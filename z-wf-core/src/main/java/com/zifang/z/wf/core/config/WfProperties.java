package com.zifang.z.wf.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * z-wf 引擎配置项。
 *
 * <p>配置前缀 {@code z.wf.*}，示例：
 * <pre>
 * z.wf.enabled=true
 * z.wf.persistence=memory        # memory | jdbc
 * z.wf.deploy-on-startup=true     # 启动时自动部署 classpath 下的流程
 * z.wf.process-path=classpath*:processes/
 * z.wf.fail-open=false           # 条件求值失败时是否放行（默认 false = 卡住）
 * z.wf.auto-initialize-schema=true
 * </pre>
 *
 * @author zifang
 */
@ConfigurationProperties(prefix = "z.wf")
public class WfProperties {

    /** 是否启用引擎。 */
    private boolean enabled = true;

    /**
     * 持久化类型：{@code memory}（默认，零依赖）/ {@code jdbc}（需 DataSource）。
     */
    private String persistence = "memory";

    /** 启动时自动部署 classpath 下的流程定义。 */
    private boolean deployOnStartup = true;

    /** 流程定义扫描路径（Spring ResourcePattern 语法）。 */
    private String processPath = "classpath*:processes/*.bpmn";

    /**
     * 条件表达式求值失败时是否判为 true（放行）。
     * <p>默认 false：审批流宁可卡住等人处理，也不该因为一条写错的条件静默放行。
     */
    private boolean failOpen = false;

    /** JDBC 模式下是否自动建表。 */
    private boolean autoInitializeSchema = true;

    /** 流程实例结果为该值时视为"通过"（供 SPI / 业务方判定 approved）。 */
    private String approvedResult = "approved";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPersistence() {
        return persistence;
    }

    public void setPersistence(String persistence) {
        this.persistence = persistence;
    }

    public boolean isDeployOnStartup() {
        return deployOnStartup;
    }

    public void setDeployOnStartup(boolean deployOnStartup) {
        this.deployOnStartup = deployOnStartup;
    }

    public String getProcessPath() {
        return processPath;
    }

    public void setProcessPath(String processPath) {
        this.processPath = processPath;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public boolean isAutoInitializeSchema() {
        return autoInitializeSchema;
    }

    public void setAutoInitializeSchema(boolean autoInitializeSchema) {
        this.autoInitializeSchema = autoInitializeSchema;
    }

    public String getApprovedResult() {
        return approvedResult;
    }

    public void setApprovedResult(String approvedResult) {
        this.approvedResult = approvedResult;
    }
}
