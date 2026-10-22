# z-wf 部署与验证手册

> z-wf 是**流程引擎中间件**（独立部署、独立 ns、HTTP 边界调用）。
> 本手册覆盖：镜像构建 → 喂 containerd → 部署 → 验证 → 回滚。
> 部署清单：[`z-wf.yaml`](z-wf.yaml)

---

## 0. 部署前必读（三条「代码齐备但从没上线过」的坑）

2026-10-21 实测：z-wf 的代码是齐的（Dockerfile / docker-compose / 独立 admin / RPC 契约都在），
但**从未在集群里跑过**。下面是踩过或验过的坑。

### 0.1 ⚠ DDL 方言（已修，但要知道它曾经是坏的）

`JdbcWorkflowPersistence.initialize()` 里的建表语句**原本按 H2 写**：
- `CLOB` —— MySQL 8.0 **语法错**（ERROR 1064）
- `CREATE INDEX IF NOT EXISTS` —— MySQL 8.0 **语法错**（ERROR 1064，不支持这个语法）

而 `application.properties` 默认数据源却是 MySQL JDBC URL ⇒ **这两条路从未同时成立过**：
H2 跑得通（全部单测都建在 H2 上），MySQL 则在第一条 `CLOB` 上就炸，
且 DDL 执行遇错即抛（`WfPersistenceException("建表失败")`）⇒ **容器起不来**。

**已修**：新增 `z-wf-core` 的 `WfDdlDialect`（方言适配层）。
- H2 侧一字不改（现有 1102 个单测全绿）
- MySQL 侧：`CLOB`→`TEXT`；索引用**「先查 `DatabaseMetaData.getIndexInfo` 再建」**

**为什么索引用「先查后建」而不是「捕获 1061 忽略」**：
MySQL 上「索引已存在」是 1061 Duplicate key name，而「建索引失败」可能是 1064 语法错或 1072 列不存在。
两者若都 catch 忽略 ⇒ 索引真的建不出来时没有任何提示 ⇒ **索引悄悄缺失 ⇒ 后续查询全表扫且无任何报错**。

**实测结论**（真 MySQL 8.0.46，非 H2 替身）：
```
initialize() 成功，耗时 321ms
13 张表（ZWF_DEFINITION/PROCESS/EXECUTION/TASK/ACTIVITY/COMMENT/JOB/
        COMPENSATION/BATCH/BATCH_ELEMENT/INCIDENT_HISTORY/FILTER/DECISION）
29 个索引全部真建出来（逐表核对 getIndexInfo，不是靠"没报错"推断）
第二次 initialize() 成功（幂等）
```

### 0.2 ⚠ 库不需要手工建

清单里指向**阿里云 RDS 的 `oc` 库**（与 z-ops / z-opc 系各仓同库，见 `z-ops/04-admin.yaml` 的
`OPS_DB_NAME=oc`）。库已存在，**不需要** `CREATE DATABASE`。
13 张 `ZWF_*` 表由 `Z_WF_AUTO_INITIALIZE_SCHEMA=true` 在启动时建（`CREATE TABLE IF NOT EXISTS`）。

> 若要独立库（`z_wf`），先手工 `CREATE DATABASE z_wf`，再把 Secret `aliyun-db` 里的
> `db-url` 改成指向它（库名**不在清单里**，在 Secret 的 `db-url` 值中）。

### 0.3 ⚠ 无 DataSource 会静默降级（但探针能发现）

`WfAutoConfiguration` 里：`z.wf.persistence=jdbc` 但拿不到 DataSource ⇒
**回落内存实现**，日志一行 WARN ⇒ **流程数据不落库、重启即丢**。

清单的 readiness/liveness 都打 `/api/wf/health` —— 该端点**真查一次库**（不是只报进程活着），
存储不可用时 status 变 DOWN ⇒ 降级会被探针发现，不会把「假健康」当成好。

### 0.4 ⚠ 库地址走 Secret，清单里不留明文主机名

JDBC URL 的 host **不写**在清单里，而是整条走 `secretKeyRef`（`aliyun-db` / `db-url`），
账号走 `db-username`，口令走 `db-password` —— 与 z-opc 主启动器**同一口径**
（z-opc 生产在跑，`z-opc-main` 2 副本就绪，用的就是这三个键）。

> 2026-10-22 修订：此前本清单把 RDS 主机名写成字面量，理由是「k8s 的 `env.value`
> 不做 `$(VAR)` 插值」。即便该理由成立，**把库地址写进 git 历史也不合规**：
> 组织边界是「除 z-opc / lead 外，任何 z-* 仓不得再有敏感 commit」。
> ⇒ 改为照抄 z-opc 的 `secretKeyRef` 口径，不再依赖 env 展开语义。
>
> **代价**：`aliyun-db` Secret 必须含 `db-url` / `db-username` / `db-password` 三个键。
> **z-ops 的同名 secret 只有 `db-host` / `db-password`** ⇒ 灌 Secret **必须从 `z-opc` ns 抄**，
> 抄 z-ops 会让容器直接起不来（`CreateContainerConfigError: secret "aliyun-db" not found` 类缺键）。

