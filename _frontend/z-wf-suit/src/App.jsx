import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout, ErrorBoundary } from '@yuku123/z-frontend-common'
import { menuItems, routes, HomePage, LoginPage } from '@yuku123/z-wf-component/pages'
import { Result, Button } from 'antd'

function NotFound() {
    return (
        <Result
            status="404"
            title="页面不存在"
            subTitle="路由表里没有这条路径。"
            extra={<Button type="primary" onClick={() => { window.location.href = '/z-wf/home' }}>去首页</Button>}
        />
    )
}

// lead 008 §16：CTC/SSO 接入前的 mock 登录。
// mock 账号不写死在仓里（001 凭证规范）：构建期注入 VITE_MOCK_USER / VITE_MOCK_PASSWORD；
// 组件是 library 模式构建，模块级 import.meta.env 会被内联成空串 ⇒ 账号由 suit 取值、经 prop 传入。
const MOCK_AUTH = import.meta.env.VITE_MOCK_USER && import.meta.env.VITE_MOCK_PASSWORD
    ? {
        username: import.meta.env.VITE_MOCK_USER,
        password: import.meta.env.VITE_MOCK_PASSWORD,
        user: { name: import.meta.env.VITE_MOCK_USER, role: '管理员' },
    }
    : null

function LoginRoute() {
    return <LoginPage mockAuth={MOCK_AUTH} />
}

function ProtectedShell() {
    const [user, setUser] = useState(null)
    const [ready, setReady] = useState(false)

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            window.location.replace('/z-wf/login')
            return
        }
        const raw = localStorage.getItem('userInfo')
        if (raw) { try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) } }
        setReady(true)
    }, [])

    if (!ready) return null

    return (
        <AppLayout
            menuItems={menuItems}
            appTitle="z-wf 流程中心"
            appVersion="0.1.0"
            appUser={user}
            appIcon={{ icon: <img src="/icon.png" alt="z-wf 流程中心" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />, color: '#3b82f6', label: 'z-wf 流程中心' }}
        />
    )
}

/** lead 008 §16 suit 一次整合：登录路由 + 鉴权壳 + URL 即状态（§11/§14）。 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
                    <Route path="/z-wf/login" element={<LoginRoute />} />
                    <Route element={<ProtectedShell />}>
                        <Route path="/" element={<Navigate to={menuItems[0].key} replace />} />
                        {routes.map((r) => (
                            <Route key={r.path} path={r.path} element={<r.Component />} />
                        ))}
                        <Route path="*" element={<NotFound />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </ErrorBoundary>
    )
}