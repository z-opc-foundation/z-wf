# z-wf 部署与验证手册

> z-wf 是**流程引擎中间件**（独立部署、独立 ns、HTTP 边界调用）。
> 本手册覆盖：镜像构建 → 喂 containerd → 部署 → 验证 → 回滚。
> 部署清单：[`z-wf.yaml`](z-wf.yaml)

---

## 0. 部署前必读（三条「代码齐备但从没上线过」的坑）

2026-10-21 实测：z-wf 的代码是齐的（Dockerfile / docker-compose / 独立 admin / RPC 契约都在），
但**从未在集群里跑过**。下面是踩过或验过的坑。

### 0.1 ⚠ DDL 方言（已修两轮；**第二个坑是 2026-10-22 首次部署时才发现的**）

`JdbcWorkflowPersistence.initialize()` 里的建表语句**原本按 H2 写**：
- `CLOB` —— MySQL 8.0 **语法错**（ERROR 1064）
- `CREATE INDEX IF NOT EXISTS` —— MySQL 8.0 **语法错**（ERROR 1064，不支持这个语法）

而 `application.properties` 默认数据源却是 MySQL JDBC URL ⇒ **这两条路从未同时成立过**：
H2 跑得通（全部单测都建在 H2 上），MySQL 则在第一条 `CLOB` 上就炸，
且 DDL 执行遇错即抛（`WfPersistenceException("建表失败")`）⇒ **容器起不来**。

**第一轮修**：新增 `z-wf-core` 的 `WfDdlDialect`（方言适配层）。
- H2 侧一字不改（现有 1104 个单测全绿）
- MySQL 侧：`CLOB`→`TEXT`；索引用**「先查 `DatabaseMetaData.getIndexInfo` 再建」**

**为什么索引用「先查后建」而不是「捕获 1061 忽略」**：
MySQL 上「索引已存在」是 1061 Duplicate key name，而「建索引失败」可能是 1064 语法错或 1072 列不存在。
两者若都 catch 忽略 ⇒ 索引真的建不出来时没有任何提示 ⇒ **索引悄悄缺失 ⇒ 后续查询全表扫且无任何报错**。

**第二轮修（2026-10-22 首次部署时暴露）**：`TIMESTAMP` 列在生产库上会**建表失败**。
- 实测生产 RDS 是 **MySQL 8.0.36**，`sql_mode` 含 `NO_ZERO_DATE` / `NO_ZERO_IN_DATE`，
  且 **`explicit_defaults_for_timestamp = 0`** ⇒ 裸 `TIMESTAMP` 列被隐式赋予零日期默认值
  `'0000-00-00 00:00:00'` ⇒ 零日期在 NO_ZERO_DATE 下**非法**
  ⇒ 建表报 `SQLSyntaxErrorException: Invalid default value for 'END_TIME'`。
- 报错总落在**第二个** TIMESTAMP 列上：MySQL 只给表里的第一个 TIMESTAMP 列自动
  `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，其余列拿零日期。
- 真库复现（同一台生产库）：
  ```
  CREATE TABLE (A TIMESTAMP, B TIMESTAMP)          => Invalid default value for 'B'
  CREATE TABLE (A TIMESTAMP NULL, B TIMESTAMP NULL) => 建表成功
  ```
- ⇒ `WfDdlDialect` 在 MySQL 侧把裸 `TIMESTAMP` 译成 `TIMESTAMP NULL`。
  **顺带避开第二个坑**：`explicit_defaults_for_timestamp=0` 时第一个 TIMESTAMP 列会被自动加上
  `ON UPDATE CURRENT_TIMESTAMP` ⇒ 任何一次 UPDATE 都会把它改写成当前时间
  （`START_TIME` 会被悄悄覆盖成"最后一次更新的时间"）；显式 `NULL` 把它固定成"无默认值、无自动更新"，
  与 H2 侧语义一致。
- ⚠ **踩过的坑**：崩溃重启的旧 Pod 会在你删表之后**再把表建回来**（`CREATE TABLE IF NOT EXISTS`
  遇到旧表会跳过 ⇒ 新 DDL 不生效）。⇒ 重建表前先把 Deployment 缩到 0，再删表，再拉起来。

**实测结论**（真 MySQL 8.0.36 生产库，非 H2 替身；2026-10-22 首次上线）：
```
initialize() 成功，耗时 ~400ms
13 张表（ZWF_DEFINITION/PROCESS/EXECUTION/TASK/ACTIVITY/COMMENT/JOB/
        COMPENSATION/BATCH/BATCH_ELEMENT/INCIDENT_HISTORY/FILTER/DECISION）
