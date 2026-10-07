package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `capability-gap.md` 的<b>内部一致性</b>检查。
 *
 * <p>第 32 轮查出：`ExternalTaskService` 在同一份文档里
 * **§2 与 §3 写「✅ 已实现」（还带 7 个 REST 端点）**，
 * 而 **§1.6 与 §5 写「⛔ 有意排除」**。
 * 两句话相隔几十行、看起来各自成立，只有**把两者放在一起比对**才会暴露。
 *
 * <p>为什么这条比其它文档检查更值得做：这张表是本项目**唯一的对外承诺清单**，
 * 它说"有意排除"读者就会直接跳过，而实际上我们实现了。
 * 少报能力比多报能力更伤 —— 多报会被用户当场发现，少报则**没人会来试**。
 *
 * <p>⇒ 判别式：**同一份清单里，一个条目的状态必须在全篇唯一**。
 * 只要它出现两次以上，就一定会漂。
 */
class WfCapabilityDocConsistencyTest {

    private static final String DOC = "../docs/capability-gap.md";

    /** 服务名 → 在文档里认得的写法。 */
    private static final String[][] SERVICES = {
            {"ExternalTaskService", "ExternalTaskService"},
            {"DecisionService", "DecisionService"},
            {"IdentityService", "IdentityService"},
            {"FormService", "FormService"},
            {"AuthorizationService", "AuthorizationService"},
            {"FilterService", "FilterService"},
            {"CaseService", "CaseService"},
    };

    @Test
    @DisplayName("一个服务不能既被标成「已实现」又被列进「有意排除」")
    void noServiceIsBothImplementedAndExcluded() throws IOException {
        String doc = read();
        List<String> clashes = new ArrayList<>();
        for (String[] row : SERVICES) {
            String name = row[0];
            boolean claimedImplemented = mentions(name, doc, "✅");
            // **排除侧要同时看两处**：§1.6 那张表用 ⛔ 标记，
            // 而 §5 那张表的行首**根本没有 ⛔**（它整张表就是排除清单）。
            // 只查 ⛔ 的话，§5 里那条永远查不到 ——
            // 于是这条判据在我第一次写的时候恒成立（反向验证当场抓到）。
            boolean claimedExcluded = mentions(name, doc, "⛔")
                    || inExclusionSection(doc, name);
            if (claimedImplemented && claimedExcluded) {
                clashes.add(name);
            }
        }
        assertTrue(clashes.isEmpty(),
                "以下服务在 §1/§2/§3 里被标成 ✅ 已实现，同时又被 §5 列进「有意排除」："
                        + String.join("、", clashes)
                        + "。同一张对外承诺表里两种状态并存，读者只会看到先读到的那句 ——"
                        + "而「有意排除」会让人**直接跳过**一个我们其实做了的功能。"
                        + "处置：要么从 §5 移除并写清为什么改，要么把实现删掉。");
    }

    @Test
    @DisplayName("「有意排除」清单里的每一条，在实现侧确实没有同名类")
    void excludedServicesHaveNoImplementation() throws IOException {
        // 这条是上一条的**另一半**：光保证「不同时出现两种状态」不够，
        // 还得保证「标成排除了的那个真的没实现」——
        // 否则把一个类改个名就绕过去了，而读者看到的仍是一张假清单
        String doc = read();
        for (String[] row : SERVICES) {
            String name = row[0];
            if (!inExclusionSection(doc, name)) {
                continue;
            }
            // 名字是**包含**关系而不是相等：本仓的服务类都带 Wf 前缀
            // （WfExternalTaskService 而不是 ExternalTaskService），
            // 按相等找会一个都找不到，这条判据于是恒成立
            boolean hasClass = classExistsSomewhere(name);
            assertTrue(!hasClass,
                    name + " 仍列在「有意排除」里，但实现侧有同名服务类 —— "
                            + "要么它其实已实现（那要移出排除清单），要么类名对不上（那要改名或改清单）");
        }
    }

