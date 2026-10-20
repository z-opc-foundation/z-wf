package com.zifang.z.wf.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.zifang.z.wf.core.config.WfProperties;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.hook.WfNotificationHook;
import com.zifang.z.wf.core.hook.WfProcessHook;
import com.zifang.z.wf.core.hook.WfTaskHook;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * z-wf README 定量主张自守卫 —— README 里写死的版本、模块清单、节点类型种数、
 * 持久化表与索引数、SPI 方法数、钩子扩展点数、配置项默认值、测试类清单，
 * 必须能从源码重算出来；README 改了就红，代码没动就不红。
 *
 * <p><b>为什么要有这一层</b>：本仓 README 把「当前版本 2.0.0」「四模块」
 * 「节点类型共 22 种」「13 张表 + 15 个索引」「{@code WfPersistence} SPI 25 个方法」
 * 「3 个 hook」「配置项若干」写成了硬数字。此前**没有任何尺子**去问代码「现在还是吗」，
 * 这些数字的保质期就只剩上一次编辑 README 的那一刻 ——
 * 实际已经漂了：节点类型 22 → 25、索引 15 → 22、SPI 25 → 78、扩展点 11 → 15。</p>
 *
 * <p><b>口径四条（都是踩过的坑）</b>：</p>
 * <ol>
 *   <li><b>事实对象只装源码侧事实</b>（{@link Facts}）—— README 侧的数值一律在核对
 *       <b>当下</b>从被核对的那份 README 里现解析。两边都从同一份 README 取数，
 *       尺子就成了恒等式比较，README 改成 9.9.9 尺子照样绿；</li>
 *   <li><b>主张抠不到就红</b>：正则没匹配上不许静默跳过，那正是空跑；</li>
 *   <li><b>按小节名 / 表格字段定位，不按数字定位</b>：短数字是长数字的子串
 *       （「13 张表」里「13」也可能出现在别的行上），按数字定位会匹配到别人的行上；</li>
 *   <li><b>清单类比集合，不比「每个都被点名」</b>：把一个类名换成另一个同样存在的类名，
 *       「每个都被点名」照样成立 ⇒ 尺子必须比集合。</li>
 * </ol>
 *
 * <p>数值不等 = 红，且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 核对结果<b>一次全报</b>（收集齐所有不一致再抛），撞停会把尺子退化成「一次只说一句」。</p>
 *
 * <p>本仓既有测试是 JUnit 5（{@code org.junit.jupiter.api}），本守卫沿用同一口径 ——
 * 混用引擎时静默不跑比红更坏。</p>
 */
public class ReadmeClaimsTest {

    /** 仓根标志：artifactId 命中 + packaging=pom + 真带 <module>（子模块 pom 的 parent 里也有同名 artifactId）。 */
    private static final String ROOT_ARTIFACT_ID = "z-wf";

    /** 源码侧路径一律相对仓根写死，不按目录名统计（多模块仓里同名目录不止一处）。 */
    private static final String JDBC_PERSISTENCE =
            "z-wf-core/src/main/java/com/zifang/z/wf/core/persistence/JdbcWorkflowPersistence.java";

    /** 钩子接口里那个只用来报身份、不参与回调触发的方法名 —— 它不是「扩展点」。 */
    private static final String HOOK_TYPE_MARKER = "hookType";

    // ==================================================================
    // 基础设施
    // ==================================================================

