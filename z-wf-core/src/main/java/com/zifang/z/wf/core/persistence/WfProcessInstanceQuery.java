package com.zifang.z.wf.core.persistence;

import java.util.Date;

import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 流程实例查询条件。
 *
 * <p>所有字段可为空 = 不过滤。分页在内存里做（先过滤后切片），
 * 生产实现应把过滤下推到 SQL，但<b>语义必须一致</b>：先过滤、再按 startTime 倒序、最后分页。
 *
 * @author zifang
 */
public class WfProcessInstanceQuery {

    private String definitionKey;

    private String businessKey;

    private String startUserId;

    private String category;

    private WfProcessStatus status;

    /**
     * 定义版本。必须与 {@link #definitionKey} 一起用 ——
     * 只给版本不给 key 会命中所有流程的同版本号，返回一批毫无关系的实例。
     */
    private Integer definitionVersion;

    /**
     * 只要终态实例（COMPLETED / EXTERNALLY_TERMINATED / INTERNALLY_TERMINATED）。
     *
     * <p>单独给一个开关而不是让调用方把三种状态各查一遍再合并：终态有三种，
     * 用 {@link #status} 单值表达"已结束"是表达不出来的，而"查三种再合并"
     * 还得处理分页 —— 三次查询各自分页，合并出来的第 2 页并不是全量第 2 页。
     */
    private boolean finishedOnly;

    /** 只要仍在流转的实例（ACTIVE / SUSPENDED）。 */
    private boolean unfinishedOnly;

    /** 起始时间（含）。 */
    private Date startTimeFrom;

    /** 截止时间（含）。 */
    private Date startTimeTo;

    /** 结果过滤。 */
    private String result;

    // ==================== 变量条件（第 43 轮） ====================
    //
    // **这一组刻意不进存储层**，过滤与分页都由
    // com.zifang.z.wf.core.service.WfProcessQueryService 在 Java 里做。
    // 理由与 token 查询那边完全相同（见 WfPersistence#appendExecutionFilters
    // 与 WfExecutionQueryService#scan）：
    // 变量存在 JSON 文本列里，各库对 JSON 函数的支持与语义都不一样，
    // 而「先 LIMIT 再过滤」会把命中的那几条切在页外
    // —— 表现是「明明有 3 条符合，翻到第一页却只有 1 条」。

    /** 变量名。{@code null} = 不按变量筛。 */
    private String variableName;

    /** 比较方式；{@code null} 表示<b>只问这个变量在不在</b>。 */
    private VariableComparison comparison;

    /** {@link VariableComparison#EQUALS} 时的比较值（按字符串形态比）。 */
    private String equalsValue;

    /** {@code GREATER_THAN} 的下界 / {@code BETWEEN} 的下界（闭）。 */
    private Double numberFrom;

    /** {@code LESS_THAN} 的上界 / {@code BETWEEN} 的上界（闭）。 */
    private Double numberTo;

    /**
     * 变量条件的比较方式。
     *
     * <p><b>刻意做成互斥的单值</b>，而不是"多个布尔开关"：
     * 一旦允许 {@code greaterThan} 与 {@code equals} 同时为真，
     * 就得定义「同时给时是取交集还是取并集」，而那种问题在
     * 「查金额 5000 以上」这种最常见的用法上永远不会有人去想 ——
     * 结果是查出来一批错的单子，而调用方以为条件没生效。
     * 单值模型让"一个查询只有一个变量条件"成为<b>构造上的事实</b>，
     * 也让每个 setter 都必须把上一个状态清干净。
     */
    public enum VariableComparison {
        /**
         * 按 {@code String.valueOf} 的<b>字符串形态</b>比。
         *
         * <p>⚠️ <b>它本身就是「宽松」的</b>：数字 {@code 100} 与字符串
         * {@code "100"} 在这个分支里<b>判相等</b>。
         * 这不是疏忽，是审批场景要的行为（金额在表单里填进来就是字符串），
         * 详见 {@link WfProcessInstanceQuery#setVariableValueEquals}。
         * 与 {@code GREATER_THAN} 那几支<b>不是同一把尺子</b> ——
         * 那几支要真的把值当数字算。
         */
        EQUALS,
        GREATER_THAN,
        LESS_THAN,
        /** 闭区间 [from, to]。 */
        BETWEEN
    }

