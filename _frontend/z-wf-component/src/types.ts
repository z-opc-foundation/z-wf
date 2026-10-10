/**
 * Workflow module type declarations.
 * Stub types for the wf module — actual runtime data comes from the API.
 */

export interface PageResult<T> {
    records: T[];
    total: number;
    pageNum: number;
    pageSize: number;
}

export interface ProcessDefinition {
    id: string;
    name: string;
    description?: string;
    version?: number;
    category?: string;
    deploymentTime?: string;
    suspended?: boolean;

    [key: string]: unknown;
}

export interface ProcessInstance {
    id: string;
    processDefinitionId: string;
    processDefinitionName?: string;
    businessKey?: string;
    startTime?: string;
    endTime?: string;
    startUser?: string;
    status?: string;
    variables?: Record<string, unknown>;

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

export interface ApprovalHistory {
    id: string;
    taskId?: string;
    taskName?: string;
    assignee?: string;
    comment?: string;
    action?: string;
    startTime?: string;
    endTime?: string;
    duration?: number;

    [key: string]: unknown;
}