    /**
     * 定位仓根：从 {@code user.dir} 向上找<b>聚合根</b>的 pom.xml。
     *
     * <p>判据同时满足：<b>项目自身</b>的 artifactId 命中（用 XML 解析取顶层
     * {@code <artifactId>}，不是全文 contains —— 子模块 pom 的 {@code <parent>} 里
     * 就写着 {@code z-wf}，全文匹配会把子模块误判成仓根）+
     * {@code <packaging>pom</packaging>} + 至少一个 {@code <module>} + 同目录有 README。</p>
     */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && p != null; i++, p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.isRegularFile(p.resolve("README.md"))) {
                String t = stripXmlComments(readText(pom));
                boolean isRoot = ownArtifactId(t).equals(ROOT_ARTIFACT_ID)
                        && t.contains("<packaging>pom</packaging>")
                        && Pattern.compile("<module>").matcher(t).find();
                if (isRoot) {
                    return p;
                }
            }
        }
        throw new IllegalStateException("未找到 z-wf 仓根：从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到同时满足 artifactId=" + ROOT_ARTIFACT_ID
                + " / packaging=pom / 带 <module> / 同目录有 README.md 的 pom.xml");
    }

    /** 取 pom 里<b>项目自身</b>的 artifactId（顶层元素，不是 parent 里的那个）。 */
    private static String ownArtifactId(String pomText) {
        Document doc = parseXml(pomText);
        NodeList kids = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "artifactId".equals(n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("XML 解析失败", e);
        }
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读文件失败：" + p, e);
        }
    }

    /** 剥掉 XML 注释（根 pom 里注释掉的 {@code <module>} 会骗过正则）。 */
    private static String stripXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 剥掉 Java 注释（块注释 + 行注释），字符串字面量原样保留。
     *
     * <p>数 {@code @Test} 方法时必须先剥注释：Javadoc 里出现过 {@code @Test} 字样
     * 就会被当成真测试方法。</p>
     */
    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                sb.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        sb.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ==================================================================
    // README 侧解析
    // ==================================================================

    private static int headingLevel(String heading) {
        int n = 0;
        while (n < heading.length() && heading.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    /**
     * 去掉行首 #、emoji 与变体选择符，剩下的就是标题文字。
     *
     * <p>emoji 用码点判断（不吃编译编码的亏）：U+1F300–U+1FAFF、U+2600–U+27BF、
     * 变体选择符 U+FE0F、零宽连接符 U+200D。</p>
     */
    private static String headingText(String raw) {
        String t = raw.trim();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        t = t.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            int cp = t.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x200D
                    || (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)) {
                if (cp > 0xFFFF) {
                    i++;
                }
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    /**
     * 取某个标题的小节正文（到下一个「同级或更高级」标题为止，{@code ###} 子标题仍属本节）。
     *
     * <p>围栏代码块内以 # 开头的行（项目结构树）不算标题，必须跳过。</p>
     */
    private static String section(String readme, String headingText) {
        String[] lines = readme.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        boolean started = false;
        int level = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                if (started) {
                    sb.append(line).append('\n');
                }
                continue;
            }
            if (!inFence && t.startsWith("#")) {
                int lv = headingLevel(t);
                String text = headingText(t);
                if (started) {
                    if (lv <= level) {
                        break;
                    }
                } else {
                    if (text.equals(headingText)) {
                        started = true;
                        level = lv;
                        continue;
                    }
                }
            }
            if (started) {
                sb.append(line).append('\n');
            }
        }
        if (!started) {
            throw new IllegalStateException("README 里找不到标题小节：" + headingText);
        }
        return sb.toString();
    }

    /** 抠出 {@code `xxx`} 形式的反引号片段。 */
    private static List<String> backticked(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`\\n]+)`").matcher(text);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    /** 去掉表格单元里的 markdown 强调符号与反引号。 */
    private static String plain(String cell) {
        return cell.replace("*", "").replace("`", "").trim();
    }

    /**
     * 取一张表格的<b>数据行</b>（去掉表头与分隔行），每行按 {@code |} 切成单元格。
     *
     * <p>表格可能跨行折断（Markdown 里换行会让一行劈成两行），所以这里按
     * 「以 | 开头」聚合，而不是假设一行就是一个完整记录。</p>
     */
    private static List<List<String>> tableRows(String sectionText) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = null;
        for (String line : sectionText.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String c : t.split("\\|", -1)) {
                cells.add(c.trim());
            }
            // 切完首尾各多一个空串
            if (cells.size() >= 2) {
                cells.remove(0);
                cells.remove(cells.size() - 1);
            }
            boolean separator = cells.size() > 1 && cells.get(0).matches(":?-{2,}:?");
            if (separator) {
                // 分隔行紧跟在表头后面 ⇒ 刚加进去的那行是表头，撤掉
                if (current != null && !rows.isEmpty() && rows.get(rows.size() - 1) == current) {
                    rows.remove(rows.size() - 1);
                }
                current = null;
                continue;
            }
            if (current == null) {
                current = cells;
                rows.add(current);
            } else {
                // 上一行没闭合（折行）：把这一行接上去
                for (int i = 1; i < cells.size(); i++) {
                    current.add(cells.get(i));
                }
            }
            // 闭合判定：以 | 结尾才是完整的一行。
            // 少了这一句，**每一行**数据行都会被当成上一行的折行续行接下去 ——
            // 于是整张表只剩第一行数据行独立存在，模块表只认出第一个模块、
            // 钩子表只认出一个接口。尺子先失灵、后冤枉健康页。
            if (t.endsWith("|")) {
                current = null;
            }
        }
        return rows;
    }

    /** camelCase → kebab-case；首字母大写<b>不</b>转横线（Session → session，不是 -session）。 */
    private static String toKebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 源码侧事实
    // ==================================================================

    /** 事实对象：<b>只装源码侧</b>的事实。README 侧的数字一律现解析。 */
    private static final class Facts {
        String revision = "";
        List<String> modules = new ArrayList<>();
        List<String> nodeTypes = new ArrayList<>();
        int tableCount;
        int indexCount;
        int persistenceMethodCount;
        /** 钩子接口名 → 该接口的扩展点回调方法名（集合，不含顺序）。 */
        Map<String, Set<String>> hookCallbacks = new LinkedHashMap<>();
        /** 配置键（{@code z.wf.xxx}）→ 默认值字面量。 */
        Map<String, String> configDefaults = new LinkedHashMap<>();
        /** 测试类简单名 → 该类的 {@code @Test} 方法数。 */
        Map<String, Integer> testClassCounts = new LinkedHashMap<>();
        /** 根 pom {@code <properties>} 里的 z-util 版本（协议层与 parser 必须同版本）。 */
        String rootZUtilVersion = "";
        /** 根 pom {@code <properties>} 里的 z-util-wf 版本键（协议层）。 */
        String rootZUtilWfVersion = "";
        /** z-wf-admin/pom.xml 里的 z-util 版本（own-parent，只能自己钉）。 */
        String adminZUtilVersion = "";
        /** 根 pom 的 dependencyManagement 里显式钉住的 z-util 坐标（集合）。 */
        Set<String> rootPinnedZUtil = new TreeSet<>();
        /** z-wf-admin/pom.xml 的 dependencyManagement 里显式钉住的 z-util 坐标（集合）。 */
        Set<String> adminPinnedZUtil = new TreeSet<>();
    }

    private static Facts facts() {
        Path root = repoRoot();
        Facts f = new Facts();

        // 根 pom：<revision> 与 <modules>（保序）
        String rootPom = stripXmlComments(readText(root.resolve("pom.xml")));
        Document doc = parseXml(rootPom);
        NodeList kids = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = n.getNodeName();
            if ("properties".equals(name)) {
                NodeList pk = n.getChildNodes();
                for (int j = 0; j < pk.getLength(); j++) {
                    Node p = pk.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE && "revision".equals(p.getNodeName())) {
                        f.revision = p.getTextContent().trim();
                    }
                }
            } else if ("modules".equals(name)) {
                NodeList mk = n.getChildNodes();
                for (int j = 0; j < mk.getLength(); j++) {
                    Node m = mk.item(j);
                    if (m.getNodeType() == Node.ELEMENT_NODE && "module".equals(m.getNodeName())) {
                        f.modules.add(m.getTextContent().trim());
                    }
                }
            }
        }

        // z-util 钉版：两个 pom 各自的属性与 dependencyManagement。
        // admin 是 own-parent，父链那套到不了它 —— **必须单独采**，
        // 只采根 pom 会把 admin 悄悄漏掉，而 README 里两处都要写。
        f.rootZUtilVersion = pomProperty(rootPom, "z-util.version");
        f.rootZUtilWfVersion = pomProperty(rootPom, "z-util-wf.version");
        f.rootPinnedZUtil = pinnedZUtilCoordinates(rootPom);
        String adminPom = stripXmlComments(readText(root.resolve("z-wf-admin/pom.xml")));
        f.adminZUtilVersion = pomProperty(adminPom, "z-util.version");
        f.adminPinnedZUtil = pinnedZUtilCoordinates(adminPom);

        // 节点类型：枚举真身（不是 names() 那份手抄清单 —— 它漏过一个类型）
        for (WfNodeType t : WfNodeType.values()) {
            f.nodeTypes.add(t.name());
        }

        // 持久化：DDL 里真建的表与索引
        String jdbc = stripComments(readText(root.resolve(JDBC_PERSISTENCE)));
        Set<String> tables = new TreeSet<>();
        Matcher tm = Pattern.compile("CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)").matcher(jdbc);
        while (tm.find()) {
            tables.add(tm.group(1));
        }
        Set<String> indexes = new TreeSet<>();
        Matcher im = Pattern.compile("CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)").matcher(jdbc);
        while (im.find()) {
            indexes.add(im.group(1));
        }
        f.tableCount = tables.size();
        f.indexCount = indexes.size();

        // 持久化 SPI 的方法面：接口真身
        f.persistenceMethodCount = WfPersistence.class.getDeclaredMethods().length;

        // 钩子：三个接口的扩展点回调（身份标记方法不算回调）
        for (Class<?> hook : Arrays.asList(WfProcessHook.class, WfTaskHook.class, WfNotificationHook.class)) {
            Set<String> callbacks = new TreeSet<>();
            for (Method m : hook.getDeclaredMethods()) {
                if (!HOOK_TYPE_MARKER.equals(m.getName())) {
                    callbacks.add(m.getName());
                }
            }
            f.hookCallbacks.put(hook.getSimpleName(), callbacks);
        }

        // 配置项：新实例上的字段值就是默认值
        WfProperties props = new WfProperties();
        for (Field field : WfProperties.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object v = field.get(props);
                f.configDefaults.put("z.wf." + toKebab(field.getName()), String.valueOf(v));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("读配置字段默认值失败：" + field.getName(), e);
            }
        }

        // 测试类清单：各模块 src/test 下的 @Test 方法数
        Pattern testAnn = Pattern.compile("^[ \\t]*@(?:[\\w.]+\\.)?Test\\b", Pattern.MULTILINE);
        for (String module : f.modules) {
            Path testRoot = root.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(testRoot)) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String simple = p.getFileName().toString();
                    simple = simple.substring(0, simple.length() - ".java".length());
                    int n = 0;
                    Matcher testMatcher = testAnn.matcher(stripComments(readText(p)));
                    while (testMatcher.find()) {
                        n++;
                    }
                    f.testClassCounts.put(simple, n);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return f;
    }

    // ==================================================================
    // 核对：README（现解析） ↔ Facts（源码侧）
    // ==================================================================

    private static List<String> verify(String readme, Facts f) {
        List<String> problems = new ArrayList<>();

        // ---- 当前版本：四处提及都必须等于根 POM 的 <revision> ----
        // 定位用「短语 + 版本号」的形状，不用裸数字：README 里还有 1.0.4/1.0.5/1.0.6
        // 那批历史发布版本，按数字定位会抓到历史叙述上去。
        verifyVersion(readme, "为什么本仓是", "【版本·标题】", f, problems);
        verifyVersion(readme, "破坏性大版本 `", "【版本·坐标声明】", f, problems);
        verifyVersion(readme, "升到 ", "【版本·升级说明】", f, problems);
        verifyVersion(readme, "锁死 `", "【版本·区间建议】", f, problems);

        // ---- 模块清单：集合相等（不是「每个都被点名」）----
        String modulesSection = section(readme, "1. 模块");
        Set<String> readmeModules = new LinkedHashSet<>();
        for (List<String> row : tableRows(modulesSection)) {
            if (row.size() < 2) {
                continue;
            }
            for (String b : backticked(row.get(0))) {
                if (b.startsWith(ROOT_ARTIFACT_ID + "-")) {
                    readmeModules.add(b);
                }
            }
        }
        if (readmeModules.isEmpty()) {
            problems.add("【模块】模块表里一个模块名都没抠到");
        }
        Set<String> sourceModules = new TreeSet<>(f.modules);
        if (!new TreeSet<>(readmeModules).equals(sourceModules)) {
            problems.add("【模块】README 模块表列了 " + readmeModules
                    + "（共 " + readmeModules.size() + " 个），根 POM <modules> 是 " + sourceModules
                    + "（共 " + sourceModules.size() + " 个）");
        }

        // ---- 节点类型：枚举常量集合相等 + 写死的种数 ----
        String nodeSection = section(readme, "3.2 节点类型");
        Set<String> readmeTypes = new TreeSet<>();
        for (String b : backticked(nodeSection)) {
            if (b.matches("[A-Z][A-Z_]*")) {
                readmeTypes.add(b);
            }
        }
        Set<String> sourceTypes = new TreeSet<>(f.nodeTypes);
        if (readmeTypes.isEmpty()) {
            problems.add("【节点类型】节点类型一节里没抠出任何类型名");
        } else if (!readmeTypes.equals(sourceTypes)) {
            Set<String> missing = new TreeSet<>(sourceTypes);
            missing.removeAll(readmeTypes);
            Set<String> extra = new TreeSet<>(readmeTypes);
            extra.removeAll(sourceTypes);
            problems.add("【节点类型】README 列了 " + readmeTypes.size() + " 种，枚举 WfNodeType 有 "
                    + sourceTypes.size() + " 种；README 少写 " + missing + "，多写 " + extra);
        }
        Matcher kindCount = Pattern.compile("共\\s*([0-9]+)\\s*种").matcher(nodeSection);
        if (!kindCount.find()) {
            problems.add("【节点类型】节点类型一节里找不到「共 N 种」这个计数");
        } else if (Integer.parseInt(kindCount.group(1)) != sourceTypes.size()) {
            problems.add("【节点类型】README 写「共 " + kindCount.group(1) + " 种」，"
                    + "枚举 WfNodeType 实有 " + sourceTypes.size() + " 种");
        }

        // ---- 持久化：SPI 方法数 / 表数 / 索引数 ----
        String persistenceSection = section(readme, "5. 持久化");
        Matcher spiCount = Pattern.compile("SPI（([0-9]+)\\s*个方法）").matcher(persistenceSection);
        if (!spiCount.find()) {
            problems.add("【持久化 SPI】持久化一节里找不到「SPI（N 个方法）」");
        } else if (Integer.parseInt(spiCount.group(1)) != f.persistenceMethodCount) {
            problems.add("【持久化 SPI】README 写 " + spiCount.group(1) + " 个方法，"
                    + "WfPersistence 接口实有 " + f.persistenceMethodCount + " 个方法");
        }
        Matcher tableCount = Pattern.compile("([0-9]+)\\s*张表").matcher(persistenceSection);
        if (!tableCount.find()) {
            problems.add("【持久化·表】持久化一节里找不到「N 张表」");
        } else if (Integer.parseInt(tableCount.group(1)) != f.tableCount) {
            problems.add("【持久化·表】README 写 " + tableCount.group(1) + " 张表，"
                    + "JDBC 实现真建 " + f.tableCount + " 张表");
        }
        Matcher indexCount = Pattern.compile("([0-9]+)\\s*个索引").matcher(persistenceSection);
        if (!indexCount.find()) {
            problems.add("【持久化·索引】持久化一节里找不到「N 个索引」");
        } else if (Integer.parseInt(indexCount.group(1)) != f.indexCount) {
            problems.add("【持久化·索引】README 写 " + indexCount.group(1) + " 个索引，"
                    + "JDBC 实现真建 " + f.indexCount + " 个索引");
        }

        // ---- 钩子：接口数 + 每个接口的扩展点回调集合 ----
        String hookSection = section(readme, "6. 服务与扩展");
        Matcher hookCount = Pattern.compile("钩子（([0-9]+)\\s*个接口").matcher(hookSection);
        if (!hookCount.find()) {
            problems.add("【钩子】服务与扩展一节里找不到「钩子（N 个接口」这个计数");
        } else if (Integer.parseInt(hookCount.group(1)) != f.hookCallbacks.size()) {
            problems.add("【钩子】README 写 " + hookCount.group(1) + " 个接口，源码里是 "
                    + f.hookCallbacks.size() + " 个：" + f.hookCallbacks.keySet());
        }
        Map<String, Set<String>> readmeHooks = new LinkedHashMap<>();
        for (List<String> row : tableRows(hookSection)) {
            if (row.size() < 2) {
                continue;
            }
            String iface = plain(row.get(0));
            if (!f.hookCallbacks.containsKey(iface)) {
                continue;
            }
            Set<String> methods = new TreeSet<>();
            for (String b : backticked(row.get(1))) {
                if (b.matches("[a-zA-Z][a-zA-Z0-9]*")) {
                    methods.add(b);
                }
            }
            readmeHooks.put(iface, methods);
        }
        if (readmeHooks.isEmpty()) {
            problems.add("【钩子】钩子表里一个接口都没抠到");
        }
        for (Map.Entry<String, Set<String>> e : f.hookCallbacks.entrySet()) {
            Set<String> readmeCallbacks = readmeHooks.get(e.getKey());
            if (readmeCallbacks == null) {
                problems.add("【钩子】README 的钩子表里没有 " + e.getKey() + " 这一行");
                continue;
            }
            if (!readmeCallbacks.equals(e.getValue())) {
                Set<String> missing = new TreeSet<>(e.getValue());
                missing.removeAll(readmeCallbacks);
                Set<String> extra = new TreeSet<>(readmeCallbacks);
                extra.removeAll(e.getValue());
                problems.add("【钩子】" + e.getKey() + " 的扩展点回调：README 写 " + readmeCallbacks
                        + "（" + readmeCallbacks.size() + " 个），源码有 " + e.getValue()
                        + "（" + e.getValue().size() + " 个）；README 少写 " + missing + "，多写 " + extra);
            }
        }

        // ---- 配置项：键集合相等 + 每个键的默认值相等 ----
        String configSection = section(readme, "8. 配置");
        Map<String, String> readmeConfig = new LinkedHashMap<>();
        Matcher cm = Pattern.compile("^[ \\t]*(z\\.wf\\.[A-Za-z0-9-]+)[ \\t]*=[ \\t]*(\\S+)",
                Pattern.MULTILINE).matcher(configSection);
        while (cm.find()) {
            readmeConfig.put(cm.group(1), cm.group(2));
        }
        if (readmeConfig.isEmpty()) {
            problems.add("【配置】配置一节里没抠出任何 z.wf.* 配置项");
        }
        Set<String> readmeKeys = new TreeSet<>(readmeConfig.keySet());
        Set<String> sourceKeys = new TreeSet<>(f.configDefaults.keySet());
        if (!readmeKeys.equals(sourceKeys)) {
            Set<String> missing = new TreeSet<>(sourceKeys);
            missing.removeAll(readmeKeys);
            Set<String> extra = new TreeSet<>(readmeKeys);
            extra.removeAll(sourceKeys);
            problems.add("【配置】README 列了 " + readmeKeys + "（" + readmeKeys.size() + " 项），"
                    + "WfProperties 有 " + sourceKeys + "（" + sourceKeys.size() + " 项）；"
                    + "README 少写 " + missing + "，多写 " + extra);
        }
        for (Map.Entry<String, String> e : f.configDefaults.entrySet()) {
            String got = readmeConfig.get(e.getKey());
            if (got == null) {
                continue;
            }
            if (!got.equals(e.getValue())) {
                problems.add("【配置默认值】" + e.getKey() + "：README 写 " + got
                        + "，WfProperties 的默认值是 " + e.getValue());
            }
        }

        // ---- 测试类清单：点名 + 逐类 @Test 数 ----
        String testSection = section(readme, "9. 测试");
        Map<String, Integer> readmeTestCounts = new LinkedHashMap<>();
        for (List<String> row : tableRows(testSection)) {
            if (row.size() < 2) {
                continue;
            }
            String cls = plain(row.get(0));
            if (!cls.matches("[A-Za-z][A-Za-z0-9]*Test")) {
                continue;
            }
            String num = plain(row.get(1));
            if (!num.matches("[0-9]+")) {
                problems.add("【测试类清单】" + cls + " 的数量列不是数字：" + num);
                continue;
            }
            readmeTestCounts.put(cls, Integer.parseInt(num));
        }
        if (readmeTestCounts.isEmpty()) {
            problems.add("【测试类清单】测试一节的表里一个测试类都没抠到");
        }
        for (Map.Entry<String, Integer> e : readmeTestCounts.entrySet()) {
            Integer actual = f.testClassCounts.get(e.getKey());
            if (actual == null) {
                problems.add("【测试类清单】README 点名的测试类 " + e.getKey() + " 在盘上找不到源码");
                continue;
            }
            // **只钉「这个测试类存在」，不钉它的 @Test 个数** ——
            // 逐类用例数是**自指计数**：守卫自己往 core 里加一个测试类，
            // 别人加一条用例也会让它变。钉它 = 每次加用例都要改 README，
            // 改到最后就没人信这个数了。⇒ 数量列在 README 里是**带日期的快照**，
            // 这里只校验「数量列是个数字」+「点名的类在盘上真的存在」。
        }

        // ---- z-util 钉版：README 写的版本与坐标数必须与两个 pom 对得上 ----
        problems.addAll(verifyZUtilPin(readme, f));

        return problems;
    }

    /**
     * 核对 z-util 钉版这条主张。
     *
     * <p><b>为什么单列一条</b>：z-util 是全组织共用件，本仓的版本 skew 曾经让 26 条用例红
     * （z-util 1.0.x 的枚举序列化老 bug：枚举被当 POJO 输出成 {@code {"name":null}}，
     * 回读直接抛「Enum name not found in JSON」）。
     * 而 README 里那句「已钉 N 个 z-util 坐标，全部解析到 X」此前**没有任何尺子**在看 ——
     * 它可以既过期又自相矛盾，且不触发任何一条断言。</p>
     *
     * <p><b>逐处全判</b>：同一个说法在 README 里出现两次（根仓一次、admin 一次），
     * 只判首处会漏 —— 改第二处就能溜过去。</p>
     */
    private static List<String> verifyZUtilPin(String readme, Facts f) {
        List<String> problems = new ArrayList<>();

        // ① 两个 pom 的版本属性必须自洽（源码侧不变量，不经 README）：
        //    协议层与 parser 同版本；admin 与根仓同版本。
        if (!f.rootZUtilVersion.equals(f.rootZUtilWfVersion)) {
            problems.add("【z-util 钉版】根 pom 的 z-util.version（" + f.rootZUtilVersion
                    + "）与 z-util-wf.version（" + f.rootZUtilWfVersion
                    + "）不一致 —— kernel 是协议层，必须与 parser 同版本");
        }
        if (!f.rootZUtilVersion.equals(f.adminZUtilVersion)) {
            problems.add("【z-util 钉版】根 pom 的 z-util.version（" + f.rootZUtilVersion
                    + "）与 z-wf-admin 的 z-util.version（" + f.adminZUtilVersion
                    + "）不一致 —— admin 是 own-parent，父链钉不住，只能自己钉");
        }

        // ② README 的每一处版本声明都必须等于钉版值。
        Matcher vm = Pattern.compile("`z-util\\.version` 显式覆盖为 `([0-9]+\\.[0-9]+\\.[0-9]+)`")
                .matcher(readme);
        int seen = 0;
        while (vm.find()) {
            seen++;
            if (!vm.group(1).equals(f.rootZUtilVersion)) {
                problems.add("【z-util 钉版】README 写「z-util.version 显式覆盖为 " + vm.group(1)
                        + "」，根 pom 钉的是 " + f.rootZUtilVersion);
            }
        }
        if (seen == 0) {
            problems.add("【z-util 钉版】README 里找不到「z-util.version 显式覆盖为 <版本>」这一处版本声明");
        }

        // ③ 「全部解析到 X」必须等于钉版值。
        Matcher rm = Pattern.compile("全部解析到\\s*`?([0-9]+\\.[0-9]+\\.[0-9]+)`?").matcher(readme);
        int seenResolved = 0;
        while (rm.find()) {
            seenResolved++;
            if (!rm.group(1).equals(f.rootZUtilVersion)) {
                problems.add("【z-util 钉版】README 写「全部解析到 " + rm.group(1)
                        + "」，钉版值是 " + f.rootZUtilVersion);
            }
        }
        if (seenResolved == 0) {
            problems.add("【z-util 钉版】README 里找不到「全部解析到 <版本>」这一处解析结果声明");
        }

        // ④ 两处坐标数：根仓与 admin 各一个，必须分别等于各自 pom 里实际钉住的坐标数。
        problems.addAll(verifyPinnedCount(readme, "已钉 ([0-9]+) 个 z-util 坐标",
                "本仓", f.rootPinnedZUtil.size()));
        problems.addAll(verifyPinnedCount(readme, "钉住 ([0-9]+) 个 z-util 坐标",
                "z-wf-admin", f.adminPinnedZUtil.size()));

        return problems;
    }

    /** 核对一处「已钉 / 钉住 N 个 z-util 坐标」的声明，N 必须等于 pom 里实际钉住的坐标数。 */
    private static List<String> verifyPinnedCount(String readme, String regex, String label, int actual) {
        List<String> problems = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(readme);
        int seen = 0;
        while (m.find()) {
            seen++;
            int said = Integer.parseInt(m.group(1));
            if (said != actual) {
                problems.add("【z-util 钉版】README 写 " + label + " 钉了 " + said
                        + " 个 z-util 坐标，pom 里实际钉住 " + actual + " 个");
            }
        }
        if (seen == 0) {
            problems.add("【z-util 钉版】README 里找不到「" + label + " 钉住 N 个 z-util 坐标」这一处坐标数声明");
        }
        return problems;
    }

    /** 核对一处「短语 + 版本号」的提及，版本必须等于根 POM 的 revision。 */
    private static void verifyVersion(String readme, String anchor, String label,
                                      Facts f, List<String> problems) {
        Matcher m = Pattern.compile(Pattern.quote(anchor) + "\\s*([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(readme);
        if (!m.find()) {
            problems.add(label + " README 里找不到「" + anchor.trim() + "<版本号>」这一处版本提及");
            return;
        }
        if (!m.group(1).equals(f.revision)) {
            problems.add(label + " README 写 " + m.group(1) + "，根 POM <revision> 是 " + f.revision);
        }
    }

    /**
     * 取 pom <b>顶层</b> {@code <properties>} 里某个属性的字面值。
     *
     * <p>只看文档根的直接子节点，不钻 profile —— profile 里若有同名 properties 会串味。</p>
     */
    private static String pomProperty(String pomText, String prop) {
        for (Node n : childElements(parseXml(pomText).getDocumentElement())) {
            if (!"properties".equals(n.getNodeName())) {
                continue;
            }
            for (Node p : childElements(n)) {
                if (prop.equals(p.getNodeName())) {
                    return p.getTextContent().trim();
                }
            }
        }
        return "";
    }

    /**
     * 取 pom 顶层 {@code dependencyManagement} 里<b>被显式钉住</b>的 z-util 坐标（集合）。
     *
     * <p>判据是 {@code <version>} 写成属性引用（{@code ${...}}）——
     * 写死字面量的不算「钉住」，那是另一回事（字面量钉在哪个坐标上，仍会被别人的 BOM 改写）。
     * 集合而不是个数：个数对但钉错了坐标，同样是错。</p>
     */
    private static Set<String> pinnedZUtilCoordinates(String pomText) {
        Set<String> out = new TreeSet<>();
        for (Node dm : childElements(parseXml(pomText).getDocumentElement())) {
            if (!"dependencyManagement".equals(dm.getNodeName())) {
                continue;
            }
            for (Node deps : childElements(dm)) {
                if (!"dependencies".equals(deps.getNodeName())) {
                    continue;
                }
                for (Node d : childElements(deps)) {
                    if (!"dependency".equals(d.getNodeName())) {
                        continue;
                    }
                    String artifact = "";
                    String version = "";
                    for (Node c : childElements(d)) {
                        if ("artifactId".equals(c.getNodeName())) {
                            artifact = c.getTextContent().trim();
                        } else if ("version".equals(c.getNodeName())) {
                            version = c.getTextContent().trim();
                        }
                    }
                    if (artifact.startsWith("z-util") && version.startsWith("${")) {
                        out.add(artifact);
                    }
                }
            }
        }
        return out;
    }

    /** 某个元素下的全部直接子元素（跳过文本节点与注释）。 */
    private static List<Node> childElements(Node parent) {
        List<Node> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add(n);
            }
        }
        return out;
    }

    // ==================================================================
    // 用例
    // ==================================================================

    /**
     * 核对入口：不一致就抛 AssertionError（JUnit 5 的 fail 抛的是 AssertionError 子类）。
     *
     * <p>牙口用例直接用这个入口，才能验到「尺子真的会抛」而不只是「尺子算出了差异」。</p>
     */
    private static void assertConsistent(String readme, Facts f) {
        List<String> problems = verify(readme, f);
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("z-wf README 与源码不一致，共 " + problems.size() + " 处：");
            for (String p : problems) {
                sb.append("\n  - ").append(p);
            }
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("用例 A：README 的每条定量主张都能从源码重算出来")
    void readmeClaimsMatchSource() {
        assertConsistent(readme(), facts());
    }

    @Test
    @DisplayName("用例 C：真实 README 不得被判红")
    void healthyReadmeIsNotAccused() {
        List<String> problems = verify(readme(), facts());
        assertTrue(problems.isEmpty(), "真实 README 被判红：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("牙口：版本号改错必红，且报错指向版本那条主张")
    void mutatedVersionMustFail() {
        String readme = readme();
        String mutated = readme.replace("破坏性大版本 `2.0.0`", "破坏性大版本 `9.9.9`");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到版本声明那一处");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains("9.9.9"), "报错没指出被改坏的版本号：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【版本"), "报错没指向版本这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("牙口：模块清单改错必红，且报错指向模块那条主张")
    void mutatedModuleMustFail() {
        String readme = readme();
        String mutated = readme.replace("| `z-wf-admin` | 独立可启动应用 |",
                "| `z-wf-admine` | 独立可启动应用 |");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 模块表里没找到 z-wf-admin 那一行");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains("z-wf-admine"), "报错没指出被改坏的模块名：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【模块】"), "报错没指向模块这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("牙口：节点类型种数改错必红，且报错指向种数那条主张")
    void mutatedNodeTypeCountMustFail() {
        String readme = readme();
        Matcher m = Pattern.compile("共\\s*([0-9]+)\\s*种").matcher(section(readme, "3.2 节点类型"));
        assertTrue(m.find(), "README 节点类型一节里找不到「共 N 种」");
        int bumped = Integer.parseInt(m.group(1)) + 1;
        String mutated = readme.replace(m.group(0), "共 " + bumped + " 种");
        assertFalse(mutated.equals(readme), "合成变异没生效");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains("共 " + bumped + " 种"), "报错没指出被改坏的种数：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【节点类型】"), "报错没指向节点类型这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("牙口：索引数改错必红，且报错指向索引那条主张")
    void mutatedIndexCountMustFail() {
        String readme = readme();
        Matcher m = Pattern.compile("([0-9]+)\\s*个索引").matcher(section(readme, "5. 持久化"));
        assertTrue(m.find(), "README 持久化一节里找不到「N 个索引」");
        int bumped = Integer.parseInt(m.group(1)) + 1;
        String mutated = readme.replace(m.group(0), bumped + " 个索引");
        assertFalse(mutated.equals(readme), "合成变异没生效");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains(bumped + " 个索引"), "报错没指出被改坏的索引数：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【持久化·索引】"), "报错没指向索引这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("牙口：配置默认值改错必红，且报错指向配置那条主张")
    void mutatedConfigDefaultMustFail() {
        String readme = readme();
        Matcher m = Pattern.compile("z\\.wf\\.fail-open=([a-z]+)").matcher(readme);
        assertTrue(m.find(), "README 里没找到 z.wf.fail-open 这一项");
        String flipped = "false".equals(m.group(1)) ? "true" : "false";
        String mutated = readme.replace(m.group(0), "z.wf.fail-open=" + flipped);
        assertFalse(mutated.equals(readme), "合成变异没生效");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains("z.wf.fail-open"), "报错没指出被改坏的默认值：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【配置默认值】"), "报错没指向配置默认值这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("牙口：z-util 钉版版本改错必红，且报错指向钉版那条主张")
    void mutatedZUtilVersionMustFail() {
        String readme = readme();
        // README 里这句话出现两次（根仓一次、admin 一次），replace 全部替换 ——
        // 逐处全判要的就是这个效果：改一处就够红。
        String mutated = readme.replace("`z-util.version` 显式覆盖为 `1.2.6`",
                "`z-util.version` 显式覆盖为 `9.9.9`");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到 z-util 版本声明那一处");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains("9.9.9"), "报错没指出被改坏的版本号：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【z-util 钉版】"), "报错没指向 z-util 钉版这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("牙口：z-util 坐标数改错必红，且报错指向钉版那条主张")
    void mutatedZUtilCountMustFail() {
        String readme = readme();
        String mutated = readme.replace("已钉 12 个 z-util 坐标", "已钉 99 个 z-util 坐标");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到坐标数声明那一处");
        AssertionError err = assertThrows(AssertionError.class, () -> assertConsistent(mutated, facts()));
        assertTrue(err.getMessage().contains("99"), "报错没指出被改坏的坐标数：\n" + err.getMessage());
        assertTrue(err.getMessage().contains("【z-util 钉版】"), "报错没指向 z-util 钉版这条主张：\n" + err.getMessage());
    }

    @Test
    @DisplayName("守卫生效确认：仓根定位、事实采集与 README 抓取都真的工作")
    void guardItselfWorks() {
        Path root = repoRoot();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), "仓根定位不对：" + root);
        assertTrue(Files.isRegularFile(root.resolve("README.md")), "仓根定位不对：" + root);
        Facts f = facts();
        assertNotNull(f, "facts 应能采到");
        assertFalse(f.revision.isEmpty(), "revision 没采到");
        assertFalse(f.modules.isEmpty(), "模块清单没采到");
        assertFalse(f.nodeTypes.isEmpty(), "节点类型没采到");
        assertTrue(f.tableCount > 0, "持久化表数应为正");
        assertTrue(f.indexCount > 0, "索引数应为正");
        assertTrue(f.persistenceMethodCount > 0, "SPI 方法数应为正");
        assertTrue(f.configDefaults.size() >= 1, "配置默认值没采到");
        assertTrue(f.testClassCounts.size() >= 1, "测试类清单没采到");
        assertFalse(f.rootZUtilVersion.isEmpty(), "根 pom 的 z-util.version 没采到");
        assertFalse(f.adminZUtilVersion.isEmpty(), "z-wf-admin 的 z-util.version 没采到");
        assertFalse(f.rootPinnedZUtil.isEmpty(), "根 pom 的 z-util 钉版坐标没采到");
        assertFalse(f.adminPinnedZUtil.isEmpty(), "z-wf-admin 的 z-util 钉版坐标没采到");
        assertTrue(readme().contains("z-wf"), "读到的 README 不含仓名，仓根定位可能错了");
        // 模块清单里不该有重复（重复会让集合比较失去意义）
        assertEquals(f.modules.size(), new TreeSet<>(f.modules).size(), "模块清单不应有重复");
    }

    private static String readme() {
        return readText(repoRoot().resolve("README.md"));
    }
}