    private int pageNum = 1;

    private int pageSize = 20;

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfProcessInstanceQuery setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
        return this;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public Integer getDefinitionVersion() {
        return definitionVersion;
    }

    public WfProcessInstanceQuery setDefinitionVersion(Integer definitionVersion) {
        this.definitionVersion = definitionVersion;
        return this;
    }

    public WfProcessInstanceQuery setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
        return this;
    }

    public String getStartUserId() {
        return startUserId;
    }

    public WfProcessInstanceQuery setStartUserId(String startUserId) {
        this.startUserId = startUserId;
        return this;
    }

    public String getCategory() {
        return category;
    }

    public WfProcessInstanceQuery setCategory(String category) {
        this.category = category;
        return this;
    }

    public WfProcessStatus getStatus() {
        return status;
    }

    public WfProcessInstanceQuery setStatus(WfProcessStatus status) {
        this.status = status;
        return this;
    }

    public boolean isFinishedOnly() {
        return finishedOnly;
    }

    public WfProcessInstanceQuery setFinishedOnly(boolean finishedOnly) {
        this.finishedOnly = finishedOnly;
        return this;
    }

    public boolean isUnfinishedOnly() {
        return unfinishedOnly;
    }

    public WfProcessInstanceQuery setUnfinishedOnly(boolean unfinishedOnly) {
        this.unfinishedOnly = unfinishedOnly;
        return this;
    }

    /**
     * 条件自相矛盾时直接拒绝，不让它变成一个空结果。
     *
     * <p>与 {@link WfTaskQuery#assertConsistent()} 同一套理由：矛盾条件叠上去
     * 只会得到一个零结果的列表，调用方却分不清是"确实没有"还是"条件打架"。
     * 这里挡三种打架：
     * <ul>
     *   <li>finishedOnly 与 unfinishedOnly 同时为真</li>
     *   <li>finishedOnly 配上非终态的 status（如 ACTIVE）</li>
     *   <li>startTimeFrom 晚于 startTimeTo</li>
     * </ul>
     * 至于 finishedOnly 配终态 status（两者一致）不在此列 ——
     * 那是冗余条件，不是矛盾条件，照常过滤即可。
     */
    public void assertConsistent() {
        if (finishedOnly && unfinishedOnly) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：finishedOnly 与 unfinishedOnly 不能同时为真。");
        }
        if (finishedOnly && status != null && !status.isTerminal()) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：finishedOnly 只收终态实例（"
                            + "COMPLETED / EXTERNALLY_TERMINATED / INTERNALLY_TERMINATED），"
                            + "而 status 却是 " + status + "。");
        }
        if (unfinishedOnly && status != null && status.isTerminal()) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：unfinishedOnly 只收在途实例（ACTIVE / SUSPENDED），"
                            + "而 status 却是终态 " + status + "。");
        }
        if (definitionVersion != null && (definitionKey == null || definitionKey.trim().isEmpty())) {
            // 静默返回"所有流程的 v3"比不返回更难发现 —— 调用方以为自己问的是某一个流程
            throw new IllegalArgumentException(
                    "流程实例查询条件不完整：definitionVersion 必须与 definitionKey 一起给，"
                            + "只给版本号会命中所有流程的同版本，返回一批无关实例。");
        }
        if (startTimeFrom != null && startTimeTo != null
                && startTimeFrom.after(startTimeTo)) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：startTimeFrom(" + startTimeFrom
                            + ") 晚于 startTimeTo(" + startTimeTo + ")。");
        }
    }

    public Date getStartTimeFrom() {
        return startTimeFrom;
    }

    public WfProcessInstanceQuery setStartTimeFrom(Date startTimeFrom) {
        this.startTimeFrom = startTimeFrom;
        return this;
    }

    public Date getStartTimeTo() {
        return startTimeTo;
    }

    public WfProcessInstanceQuery setStartTimeTo(Date startTimeTo) {
        this.startTimeTo = startTimeTo;
        return this;
    }

    public String getResult() {
        return result;
    }

    public WfProcessInstanceQuery setResult(String result) {
        this.result = result;
        return this;
    }

    /**
     * 变量存在性：只看有没有这个名字，<b>不看它的值</b>。
     *
     * <p>审批系统里最常用的一个查询形态就是它 ——
     * <b>"哪些单子还没填金额"</b>。它也是 {@code equals} 表达不了的那一半：
     * {@code =null} 会被当成"值为 null"而命中一批单子，
     * 而"根本没这个键"根本不是一种值。
     */
    public WfProcessInstanceQuery setVariableName(String name) {
        resetVariableCondition();
        this.variableName = trimToNull(name);
        return this;
    }

    /**
     * 按变量的值查（{@link VariableComparison#EQUALS}：按<b>字符串形态</b>比）。
     *
     * <h3>⚠️ 这里就是「类型宽松」，而且是有意的</h3>
     * 比较落在 {@code String.valueOf(value).equals(参数值)} 上，
     * 于是数字 {@code 100} 与字符串 {@code "100"} <b>判相等</b>。
     * 本方法的旧注释曾写着「不做类型宽松，{@code 1} 与 {@code "1"} 判不等」——
     * 那是<b>照抄 token 侧的错误注释</b>，与它自己的实现正好相反（第 43 轮改正）。
     *
     * <p><b>宽松才是这里要的行为</b>：审批的金额在表单里填进来是字符串
     * {@code "5000"}，另一条后端路径又可能存成数字 {@code 5000}。
     * 按类型严格比的话，{@code =5000} 查不到字符串那一批，
     * 而且<b>不报任何错</b> —— 调用方只会看到"就是没有"。
     * 「查不出来」比「多查出几条形态相同的」危险得多。
     *
     * <p>但要清楚它的边界：
     * <ul>
     *   <li><b>只在这一支宽松</b>。{@code >} / {@code <} / 区间那几支要真的
     *       {@code parseDouble}，走的是另一把尺子。</li>
     *   <li>于是 {@code ="null"} 会命中"值确实是 null"的那一单 ——
     *       那是形态相同，不是 bug。<b>要问「有没有这个键」就用
     *       {@link #setVariableName}</b>，它才是那个不涉及值的问题。</li>
     *   <li>「压根没设过这个键」任何一支都不命中：比较前先 {@code containsKey}。</li>
     * </ul>
     *
     * <p>与 token 侧 {@code WfExecutionQuery#setVariableValueEquals} 同一把尺子 ——
     * 流程级与 token 级对同一个变量的回答不一致，比任一边选错更难查。
     */
    public WfProcessInstanceQuery setVariableValueEquals(String name, String value) {
        resetVariableCondition();
        this.variableName = trimToNull(name);
        this.comparison = VariableComparison.EQUALS;
        this.equalsValue = value;
        return this;
    }

    /** 按变量的值查：{@code name > number}（严格大于）。 */
    public WfProcessInstanceQuery setVariableValueGreaterThan(String name, double number) {
        resetVariableCondition();
        this.variableName = trimToNull(name);
        this.comparison = VariableComparison.GREATER_THAN;
        this.numberFrom = Double.valueOf(number);
        return this;
    }

    /** 按变量的值查：{@code name < number}（严格小于）。 */
    public WfProcessInstanceQuery setVariableValueLessThan(String name, double number) {
        resetVariableCondition();
        this.variableName = trimToNull(name);
        this.comparison = VariableComparison.LESS_THAN;
        this.numberTo = Double.valueOf(number);
        return this;
    }

    /**
     * 按变量的值查：{@code from <= name <= to}（<b>两端都是闭的</b>）。
     *
     * <p>闭区间而不是开端点，因为审批场景里的阈值（"金额 1000 到 5000 需要总监"）
     * 说的就是包含边界；而开端点要在参数名和文档里各写一遍，
     * 写漏一次就变成"边界上的单子少审了一层"。
     *
     * <p>调用方用它天然覆盖"大于 1000 且小于 5000"之外的那条真正需求 ——
     * 区间查在审批里比单边查常见得多，而两个单边 setter 叠不出区间
     * （互斥模型下第二个会把第一个清掉）。
     */
    public WfProcessInstanceQuery setVariableValueBetween(String name, double from, double to) {
        if (from > to) {
            // 静默交换两端会让"查 5000 到 1000"变成查 1000 到 5000，
            // 而调用方以为结果为空 —— 拿到一份看着合理的错数据
            throw new IllegalArgumentException(
                    "变量区间查询的下界(" + from + ")大于上界(" + to + ")，无法构造区间。");
        }
        resetVariableCondition();
        this.variableName = trimToNull(name);
        this.comparison = VariableComparison.BETWEEN;
        this.numberFrom = Double.valueOf(from);
        this.numberTo = Double.valueOf(to);
        return this;
    }

    public String getVariableName() {
        return variableName;
    }

    public VariableComparison getComparison() {
        return comparison;
    }

    public String getEqualsValue() {
        return equalsValue;
    }

    public Double getNumberFrom() {
        return numberFrom;
    }

    public Double getNumberTo() {
        return numberTo;
    }

    /**
     * 是否用到了变量条件。
     *
     * <p>这是<b>快慢两条路的唯一分界</b>：没有它就原路把查询交给存储层
     * （分页下推 SQL，行为与本特性之前完全一致）；
     * 有它才走 Java 过滤 + Java 分页。
     * ⇒ 分界必须是这个方法，<b>而不是"尽量下推"</b>：
     * 一旦某天有人顺手把某个条件也写进 {@code appendProcessFilters}，
     * 内存实现与 JDBC 就会给出两个不同的答案，而症状只在真库上出现。
     */
    public boolean hasVariableCondition() {
        return variableName != null;
    }

    /** 清掉整个变量条件（不只是名字）。 */
    private void resetVariableCondition() {
        this.variableName = null;
        this.comparison = null;
        this.equalsValue = null;
        this.numberFrom = null;
        this.numberTo = null;
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 清掉全部变量条件（慢路径扫描前用）。
     *
     * <p>扫描时要把它们摘掉，再拿「读到的行数」与 {@code MAX_SCAN} 比 ——
     * 留着的话读到的就已经是命中行，溢出判断就失去了意义。
     *
     * <p><b>单独给一个方法而不是让调用方逐个 setter 置空</b>：
     * 少清一个字段的症状是「扫描时按变量条件过滤了一遍，后面再过滤一遍」，
     * 结果碰巧还对，于是没人发现它已经不对了。
     */
    public WfProcessInstanceQuery clearVariableConditions() {
        resetVariableCondition();
        return this;
    }

    /**
     * 副本。慢路径要在这上面改分页参数。
     *
     * <p>与 {@code WfExecutionQuery#copy} 同一个理由：
     * 就地改会让调用方之后再拿去翻页时读到被改过的参数
     * （{@code fetch} 被写成 {@code pageSize=2001} 之后，原对象再也翻不了页）。
     */
    public WfProcessInstanceQuery copy() {
        WfProcessInstanceQuery other = new WfProcessInstanceQuery();
        other.definitionKey = definitionKey;
        other.businessKey = businessKey;
        other.startUserId = startUserId;
        other.category = category;
        other.status = status;
        other.definitionVersion = definitionVersion;
        other.finishedOnly = finishedOnly;
        other.unfinishedOnly = unfinishedOnly;
        other.startTimeFrom = startTimeFrom;
        other.startTimeTo = startTimeTo;
        other.result = result;
        other.variableName = variableName;
        other.comparison = comparison;
        other.equalsValue = equalsValue;
        other.numberFrom = numberFrom;
        other.numberTo = numberTo;
        other.pageNum = pageNum;
        other.pageSize = pageSize;
        return other;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfProcessInstanceQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfProcessInstanceQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 20 : pageSize;
        return this;
    }

    /**
     * 分页偏移。
     */
    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }
}
