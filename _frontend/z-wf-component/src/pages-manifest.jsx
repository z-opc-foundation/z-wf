import {
    DashboardOutlined,
    CheckSquareOutlined,
    ClockCircleOutlined,
    ProfileOutlined,
    ApartmentOutlined,
    FileDoneOutlined,
    UnorderedListOutlined,
} from '@ant-design/icons'
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
export const menuItems = [
    {key: '/workflow/dashboard', icon: <DashboardOutlined/>, label: '工作台'},
    {key: '/workflow/todo', icon: <ClockCircleOutlined/>, label: '待办任务'},
    {key: '/workflow/done', icon: <CheckSquareOutlined/>, label: '已办任务'},
    {key: '/workflow/my-processes', icon: <UnorderedListOutlined/>, label: '我的流程'},
    {key: '/workflow/processes', icon: <ProfileOutlined/>, label: '流程定义'},
    {key: '/workflow/designer', icon: <ApartmentOutlined/>, label: '流程设计'},
    {key: '/workflow/process', icon: <FileDoneOutlined/>, label: '流程详情'},
]

const routeTable = [
    {path: '/workflow/dashboard', Component: Dashboard},
    {path: '/workflow/todo', Component: TodoList},
    {path: '/workflow/done', Component: DoneList},
    {path: '/workflow/my-processes', Component: MyProcesses},
    {path: '/workflow/processes', Component: ProcessList},
    {path: '/workflow/designer/:id', Component: ProcessDesigner},
    {path: '/workflow/process/:processInstanceId', Component: ProcessDetail},
    {path: '/workflow/task/:taskId', Component: TaskDetail},
]
export {routeTable}
export {default as Dashboard} from './wf/pages/Dashboard'
export {default as TodoList} from './wf/pages/TodoList'
export {default as ProcessList} from './wf/pages/ProcessList'
export {default as ProcessDesigner} from './wf/pages/ProcessDesigner'