    @Test
    @DisplayName("表格后面那句「这N条」必须数得清，且与表格实际行数一致")
    void proseCountMatchesTableRowCount() throws IOException {
        // 这条不是凭空加的：修 §5 时**自己**制造了一次同类缺陷 ——
        // 删掉 ExternalTaskService 那一行后，紧接着的「这**五条**的共同点」
        // 就成了悬空计数，而我是在提交前 `git diff` 里才看见的。
        // ⇒ 表格行数与正文计数是**同一事实的两次记录**，改一处必须改另一处；
        // 而"删表格行"是最容易让人只盯着表格本身看的那类改动。
        String doc = read();
        String[] lines = doc.split("\n");
        List<String> bad = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (!lines[i].startsWith("|")) {
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < lines.length && lines[end + 1].startsWith("|")) {
                end++;
            }
            // 减去表头与分隔行，剩下的才是数据行
            int dataRows = (end - i + 1) - 2;
            // 表格结束后 4 行内（留出空行）找「这N条」这类计数句
            for (int j = end + 1; j <= Math.min(end + 4, lines.length - 1); j++) {
                Matcher m = COUNT_SENTENCE.matcher(lines[j]);
                if (m.find()) {
                    int said = chineseNumeral(m.group(1));
                    if (said >= 0 && said != dataRows) {
                        bad.add(lines[j].trim() + " 〔紧跟的表格只有 " + dataRows + " 行〕");
                    }
                }
            }
            i = end + 1;
        }
        assertTrue(bad.isEmpty(),
                "正文里的计数与紧跟其后的表格行数对不上：\n  - " + String.join("\n  - ", bad)
                        + "\n删表格行时最容易只改表格、不改这句话 —— 而它恰好是读者读完整张表后"
                        + "用来概括的那句，数错了等于亲手推翻自己列的清单。");
    }

    // ==================== 工具 ====================

    /** 「这N条」「这N项」这类把数量写进句子的表述。 */
    private static final Pattern COUNT_SENTENCE = Pattern.compile("这([一二三四五六七八九十]+)[条项个]");

    private static final String CN_DIGITS = "零一二三四五六七八九";

    /**
     * 中文数字转阿拉伯数字。够用即可 —— 这里的输入全部来自我们自己写的句子，
     * 超过十（那不叫"几条"，那叫"几十条"）返回 -1 表示不解析，交给上层跳过。
     */
    private int chineseNumeral(String s) {
        if (s.equals("十")) {
            return 10;
        }
        int value = 0;
        for (int k = 0; k < s.length(); k++) {
            int d = CN_DIGITS.indexOf(s.charAt(k));
            if (d < 0) {
                return -1;
            }
            value = value * 10 + d;
        }
        return value;
    }

    private boolean classExistsSomewhere(String name) {
        return findJavaFile(new File("src/main/java"), name);
    }

    private boolean findJavaFile(File dir, String serviceName) {
        if (!dir.isDirectory()) {
            return false;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (findJavaFile(child, serviceName)) {
                    return true;
                }
            } else if (child.getName().endsWith(".java")
                    && child.getName().contains(serviceName)) {
                return true;
            }
        }
        return false;
    }

    /** 该服务名是否出现在标着 ✅ 或 ⛔ 的行里。 */
    private boolean mentions(String name, String doc, String mark) {
        for (String line : doc.split("\n")) {
            if (!line.startsWith("|")) {
                continue;
            }
            if (!line.contains(name) || !line.contains(mark)) {
                continue;
            }
            // 「有意排除，见 §5」这一句本身就是排除声明，
            // 而 §5 那张表的行首没有 ✅，所以只要带 ⛔ 就算排除侧
            return true;
        }
        return false;
    }

    /** 该服务名是否出现在 §5「有意排除的部分」这一节里。 */
    private boolean inExclusionSection(String doc, String name) {
        int start = doc.indexOf("## 5. 有意排除的部分");
        int end = doc.indexOf("## 6.", start);
        if (start < 0) {
            return false;
        }
        if (end < 0) {
            end = doc.length();
        }
        Set<String> rows = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\|[^|]*\\|", Pattern.MULTILINE).matcher(
                doc.substring(start, end));
        while (m.find()) {
            rows.add(m.group(0));
        }
        for (String row : rows) {
            if (row.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private String read() throws IOException {
        File file = new File(DOC);
        assertTrue(file.isFile(), "找不到 " + DOC + " —— 判据读不到文档就是恒红，"
                + "而那与文档写错了长得一样");
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }
}