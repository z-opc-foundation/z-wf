package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「Camunda 依据」类断言的**自检**。
 *
 * <p>第 30 轮查出两处 Camunda 依据是错的（复杂网关的 join、delegate 的解析顺序），
 * 两处的形状完全一样：**把本实现自己的设计选择，挂上了「与 Camunda 一致」**。
 * 而这类错误静态检查看不出来、单测也测不到 —— 它只在读者去查 Camunda 文档时才暴露。
 *
 * <p>所以本类把「已经查证过的 Camunda 依据」列成清单，并断言仓库里
 * <b>不存在与清单冲突的说法</b>。新增 Camunda 依据类注释时，
 * 要么在本清单里补一条（并附出处），要么别写「与 Camunda 一致」。
 *
 * <p><b>为什么用读源码而不是读文档</b>：文档会被人手改，而源码是判据要守的东西。
 * 清单冲突检测的是**具体那句话**，不是整个文件。
 */
class WfCamundaClaimAuditTest {

    /**
     * 已经查证过、允许出现在注释里的 Camunda 依据。
     *
     * <p>每条都带出处。没有出处的断言一律不许写「与 Camunda 一致」。
     */
    private static final String[][] VERIFIED = {
            {"exclusiveGateway 汇合是穿透（joining gateway has a pass-through semantic）",
             "Camunda 官方 BPMN 2.0 参考 Exclusive Gateway 一节 + 社区版对同一模型的回答"
             + "（tokens will not join）+ 中文实践总结。三个独立来源说法一致"},
            {"camunda:asyncBefore / camunda:asyncAfter 是 Camunda 的扩展属性名",
             "docs.camunda.org/manual/7.x/reference/bpmn20/tasks/service-task 的"
             + " Camunda Extensions 属性表"},
            {"camunda:class / camunda:expression / camunda:delegateExpression 三者互斥",
             "docs.camunda.org/manual/7.17/reference/bpmn20/tasks/service-task："
             + "「are mutually exclusive. The process engine will use only one.」"},
            {"Camunda 7 与 8 执行期都不支持 complexGateway",
             "官方论坛 2025-04 员工 nathan.loding「neither 7 nor 8」；"
             + "且官方 BPMN 2.0 参考的网关章节没有 complexGateway 页"},
            {"ExecutionListener 的 transitionStart / transitionEnd 是 Camunda 的两个事件",
             "docs.camunda.org .../user-guide/process-engine/delegation-code 的"
             + " ExecutionListener 事件列表"},
    };

    /**
     * **禁止出现的说法**：它们都把本实现的设计选择说成 Camunda 的规定。
     *
     * <p>每条附上「为什么是错的」，因为只写禁止词条的话，
     * 下次有人会绕开词条重新写一遍同样的错话。
     */
    private static final String[][] FORBIDDEN = {
            {"解析顺序（与 Camunda 一致）",
             "Camunda 说的是 delegateClass/delegateExpression 互斥，"
             + "不存在「表达式优先于类」这个优先级"},
            {"与 Camunda 的 DefaultLockTime 一致",
             "Camunda 取活时锁时长由 .topic(name, millis) 显式指定，"
             + "文档里查不到「5 分钟默认」这条规定"},
            {"Camunda 把复杂网关的 join 逻辑",
             "Camunda 7/8 执行期根本不执行复杂网关，没有「留给实现」这一说"},
            {"Camunda 把这一层交给实现",
             "同上"},
            {"语义相同但不需要额外存储",
             "Camunda Category 是独立持久化实体、可被多套定义引用；"
             + "本仓只是定义上的一个字符串字段，两者不等价"},
    };

    /** 只扫源码与文档，不扫 target 与二进制。 */
    private static final String[] SCAN_ROOTS = {
            "src/main/java", "src/test/java", "src/main/resources",
    };

