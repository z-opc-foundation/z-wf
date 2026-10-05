package com.zifang.z.wf.starter.rpc;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * RPC 服务实现。
 *
 * <p>刻意<b>不</b>打 {@code @ZRpcService} 注解：z-rpc 是 optional 依赖，
 * 类上留注解会让 starter 在 classpath 没有 z-rpc 时直接编译/加载失败。
 * 真正要暴露 RPC 的形态（{@code z-wf-admin}）在 z-rpc 确实存在时再由
 * {@link WfRpcAutoConfiguration} 装配一个带注解的子类。
 *
 * @author zifang
 */
@Service
public class WfProcessRpcServiceImpl implements WfProcessRpcService {

    @Resource
    private WfRuntimeService runtimeService;

    @Resource
    private WfTaskService taskService;

    @Override
    public String startProcess(String definitionKey, String businessKey, String userId,
                               Map<String, Object> variables) {
        return runtimeService.startProcessInstance(
                definitionKey, null, businessKey, userId, null, variables);
    }

    @Override
    public List<WfTask> getTodoList(String userId, int pageNum, int pageSize) {
        return taskService.getTodoList(userId, null, pageNum, pageSize);
    }

    @Override
    public WfProcessInstance completeTask(String taskId, String userId, String comment,
                                          Map<String, Object> variables) {
        return runtimeService.completeTask(taskId, userId, comment, variables);
    }
}