### 0.5 ⚠ 单副本 + `Recreate` 策略（不是滚动更新）

`ZWF_JOB` 表里有定时器型 job 在跑。滚动更新会出现「新旧两个实例同时扫同一批到期 job」
⇒ **同一 job 被执行两次**。所以 `strategy.type: Recreate`，`replicas: 1`。

---

## 1. 构建镜像

z-wf 有自己的多阶段 Dockerfile（Temurin 8 JDK→JRE，非 root 用户 uid 10001）。

### 1.1 在有 docker 的节点上构建

```bash
cd z-wf
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)

# ⚠ Dockerfile 第一阶段要能解析到 parent（z-boot-parent:1.1.0，在 repo1）与兄弟仓件。
#   离线构建先确保 ~/.m2 里有；否则先 mvn -o -DskipTests install 把本仓件灌进本地仓。
docker build --platform linux/amd64 -t z-wf:1 .
```

**tag 必须是裸 tag**（`z-wf:1`），**不许带仓库前缀** ——
`k3s ctr images import` 灌进来后镜像名就是 `docker.io/library/z-wf:1`，
清单里写带 Nexus 前缀的 tag ⇒ 名字对不上 ⇒ `ErrImageNeverPull`。
（这条坑记在 `z-opc/deploy/kubernetes/04-main.yaml` 头部，z-ops 同样。）

### 1.2 喂进 containerd（两个节点都要喂）

```bash
docker save z-wf:1 | gzip -1 > /home/zifang/zopcimg/z-wf-1.tar.gz

# 节点 zifang001（.231，有 docker）：
cat z-wf-1.tar.gz | sudo -n k3s ctr images import -

# 节点 zifang002（.136，pod 可能落这儿，**无 docker**）：
scp -3 z-wf-1.tar.gz zifang@192.168.31.136:/home/zifang/zopcimg/
ssh zifang@192.168.31.136 'cat /home/zifang/zopcimg/z-wf-1.tar.gz | sudo -n k3s ctr images import -'
```

⚠ **必须是 `cat` 管道** —— `ctr images import -` 从 stdin 读，直接喂文件会报 `unrecognized image format`。
⚠ **两个节点都要喂**：未调度前不知道 pod 落谁家，少一份就 `ErrImageNeverPull`。

---

## 2. 部署

```bash
# 1) 先建 ns —— Secret 必须落在已存在的 ns 里（z-wf.yaml 里也含 ns，但清单整体
#    apply 之前它并不存在 ⇒ 先建 ns，后面灌 Secret 才有地方落）
kubectl create namespace z-wf --dry-run=client -o yaml | kubectl apply -f -

# 2) 再灌 Secret aliyun-db —— 必须含 db-url / db-username / db-password 三个键
#    ⚠ **从 z-opc ns 抄**，不是 z-ops：z-ops 的同名 secret 只有 db-host/db-password，
#      缺 db-url/db-username ⇒ 抄 z-ops 容器起不来（见 §0.4）
kubectl -n z-opc get secret aliyun-db -o yaml | \
  yq 'del(.metadata) | .metadata.name = "aliyun-db"' | kubectl apply -n z-wf -f -

# 3) dry-run 校验
kubectl apply --dry-run=client -f z-wf/deploy/kubernetes/z-wf.yaml

# 4) 正式部署
kubectl apply -f z-wf/deploy/kubernetes/z-wf.yaml

# 5) 看状态
kubectl -n z-wf get pods,svc
```

⚠ **顺序不能反**：先灌 Secret 再建 ns ⇒ 第二步报 `namespaces "z-wf" not found`；
先 apply 清单再灌 Secret ⇒ Pod 会先起不来（缺 secret 键 ⇒ CreateContainerConfigError），
Secret 补上后 Pod 不会自动重建，**要 `kubectl -n z-wf rollout restart deploy/z-wf-admin`**。

**清单创建的资源**：

| 资源 | 名 | 说明 |
|---|---|---|
| Namespace | `z-wf` | 独立 ns |
| Service (ClusterIP) | `z-wf-admin` | 集群内调用：`http://z-wf-admin.z-wf.svc.cluster.local:8080` |
| Service (NodePort) | `z-wf-admin-np` | 排障直连：`192.168.31.136:31887`（31887 实测未占用） |
| Deployment | `z-wf-admin` | 单副本 + Recreate |

---

## 3. 验证（三层，逐层收紧）

```bash
# L1 容器内健康（该端点真查库）
kubectl -n z-wf exec deploy/z-wf-admin -- wget -q -O- http://127.0.0.1:8080/api/wf/health

# L2 集群内可达（从 z-opc 容器里调 —— 这才是「中间件可被调用」的判据）
kubectl -n z-opc exec deploy/z-opc-main -- \
  wget -q -O- http://z-wf-admin.z-wf.svc.cluster.local:8080/api/wf/health

# L3 宿主机直连
curl -s http://192.168.31.136:31887/api/wf/health
```

**通过判据**：

