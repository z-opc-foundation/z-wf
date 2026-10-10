import { ApartmentOutlined, CheckSquareOutlined, ClockCircleOutlined, DashboardOutlined, FileDoneOutlined, HomeOutlined, ProfileOutlined, UnorderedListOutlined } from '@ant-design/icons'
import Dashboard from './wf/pages/Dashboard'
import TodoList from './wf/pages/TodoList'
import DoneList from './wf/pages/DoneList'
import MyProcesses from './wf/pages/MyProcesses'
import ProcessList from './wf/pages/ProcessList'
import ProcessDesigner from './wf/pages/ProcessDesigner'
import ProcessDetail from './wf/pages/ProcessDetail'
import TaskDetail from './wf/pages/TaskDetail'

/**
 * 路由表用绝对路径 /workflow/*：页面内部 navigate 与 <Link> 都写死了
 * /workflow/todo、/workflow/designer/:id 等（主壳原样），suit 必须挂 layout 路由同前缀。
 */

export {default as Dashboard} from './wf/pages/Dashboard'
export {default as TodoList} from './wf/pages/TodoList'
export {default as ProcessList} from './wf/pages/ProcessList'
export {default as ProcessDesigner} from './wf/pages/ProcessDesigner'
import HomePage from './pages/HomePage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-wf 流程中心', short: 'z-wf' }

export const menuItems = [
    { key: '/z-wf/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-wf/workflow/dashboard', label: '工作台', icon: <DashboardOutlined /> },
    { key: '/z-wf/workflow/todo', label: '待办任务', icon: <ClockCircleOutlined /> },
    { key: '/z-wf/workflow/done', label: '已办任务', icon: <CheckSquareOutlined /> },
    { key: '/z-wf/workflow/my-processes', label: '我的流程', icon: <UnorderedListOutlined /> },
    { key: '/z-wf/workflow/processes', label: '流程定义', icon: <ProfileOutlined /> },
    { key: '/z-wf/workflow/designer', label: '流程设计', icon: <ApartmentOutlined /> },
    { key: '/z-wf/workflow/process', label: '流程详情', icon: <FileDoneOutlined /> },
]

export const routes = [
    { path: '/z-wf/home', Component: HomePage },
    { path: '/z-wf/workflow/dashboard', Component: Dashboard },
    { path: '/z-wf/workflow/todo', Component: TodoList },
    { path: '/z-wf/workflow/done', Component: DoneList },
    { path: '/z-wf/workflow/my-processes', Component: MyProcesses },
    { path: '/z-wf/workflow/processes', Component: ProcessList },
    { path: '/z-wf/workflow/designer/:id', Component: ProcessDesigner },
    { path: '/z-wf/workflow/process/:processInstanceId', Component: ProcessDetail },
    { path: '/z-wf/workflow/task/:taskId', Component: TaskDetail },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
