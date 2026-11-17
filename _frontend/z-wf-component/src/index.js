export * from './wf/services/api.js'
// WorkflowApp 是 z-wf 自己的兜底路由组件（pages-manifest 里 `/z-wf/:rest*` 用它），
// z-team 的 SystemShell 也按具名导出引它挂 `/workflow/*`。
export {default as WorkflowApp} from './wf/pages/WorkflowApp.jsx'
// 组件文档页（lead 008 §7）：主壳按统一名字取，各仓一致
export {default as ComponentDocPage} from './pages/ComponentDoc'
