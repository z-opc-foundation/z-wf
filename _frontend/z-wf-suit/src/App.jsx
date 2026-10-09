import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-wf-component/pages'
import '@yuku123/z-wf-component/style.css'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/workflow/dashboard" replace/>}/>
            {/* 页面内部 navigate 写死 /workflow/* 绝对路径，故用无 path 的 layout 路由承载 */}
            <Route element={
                <AppLayout menuItems={menuItems} appTitle="z-wf 流程中心" appShort="WF"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