22 个二级索引全部真建出来（逐表核对 information_schema.STATISTICS，不是靠"没报错"推断）
第二次 initialize() 成功（幂等）
复核：带 DEFAULT_GENERATED / on update 的 TIMESTAMP 列数 = 0（应为 0）
```
> **索引口径（2026-10-22 订正，以真库为准）**：
> | 口径 | 数量 | 怎么数的 |
> |---|---|---|
> | 表 | 13 | `zwf_*` 表 |
> | **二级索引（验收口径）** | **22** | 非主键索引名去重后计数 |
> | 主键 | 13 | 每表一个 PRIMARY |
> | 索引名合计 | 35 | 22 + 13 |
>
> ⚠ **此前本手册写的「29 个索引」是误算，已作废**。误算成因：`information_schema.STATISTICS`
> 是**一列一行**（复合索引按列数重复出现），直接数行会把复合索引算多次。
> 正解是 `group by table_name, index_name` 后数索引**名**。
> ⚠ 此前那句「29 − 22 = 差额 7 来自列上的 UNIQUE 约束」**也是错的**：
> 22 个二级索引里**只有 1 个**是 UNIQUE（`UDX_WF_INCHIST_JOB`），
> 其余 21 个是非唯一索引。
> ✅ 交叉验证：源码 `JdbcWorkflowPersistence` 里 `ddl.add("CREATE INDEX …")` **恰好 22 条**
> （其中 1 条 `CREATE UNIQUE INDEX`）⇒ **源码语句数 = 库中索引数 = 22**，两侧对齐。

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

### 0.6 🔴 入站鉴权 + 可观测性（2026-10-22 上线，TASK-20261022-024）

**背景**：z-wf 于 2026-10-22 08:45 首次上线，通过 NodePort `31887` 裸暴露在集群内网，
而它**从设计上就没有任何认证层**（全仓 grep：`HandlerInterceptor` / `OncePerRequestFilter` /
`SecurityFilterChain` / `@NoLogin` 均 0 命中）⇒ 首次上线实测：
**9 条读端点匿名可读、20 条写端点全部已注册且闸门不在拦**。

**为什么不能靠主壳 SSO 闸门**：主壳闸门（z-ctc 的 `SsoInterceptor`）是**进程内**拦截器，
挂在 z-ops / z-opc 自己的应用里。z-wf 是独立进程、不经网关、不挂那个闸门
⇒ 「把路径加进 `sso.intercept-paths`」对它**无效**。

**现在的做法**：z-wf-admin 的 Web MVC 层自带一道入站闸（`WfAuthInterceptor`）：

| 项 | 口径 |
|---|---|
| 受保护面 | `/api/**`（业务接口全部） |
| 免鉴权面 | ① `/api/wf/health/probe`（**探针专用**，只回 status/engine）② `/error` ③ 静态资源与接口文档 |
| 凭据通道 | `Authorization: Bearer <token>` / 裸 `Authorization: <token>` / Cookie `sso_token`、`zctc_token`（与主壳同口径） |
| 凭据来源 ① | **服务令牌**：`Z_WF_AUTH_TOKENS`（逗号分隔，元素形如 `name:secret`；`name` 只进审计日志） |
| 凭据来源 ② | **主壳 SSO 票据**：`Z_WF_SSO_JWT_SECRET`（与 z-ctc 同一枚 HS256 密钥 ⇒ z-wf **本地离线验签**） |
| 没带凭据 / 凭据无效 | **401** + JSON 信封（不是 HTML） |
| ⚠ **带了凭据仍 401 的头号原因** | **`name:secret` 是「配置格式」，请求头只送 secret 部分** —— `name` 只进审计归因，校验时只比对 secret（`WfStaticTokenValidator` 按 `indexOf(':')` 拆名/密后比密）。<br>✅ 正确：`Authorization: Bearer <43 位 secret>` ／ 裸 `Authorization: <secret>` ／ Cookie `sso_token=<secret>`<br>❌ 错误：`Authorization: Bearer zteam:<43 位 secret>`（整串）⇒ **必 401**（2026-10-22 19:0x 实测） |
| **一个凭据来源都没配** | **503** + JSON 信封 —— **fail-closed**，不是 401，更不是放行 |
| 审计 | 应用层 `[WF-ACCESS]` 审计行（凭据名 + 路径 + 状态码 + 耗时 + 来源 IP）+ Tomcat access log |

> ⚠ **为什么 SSO 票据是「离线验签」而不是回调 z-ctc**：z-ctc 有 `GET /api/ctc/authn/verify`
> （服务端只验签不查库），本可以远端调用；但实测本集群的 NetworkPolicy **确实生效** ——
> z-wf 的 Pod 访问 z-ctc 的 ClusterIP **超时**，访问自己同 ns 的正常
> ⇒ z-ctc ns 的 netpol 没放行 z-wf 的 Pod，远端验签在当前拓扑下不可用。
> 离线验签顺带去掉了「4A 中心抖一下 ⇒ 流程引擎全挂」这个故障域。

> ⚠ **探针路径为什么从 `/api/wf/health` 换成 `/api/wf/health/probe`**：
> kubelet 的 httpGet 探针**带不了自定义请求头**（`httpHeaders` 只能写字面量、不能引用 Secret）
> ⇒ 鉴权上线后探针带不来 Authorization。而 `/api/wf/health` 在验收口径的 **17 条受测读路径**里，
> 豁免它 = 验收指标 ①（匿名可读数 = 0）直接不成立。
> ⇒ 另开一条只回 `status/engine`、**不含业务数据**的探针路径。
> **别把探针改回去。**

> ⚠ **可观测性为什么要两层**：Tomcat access log 记「连接层」（来源 IP / UA / 状态码 / 耗时）；
> 应用层 `[WF-ACCESS]` 审计行记「用的哪个凭据 + 调了哪个端点」。
> 首日上线时 z-wf **没有任何访问日志** ⇒ Pod 里那组 08:58 的请求
> （`MissingServletRequestParameterException: userId` / `任务已完成或已作废`）
> 至今**无法证明也无法排除**是被谁发的 —— 这正是本项的由来。

---

## 1. 构建镜像

z-wf 有自己的多阶段 Dockerfile（Temurin 8 JDK→JRE，非 root 用户 uid 10001）。

### 1.1 在有 docker 的节点上构建

```bash
cd z-wf
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)

# ⚠ Dockerfile 第一阶段要能解析到 parent（z-boot-parent:1.2.0，在 repo1）与兄弟仓件。
#   离线构建先确保 ~/.m2 里有；否则先 mvn -o -DskipTests install 把本仓件灌进本地仓。
# ⚠ 现役走的是 **runtime-only** 路线（见下面的 2026-10-22 18:0x 记录）：
#   ① 本地离线打 jar ② COPY 进 runtime 镜像 ③ 喂节点
#   根 Dockerfile（容器内现编）会把部署绑架在依赖下载上，别用。
docker build --platform linux/amd64 -t z-wf:2 .
```

**tag 必须是裸 tag**（`z-wf:2`），**不许带仓库前缀** ——
`ctr images import` 灌进来后镜像名就是 `docker.io/library/z-wf:2`，
清单里写带 Nexus 前缀的 tag ⇒ 名字对不上 ⇒ `ErrImageNeverPull`。
（这条坑记在 `z-opc/deploy/kubernetes/04-main.yaml` 头部，z-ops 同样。）

> ⚠ **2026-10-22 18:0x：现役 tag = `z-wf:3`，且必须「用新 tag」而不是覆盖旧 tag。**
> `z-wf:2` 是 08:54 构建的**旧 jar**（实测运行中 jar 里 `WfAuthInterceptor` / `health/probe`
> 命中数都是 **0** ⇒ 里面没有入站鉴权代码）⇒ 拿旧镜像带本清单 = 探针打
> `/api/wf/health/probe`（旧镜像没有）⇒ **404 ⇒ readiness 恒失败 ⇒ 域挂**，
> 且 5 个鉴权 env 一行都不生效（代码不在）⇒ **假安全感**。
> ⇒ **改代码后必须重建镜像**，不能只改清单。
> ⇒ **用新 tag（`:3`）而不是覆盖 `:2`**：同 tag 覆盖后旧 digest 会被 GC，
> 覆盖前先另存 tar 才能回滚；新 tag 天然留住旧 tag 作回滚锚点。
>
> runtime-only 的实际命令（2026-10-22 18:0x 实测通过）：
> ```bash
> # ① 本地离线打 jar（11.7s）
> cd z-wf && export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
> mvn -o -pl z-wf-admin -am clean package -DskipTests -Dmaven.javadoc.skip=true
> # ② 组装构建上下文（jar 要叫 z-wf-admin.jar）
> mkdir -p /tmp/zwf-build && cp z-wf-admin/target/z-wf-admin-*-exec.jar /tmp/zwf-build/z-wf-admin.jar
> cp deploy/Dockerfile.runtime /tmp/zwf-build/Dockerfile
> # ③ 构建（BASE_IMAGE 可覆盖成国内镜像源；集群两节点是 amd64 ⇒ --platform 必须写）
> cd /tmp/zwf-build && docker build --platform linux/amd64 \
>   --build-arg BASE_IMAGE=docker.1ms.run/library/eclipse-temurin:8-jre -t z-wf:3 .
> # ④ 落盘 + 两端 md5 对账 + 按文件路径 import（别用管道，见 §2）
> docker save z-wf:3 -o /tmp/z-wf-3.tar && md5 -q /tmp/z-wf-3.tar
> ```

> ⚠ **2026-10-22 首次上线实测：容器内现编会把部署绑架在依赖下载上。**
> 根 Dockerfile 的第一阶段（`maven:3.9.9-eclipse-temurin-8`）要在**容器里**从 repo1
> 重新解析整棵依赖树 —— 实测在 zifang001 上跑这条，`.m2` 15 分钟只涨到 108M 就卡住不动。
> ⇒ **推荐用 runtime-only 路线**：[`../Dockerfile.runtime`](../Dockerfile.runtime)
> —— 本地离线打 jar（`mvn -o … package`，18 秒）再 COPY 进镜像，
> 镜像内容与根 Dockerfile 走完 stage1+stage2 **等价**，差别只在「编译发生在哪台机器」。
> ⚠ 那个文件叫 `Dockerfile.runtime` 而不是 `Dockerfile.jar`：本仓 `.gitignore` 里有 `*.jar`，
> **`Dockerfile.jar` 会被 git 当二进制包忽略掉**（文件名以 `.jar` 结尾即命中）。

### 1.2 喂进 containerd（两个节点都要喂）

```bash
docker save z-wf:3 | gzip -1 > /home/zifang/zopcimg/z-wf-3.tar.gz

# ⚠ ctr 必须显式指定 address 与 namespace，否则会灌进错误的 containerd / 错误的 ns：
#    --address /run/k3s/containerd/containerd.sock  --namespace k8s.io
#    （k3s ctr 包装脚本默认带了这两个参数；直接调 ctr 时必须自己写。）

# 节点 zifang001（.231，有 docker）：
cat z-wf-2.tar.gz | sudo -n ctr --address /run/k3s/containerd/containerd.sock \
  --namespace k8s.io images import -

# 节点 zifang002（.136，pod 可能落这儿，**无 docker**）：
scp -3 z-wf-2.tar.gz zifang@192.168.31.136:/home/zifang/zopcimg/
ssh zifang@192.168.31.136 'cat /home/zifang/zopcimg/z-wf-2.tar.gz | sudo -n ctr --address /run/k3s/containerd/containerd.sock --namespace k8s.io images import -'
```

⚠ **必须是 `cat` 管道** —— `ctr images import -` 从 stdin 读，直接喂文件会报 `unrecognized image format`。
⚠ **两个节点都要喂**：未调度前不知道 pod 落谁家，少一份就 `ErrImageNeverPull`。
⚠ **.231 没有免密 sudo** ⇒ 灌镜像要走「搬运工」容器（容器内 chroot 到宿主再调 ctr）：
```bash
ssh zifang@192.168.31.231 'docker run --rm --entrypoint sh --privileged -v /:/host z-ops:272 -c \
  "chroot /host /usr/local/bin/ctr --address /run/k3s/containerd/containerd.sock --namespace k8s.io images import /home/zifang/zopcimg/z-wf-2.tar.gz"'
```
⚠ **灌完必须用 kubelet 视角复核**（`ctr images list` 看到的是 containerd 自己的账本，
不等于 kubelet 能用它）：
```bash
kubectl -n z-wf debug node/zifang001 -it --image=busybox -- chroot /host crictl images | grep z-wf
# 期望：docker.io/library/z-wf:3 / linux/amd64 / io.cri-containerd.image=managed
```

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

# 3.1) 🔴 灌入站鉴权的 Secret（2026-10-22，TASK-20261022-024）
#     ⚠ 不灌也能起（清单里 secretKeyRef 是 optional:true），
#       但**所有 /api/** 会 fail-closed 返 503 —— 这是刻意的，不是故障。
#     ⚠ token 只在本机终端生成，**不要**写进任何文件、不要提交进 git。
TOKEN=$(openssl rand -hex 32)
kubectl -n z-wf create secret generic z-wf-auth \
  --from-literal=auth-tokens="zteam:$TOKEN" \
  --dry-run=client -o yaml | kubectl apply -f -
unset TOKEN
#   可选：让主壳的 SSO 登录票据也能直接调 z-wf（需要 z-ctc 那枚 HS256 密钥）。
#   ⚠ z-ctc 的密钥是**源码里的硬编码常量**（z-ctc-sso 的 SsoAutoConfiguration#jwtUtil），
#     属 z-ctc 仓的既有账，不在本手册范围；下面从源码取，**不把明文写进本仓**：
#   SSO_SECRET=$(grep -o 'new JwtUtil("[^"]*")' \
#     <z-ctc 仓>/z-ctc-sso/src/main/java/com/zifang/ctc/sso/config/SsoAutoConfiguration.java \
#     | sed 's/.*"\(.*\)".*/\1/')
#   kubectl -n z-wf create secret generic z-wf-auth \
#     --from-literal=sso-jwt-secret="$SSO_SECRET" \
#     --dry-run=client -o yaml | kubectl apply -f -
#   unset SSO_SECRET

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
| Service (NodePort) | `z-wf-admin-np` | 排障直连：`192.168.31.136:31887`（31887 实测未占用）—— **受鉴权保护**，见 §0.6 |
| Deployment | `z-wf-admin` | 单副本 + Recreate |
| NetworkPolicy | `netpol-denyall-ingress` | 入站白名单（同 ns + 调用方 ns + 节点/LAN 段），见 §0.6 |

