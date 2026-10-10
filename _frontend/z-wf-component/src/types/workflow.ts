/**
 * Workflow type declarations (re-export from @/wf/types).
 */

export interface DashboardStats {
    todoCount: number;
    doneCount: number;
    processCount: number;
    approvalCount: number;

    [key: string]: unknown;
}

export interface Task {
    id: string;
    name: string;
    assignee?: string;
    processInstanceId?: string;
    processDefinitionId?: string;
    createTime?: string;
    dueDate?: string;
    priority?: number;
    description?: string;
    taskDefinitionKey?: string;
    formKey?: string;

    [key: string]: unknown;
}
