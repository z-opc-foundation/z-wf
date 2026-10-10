/**
 * z-camuda 审批中心 / 流程设计 的 API 客户端。
 *
 * 2026-10-04 瘦身：原先本文件在 approvalApi / designerApi 之外，还挂着
 * 32 个 makeApi 产物（mcp / mock / naming / ops / oss / script / skill / agent /
 * flow / trace / llm / product / scene / ctcAc 系列 / metaApp / job / webide /
 * privateConfig / agentTeam / workspace …）与 config/mist 两份复制品同构。
 * 全仓逐条解析 import 后确认
 * **只有 approvalApi 与 designerApi 被引用**（导入方 8 个，全在 wf/pages/ 下），
 * 其余 50 个导出零引用。
 *
 * 同名的 configApi / mcpApi / ctcAcOrgApi 等在 common、config/mist、agent/api、
 * ctc/components 各有自己的定义，删这里不影响它们。
 *
 * 判定口径：按 bundler 规则解析每个 import 的实际路径（@/ → src/，
 * ./ 与 ../ 逐级上溯，扩展名顺序沿用 Vite 默认），而不是按名字 grep——
 * 早期用「名字是否在全仓出现过」判断过一轮，结果 50 个导出全部误判为「活」，
 * 因为这三个文件的导出名与其他模块大量重名。
 */
import {request} from '@/common'

export const approvalApi = {
    // 审批中心实际位于 /api/approval-center/* (Camunda BPM 适配层), 与 makeApi('approval') 不一致
    // 注意: 走 @/common/utils/request (统一拦截 + Result 解包), 业务层拿到的 r 已经是内层数据,
    //       不需要再 .then(r => r.data).
    getDashboardStats: (userId) => request.get('/approval-center/dashboard', {params: {userId}}),
    getTodoList: (userId, pageNum = 1, pageSize = 10) =>
        request.get('/approval-center/tasks/todo', {params: {userId, pageNum, pageSize}}),
    getDoneList: (userId, pageNum = 1, pageSize = 10) =>
        request.get('/approval-center/tasks/done', {params: {userId, pageNum, pageSize}}),
    getTaskDetail: (taskId) => request.get('/approval-center/tasks/get', {params: {taskId}}),
    completeTask: (taskId, payload) => request.post('/approval-center/tasks/complete', payload, {params: {taskId}}),
    getMyProcesses: (userId, pageNum = 1, pageSize = 10) =>
        request.get('/approval-center/my-processes', {params: {userId, pageNum, pageSize}}),
    getProcessDetail: (processInstanceId) =>
        request.get('/approval-center/processes/get', {params: {processInstanceId}}),
    startProcess: (payload) => request.post('/approval-center/processes/start', payload),
    getProcessDefinitions: () => request.get('/approval-center/processes/definitions'),
    // 2026-10-04 修：后端签名是 @RequestParam String processInstanceId，
    // 原来这里发的是 ?id=，后端收不到必填参数直接 400。
    deleteProcessInstance: (processInstanceId) =>
        request.delete('/approval-center/processes', {params: {processInstanceId}}),
}

/**
 * 流程设计 API — 对应 z-camuda 的 ProcessModelController
 * (@RequestMapping("/api/approval-center/processes/model"))。
 *
 * 2026-10-04 新增。此前这里是 `makeApi('designer')`，那是个只有
 * list/page/get/create/update/delete 六个方法的通用垫壳，而页面调的是
 * getFlowGraph / saveFlowGraph / deployProcess / getApprovalHistory ——
 * 四个都不存在，点进 /workflow/designer/:id 必 TypeError。
 * z-camuda 侧同时补齐了对应的流程图端点（本次一起加的）。
 */
export const designerApi = {
    /** 取设计稿，返回 LogicFlow 的 {nodes, edges} */
    getFlowGraph: (processKey) =>
        request.get('/approval-center/processes/model/graph', {params: {processKey}})
            .then(r => (r && typeof r === 'object' && 'graph' in r ? r.graph : r)),

    /** 保存设计稿 */
    saveFlowGraph: (processKey, graph) =>
        request.post('/approval-center/processes/model/graph', {
            processKey,
            graph: typeof graph === 'string' ? graph : JSON.stringify(graph),
        }),

    /** 发布：设计稿转 BPMN 并部署为新的流程定义版本 */
    deployProcess: (processKey, name) =>
        request.post('/approval-center/processes/model/deploy', {processKey, name}),

    /**
     * 审批轨迹。走 /api/wf/process/trail（ProcessOperationController#005）。
     *
     * 注意参数是**流程实例 id**，不是任务 id —— TaskDetail 早先把 taskId 传进来，
     * trail 查不到任何东西，页面表现为「审批历史恒空」。调用方要传 processInstanceId。
     *
     * 返回值做了归一化：引擎给的是
     *   {activityId, activityName, activityType, assignee, startTime, endTime, duration}
     * 而页面渲染用的是 {id, taskName, action, time, ...}。放在这里转一次，
     * 页面就不用各写一遍字段映射。
     *
     * action 的口径：引擎的 trail 不直接记录「通过/驳回」，
     * 只能按 activity 有没有 endTime 区分「已完成 / 进行中」。
     * reject / terminate 两种要区分，得读流程变量里的 approvalResult，
     * trail 端点没返回 —— 这里不编。
     */
    getApprovalHistory: (processInstanceId) =>
        request.get('/wf/process/trail', {params: {processInstanceId}})
            .then(r => (Array.isArray(r) ? r : []).map(item => ({
                id: item.activityId,
                taskName: item.activityName,
                action: item.endTime ? 'complete' : 'processing',
                time: item.startTime,
                endTime: item.endTime,
                duration: item.duration,
                operator: item.assignee,
                activityType: item.activityType,
            }))),
}