### 2.1 `z-wf-admin-np` 这个 NodePort 的存废（2026-10-22 决策）

**决策：保留（常驻排障口），但必须受鉴权 + 受 NetworkPolicy 约束。**

| 依据 | 说明 |
|---|---|
| 它的用途 | 宿主机排障直连（§3 的 L3 层）。z-wf 是中间件，板间调用走 ClusterIP 就够 |
| 常驻的前提 | 「有闸门」—— NodePort 与 ClusterIP 走**同一个进程**，应用层鉴权对两者一视同仁 ⇒ 保留它不会多开暴露面 |
| 为什么不撤 | 撤除动作需要 `kubectl delete`（本卡禁止 kubectl 写操作），且端口收敛归 `TASK-20261021-013` 统一排期 |
| 撤除条件 | ① 该 netpol 生效且探针/排障改走 `kubectl exec` + `kubectl port-forward` ② `TASK-20261021-013` 排到 z-wf |
| 复核口径 | 保留期间，NodePort 上的 `/api/**` 匿名必须不可读（§3 的 L4 验收） |


---

## 3. 验证（三层，逐层收紧）

> ⚠ 2026-10-22 起：`/api/**` 全部要凭据（§0.6）⇒ 下面 L2/L3 的业务调用**必须带 Authorization**。
> 探针路径（`/api/wf/health/probe`）免鉴权，是免凭据的唯一「业务口径」入口。