    @Test
    @DisplayName("仓库里不存在「把本实现的设计选择说成 Camunda 的规定」这种注释")
    void noMisattributedCamundaClaims() throws IOException {
        List<String> hits = new ArrayList<>();
        for (File file : sourceFiles()) {
            // **必须跳过本文件**：禁用词条就写在下面的清单里，
            // 不跳过的话这条判据会命中自己、并且永远是红的。
            // 子串匹配也分不清「引用某句话来否定它」与「主张它」，
            // 所以正文里一律不引用那几句原话（要说就用自己的话转述）。
            if (file.getName().equals("WfCamundaClaimAuditTest.java")) {
                continue;
            }
            String text = new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
            for (String[] row : FORBIDDEN) {
                if (text.contains(row[0])) {
                    hits.add(file.getName() + " 命中「" + row[0] + "」"
                            + System.lineSeparator() + "    为什么错：" + row[1]);
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "以下注释把本实现自己的选择说成了 Camunda 的规定 —— "
                        + "读者照着去查 Camunda 文档会查无此事：" + System.lineSeparator()
                        + String.join(System.lineSeparator(), hits)
                        + System.lineSeparator()
                        + "改法：要么改成「这是本实现的取舍」并说明理由，"
                        + "要么给出确实查证过的出处（补进 VERIFIED 清单）。");
    }

    @Test
    @DisplayName("清单里的每条依据都真的带出处（空出处等于没查证）")
    void everyVerifiedClaimHasItsSource() {
        for (String[] row : VERIFIED) {
            assertTrue(row[0] != null && !row[0].trim().isEmpty(),
                    "清单里有空断言");
            assertTrue(row[1] != null && row[1].length() >= 20,
                    "「" + row[0] + "」没有可追溯的出处 —— "
                            + "写进清单等于给自己一个不用再查的借口。实际: " + row[1]);
        }
        assertEquals(5, VERIFIED.length,
                "清单条目数变了：新增依据要同时写清出处，否则本测试只是个数检查");
    }

    // ==================== 行为本身也要钉住 ====================

    /**
     * delegateClass 与 delegateExpression 同时配 ⇒ 部署期 ERROR。
     *
     * <p><b>这条不是注释，是行为。</b>第 31 轮补上它正是因为 Camunda 明确要求互斥，
     * 而本实现原先只要求"至少配一个"，于是运行期静默挑 delegateExpression ——
     * 症状是「我明明写了类名却一直没执行」，而图上与轨迹上都看不出原因。
     */
    @Test
    @DisplayName("delegateClass 与 delegateExpression 同时配 ⇒ 部署期 ERROR")
    void delegateClassAndExpressionAreMutuallyExclusive() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"bothDelegate\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <serviceTask id=\"st\" name=\"干活\""
                + " zifang:delegateClass=\"com.example.A\""
                + " zifang:delegateExpression=\"${a}\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"st\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"st\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        // **走部署链路而不是 parse**：解析器刻意不跑校验器
        // （见 WfXmlParser 里「解析层不替作者挑一个」那段），
        // 直接调 parse 的话这道 ERROR 压根不会发生，判据问的就不是那道闸门了
        com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence repo =
                new com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence();
        repo.initialize();
        com.zifang.z.wf.core.service.WfRepositoryService repository =
                new com.zifang.z.wf.core.service.WfRepositoryService(repo);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "bothDelegate"));
        assertTrue(ex.getMessage().contains("只能留一个"),
                "报错要说清是二选一 —— 只说「不合法」的话，作者不知道要去掉哪一个。"
                        + "实际: " + ex.getMessage());
    }

    // ==================== 夹具 ====================

    private List<File> sourceFiles() throws IOException {
        List<File> files = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            File dir = new File(root);
            collect(dir, files);
        }
        return files;
    }

    private void collect(File dir, List<File> out) {
        if (!dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().endsWith(".java") || child.getName().endsWith(".md")) {
                out.add(child);
            }
        }
    }
}