import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-wf-component/pages'
import '@yuku123/z-wf-component/style.css'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/workflow/dashboard" replace/>}/>
            {/* 页面内部 navigate 写死 /workflow/* 绝对路径，故用无 path 的 layout 路由承载 */}
            <Route element={
                <AppLayout menuItems={menuItems} appTitle="z-wf 流程中心" appShort="WF" appIcon={{icon: <img src="/icon.png" alt="WF" style={{width: "100%", height: "100%", objectFit: "cover", borderRadius: 8}}/>, color: '#3b82f6', label: 'WF'}}/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
