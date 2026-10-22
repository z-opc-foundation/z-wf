export * from './wf/services/api.js'
// WorkflowApp 是 z-wf 自己的兜底路由组件（pages-manifest 里 `/z-wf/:rest*` 用它），
// z-team 的 SystemShell 也按具名导出引它挂 `/workflow/*`。
// 组件一直在 pages/WorkflowApp.jsx，只是没从包根导出，导致消费方构建期 "not exported by"。
export {default as WorkflowApp} from './pages/WorkflowApp.jsx'
// 组件文档页（lead 008 §7）：主壳按统一名字取，各仓一致
export {default as ComponentDocPage} from './pages/ComponentDoc'
