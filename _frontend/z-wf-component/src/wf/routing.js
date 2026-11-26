import {useLocation} from 'react-router-dom'

let configuredBase

// 与 configureWf({apiBase}) 共用宿主配置入口；wfBase 是页面挂载根，不是 API 根。
export function configureWfBase(base) {
    configuredBase = base == null ? undefined : `/${String(base).split('/').filter(Boolean).join('/')}`.replace(/\/$/, '')
}

export function resolveWfBase(pathname) {
    if (configuredBase !== undefined) return configuredBase
    // 从当前可达页面推导挂载根，同时支持 manifest 的独立路由和宿主的任意前缀。
    return pathname.replace(/\/+$/, '').replace(/\/(?:dashboard|todo|done|my-processes|processes|(?:task|process|designer)\/[^/]+)$/, '')
}

export function useWfBase() {
    return resolveWfBase(useLocation().pathname)
}