| 检查 | 期望 | 不符合时 |
|---|---|---|
| Pod Ready | 1/1 Ready，restarts=0 | 看日志有没有「建表失败」 |
| `/api/wf/health` | `status: UP`（含 db 组件） | DOWN ⇒ 库连不上或表没建成 |
| 库里 13 张表 | `ZWF_*` 全在 | 查 `Z_WF_AUTO_INITIALIZE_SCHEMA` 与日志 |
| 索引 | 29 个真建出来（`SHOW INDEX FROM ZWF_PROCESS`） | 若缺，说明 DDL 中途失败 |
| 接口文档 | `/doc.html` 可打开 | Knife4j 起没起来 |

**跑一次真业务**（人工审批流，验 token 执行树）：

```bash
# 流程定义是 classpath 内置的两个示例（z.wf.deploy-on-startup=true 会自动部署）
curl -s http://192.168.31.136:31887/api/wf/definitions | head -c 800
curl -s 'http://192.168.31.136:31887/api/approval-center/tasks/todo?userId=ceo'
```

---

## 4. 机器领活契约（这才是 z-wf 对「AI 全流程」的价值）

部署后，z-team 的 AI 就能通过 HTTP 边界领**带租约**的活。

| 端点 | 语义 |
|---|---|
| `POST /api/wf/external-tasks/fetch` | 按 topic 原子选出 + 上锁（**租约制**，到期重领） |
| `POST /api/wf/external-tasks/{id}/complete` | 交差，token 继续往下走 |
| `POST /api/wf/external-tasks/{id}/fail` | 解锁并扣重试（失败多半是瞬时的，不解锁等于永久卡死） |
| `POST /api/wf/external-tasks/{id}/bpmn-error` | 报错交回走错误边界（确定失败 ≠ 再试一次） |
| `POST /api/wf/external-tasks/{id}/release` | 主动释放，解锁但不扣重试 |
| `GET /api/wf/external-tasks/locked?workerId=` | 某 worker 锁着哪些活 |
| `GET /api/wf/external-tasks/count?topic=` | 某主题积压多少 |

**与 z-team 现有 task 机制的差别（这才是要不要用的理由）**：

| | z-team `z_team_task` | z-wf 外部任务 |
|---|---|---|
| 认领 | 乐观认领 PENDING→RUNNING | 原子选出 + 上锁 |
| **租约** | **无** | **有**（到期重领） |
| worker 崩了 | 悬在 RUNNING，靠超时兜底回收 | 自动释放、可被重领 |
| 失败 | 无重试扣减语义 | fail 扣重试 / bpmn-error 走错误边界 / release 不扣 |

⇒ **「AI 领活、崩了能自愈」这套租约语义，全组织只存在于 z-wf。**

---

## 5. 回滚

```bash
# 1) 停（保留数据）
kubectl -n z-wf scale deploy/z-wf-admin --replicas=0

# 2) 彻底撤（数据在 RDS 的 oc 库里，不随 Pod 消失）
kubectl delete -f z-wf/deploy/kubernetes/z-wf.yaml

# 3) 确认表还在（重部署后数据仍在）
# mysql> SHOW TABLES LIKE 'ZWF_%';
```

**回滚不删数据**：13 张 `ZWF_*` 表在共享 RDS 的 `oc` 库里，
删 Deployment/Service 不会动它们。若要清数据，需**手工** `DROP TABLE`（走评审）。

⚠ **不要** `kubectl delete ns z-wf --force` 去「清干净」：
那会连带删掉 ns 里的其它资源，且**不影响** RDS 里的表 ⇒ 制造「以为清了其实没清」的假象。

---

## 6. 验证记录（2026-10-21）

| 项 | 状态 | 证据 |
|---|---|---|
| DDL 方言缺陷定位 | ✅ | 真 MySQL 8.0.46 实跑：`CLOB` 与 `CREATE INDEX IF NOT EXISTS` 均 ERROR 1064 |
| DDL 修复 | ✅ | `WfDdlDialect`；H2 侧一字不改 |
| MySQL 建表 | ✅ | 13 张表 + 29 索引，321ms |
| 幂等 | ✅ | 第二次 `initialize()` 成功 |
| H2 回归 | ✅ | z-wf-core **1102/1102** 全绿（原 1093 + 新增 9） |
| 清单语法 | ✅ | `kubectl apply --dry-run=client` 4 资源全过 |
| NodePort 不撞号 | ✅ | 31887 实测未占用（已用集合 31 个，最大 31976） |
| 集群内实际部署 | ⏳ **未做** | 需镜像构建 + 喂 containerd + apply |

---

## 7. 相关文档

| 文档 | 位置 |
|---|---|
| 部署清单 | `z-wf/deploy/kubernetes/z-wf.yaml` |
| z-wf 仓 README（能力/坐标/选型史） | `z-wf/README.md` |
| 方言适配层 | `z-wf/z-wf-core/.../persistence/WfDdlDialect.java` |
| 方言回归测试 | `z-wf/z-wf-core/src/test/.../WfDdlDialectTest.java` |
| 集群产品册子 | `z-team/_skills/cluster-product-catalog/` |
| 镜像构建口径 | `z-opc/deploy/kubernetes/04-main.yaml`（头部注释） |
