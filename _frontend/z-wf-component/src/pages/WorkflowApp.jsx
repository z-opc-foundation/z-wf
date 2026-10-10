import {Navigate, Route, Routes} from 'react-router-dom'
import Dashboard from './Dashboard'
import TodoList from './TodoList'
import DoneList from './DoneList'
import MyProcesses from './MyProcesses'
import ProcessList from './ProcessList'
import ProcessDesigner from './ProcessDesigner'
import ProcessDetail from './ProcessDetail'
import TaskDetail from './TaskDetail'

export default function WorkflowIndex() {
    return (
        <Routes>
            <Route index element={<Navigate to="dashboard" replace/>}/>
            <Route path="dashboard" element={<Dashboard/>}/>
            <Route path="todo" element={<TodoList/>}/>
            <Route path="done" element={<DoneList/>}/>
            <Route path="my-processes" element={<MyProcesses/>}/>
            <Route path="processes" element={<ProcessList/>}/>
            <Route path="designer/:id" element={<ProcessDesigner/>}/>
            <Route path="process/:processInstanceId" element={<ProcessDetail/>}/>
            <Route path="task/:taskId" element={<TaskDetail/>}/>
        </Routes>
    )
}
