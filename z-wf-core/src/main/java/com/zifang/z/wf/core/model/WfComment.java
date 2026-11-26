package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 流程评论 / 加签意见。
 *
 * <p>对应 z-camuda 的 {@code ProcessOperationController#comment} 与
 * {@code ApprovalCenterController} 的审批意见展示。
 *
 * @author zifang
 */
public class WfComment implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String processInstanceId;

    /** 关联任务 id（流程级评论为 null）。 */
    private String taskId;

    private String userId;

    private String type;

    private String content;

    private Date time;

    public WfComment() {
    }

    public WfComment(String id, String processInstanceId, String userId, String type, String content) {
        this.id = id;
        this.processInstanceId = processInstanceId;
        this.userId = userId;
        this.type = type;
        this.content = content;
        this.time = new Date();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    @Override
    public String toString() {
        return "WfComment{" + processInstanceId + " " + userId + ": " + content + "}";
    }
}
