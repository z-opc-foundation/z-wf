package com.zifang.z.wf.starter.rpc;

import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * RPC 服务契约 —— 经 z-rpc 对外暴露流程能力。
 *
 * <p>与 z-camuda 的 {@code CamudaProcessRpcService} 同名同义（发起 / 待办 / 完成），
 * 便于从 Camuda 迁移的调用方只改包名。
 *
 * <p><b>本接口不引 z-rpc 注解</b>：契约层保持零依赖，
 * {@code WfProcessRpcServiceImpl} 才在 classpath 有 z-rpc 时加 {@code @ZRpcService}。
 * 这样不引 z-rpc 的业务方也能正常编译引用本接口（它只是普通 Java 接口）。
 *
 * @author zifang
 */
public interface WfProcessRpcService {

    /**
     * 发起流程。
     *
     * @return 流程实例 ID
     */
    String startProcess(String definitionKey, String businessKey, String userId,
                        Map<String, Object> variables);

    /**
     * 查询某人的待办任务。
     */
    List<WfTask> getTodoList(String userId, int pageNum, int pageSize);

    /**
     * 办理任务。
     */
    WfProcessInstance completeTask(String taskId, String userId, String comment,
                                   Map<String, Object> variables);
}