```bash
# L1 容器内健康（该端点真查库；免鉴权的探针路径）
kubectl -n z-wf exec deploy/z-wf-admin -- wget -q -O- http://127.0.0.1:8080/api/wf/health/probe

# L2 集群内可达（从 z-opc 容器里调 —— 这才是「中间件可被调用」的判据）
kubectl -n z-opc exec deploy/z-opc-main -- \
  wget -q -O- http://z-wf-admin.z-wf.svc.cluster.local:8080/api/wf/health/probe

# L3 宿主机直连（排障口；已受鉴权 ⇒ 业务调用要带凭据）
curl -s http://192.168.31.136:31887/api/wf/health/probe
curl -s -H "Authorization: Bearer $TOKEN" http://192.168.31.136:31887/api/wf/definitions
```

**通过判据**：

| 检查 | 期望 | 不符合时 |
|---|---|---|
| Pod Ready | 1/1 Ready，restarts=0 | 看日志有没有「建表失败」 |
| `/api/wf/health/probe` | `status: UP`（含 db 组件） | DOWN ⇒ 库连不上或表没建成 |
| 库里 13 张表 | `ZWF_*` 全在 | 查 `Z_WF_AUTO_INITIALIZE_SCHEMA` 与日志 |
| 索引 | **22 个二级索引真建出来**（`SELECT COUNT(DISTINCT index_name) FROM information_schema.STATISTICS WHERE table_schema='oc' AND table_name LIKE 'zwf%' AND index_name<>'PRIMARY'` ⇒ 22） | 若缺，说明 DDL 中途失败 |
| 接口文档 | `/doc.html` 可打开 | Knife4j 起没起来 |

### 3.0 ✅ 2026-10-22 18:0x 已执行（发版 + 灌 Secret + apply + 验收）

| 步骤 | 结果 |
|---|---|
| 重建镜像 | `z-wf:3`（config digest `sha256:d0fd5250c0411…`），tar md5 `bce09312c09a77a6efe0f72b3db76af8` 两端对账，两节点均 import |
| 灌 Secret | `z-wf-auth`：`auth-tokens`（`zteam:` / `zops:` 两枚服务令牌）+ `sso-jwt-secret`（z-ctc 同一枚 HS256 密钥，运行时从源码取，**不落本仓**） |
| apply | Deployment 镜像 `:2`→`:3` + 5 个 env + 探针路径改；**新建** `netpol-denyall-ingress`；ns / 两个 svc unchanged；策略 Recreate（单副本，短暂不可用） |
| 线上终态 | 新 Pod `z-wf-admin-56bfc444bc-vw4rw` **1/1 Ready / 0 重启**，旧 Pod 已下线，imageID = 本地构建产物 |
| 匿名读（`.231`） | 20/20 路径 **401** + JSON 信封；`Allow` 只有 `OPTIONS`；SPA HTML 兜底 **0** ⇒ 三条口径 **PASS** |
| 带凭据读（`.231`） | 20/20 路径 **200**，`Allow` 正确带上 POST/DELETE ⇒ 端点仍注册可用 |
| SSO 票据 | `Authorization: Bearer <jwt>` **200**（返回真实流程定义）；`Cookie: sso_token=<jwt>` **200** |
| 免鉴权探针 | `/api/wf/health/probe` → 200 JSON `{"data":{"status":"UP","engine":"z-wf"}…}` |
| 集群内 ClusterIP | z-opc Pod → `z-wf-admin.z-wf.svc…`：探针 200 / 业务 API 401 ⇒ netpol 白名单生效 |

> ⚠ **`.136` 节点的 NodePort 整体不可用**（节点级，与本清单无关）⇒
> 该节点上 20 条路径读数是「**连接被拒**」而不是 401
> ⇒ **「探不通」要分清「连不上」与「连上了但被拒」**，两者在验收里含义完全不同。
> 详见 lead 仓 `TASK-20261022-049`。

### 3.1 🔴 L4 鉴权验收（2026-10-22，TASK-20261022-024）

**口径（三条，缺一不可）**：

| # | 检查 | 期望 |
|---|---|---|
| ① | 17 条读路径 × 2 节点 × 3 次**匿名 GET** | **匿名可读数 = 0**（全部 401/503，且响应体是 JSON） |
| ② | 20 条写路径的 **OPTIONS**（只探注册性，不下发写请求） | `Allow` **不得再含 POST/PUT/DELETE** |
| ③ | 业务信封判据 | 响应必须是 **JSON**，**不是** SPA 兜底 HTML |

> ⚠ **③ 是最容易假绿的地方**：`z-wf` 的 `WfWebMvcConfig` 会对未知路径返 `index.html`
> ⇒ 只看 HTTP 200 会把「返回了一段 HTML」读成「接口正常」。
> ⚠ 另一个坑：`/actuator/health` 对 z-wf 会落到 SPA 回落页返 **200 HTML** ⇒ **不是健康信号**；
> 真实健康路径是 `/api/wf/health/probe`（返 JSON）。

复算脚本（只读；只发 GET 与 OPTIONS，不下发任何写请求）：

```bash
# 产物与脚本：z-opc-foundation-lead/002_项目需求/003_定时任务空间/evidence/TASK-20261022-024/
python3 verify_zwf_auth.py --base http://192.168.31.231:31887 --base http://192.168.31.136:31887
```

**跑一次真业务**（人工审批流，验 token 执行树）：

```bash
# 流程定义是 classpath 内置的两个示例（z.wf.deploy-on-startup=true 会自动部署）
curl -s -H "Authorization: Bearer $TOKEN" http://192.168.31.136:31887/api/wf/definitions | head -c 800
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://192.168.31.136:31887/api/approval-center/tasks/todo?userId=ceo'
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
| MySQL 建表 | ✅ | 13 张表 + 22 个二级索引，321ms |
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
