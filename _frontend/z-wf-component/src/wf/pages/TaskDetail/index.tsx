import React, {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {
    Badge,
    Button,
    Card,
    Descriptions,
    Divider,
    Empty,
    Form,
    Input,
    message,
    Modal,
    Radio,
    Select,
    Space,
    Spin,
    Tag,
    Timeline,
    Typography,
} from 'antd';
import {
    CheckCircleOutlined,
    CheckOutlined,
    ClockCircleOutlined,
    CloseCircleOutlined,
    CloseOutlined,
    ExclamationCircleOutlined,
    PlayCircleOutlined,
    UserOutlined,
} from '@ant-design/icons';
import {approvalApi, designerApi} from '../../services/api';
import {EmptyState, ErrorState, PageHeader} from '../../ui';
import {ApprovalHistory, Task} from '../../types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title, Text} = Typography;
const {TextArea} = Input;
const {confirm} = Modal;

const TaskDetail: React.FC = () => {
    const {taskId} = useParams<{ taskId: string }>();
    const navigate = useNavigate();
    const [form] = Form.useForm();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [task, setTask] = useState<Task | null>(null);
    const [history, setHistory] = useState<ApprovalHistory[]>([]);
    const [action, setAction] = useState<'approve' | 'reject'>('approve');

    const fetchTaskDetail = async () => {
        if (!taskId) return;
        try {
            setLoading(true);
            // 2026-10-04 修：轨迹端点 /api/wf/process/trail 收的是**流程实例 id**，
            // 原来直接把 taskId 传进去，引擎查不到任何 activity，页面表现为「审批历史恒空」，
            // 而且不报错。先取任务详情拿 processInstanceId 再查轨迹。
            const taskData = await approvalApi.getTaskDetail(taskId);
            setTask(taskData);
            const processInstanceId = (taskData as any)?.processInstanceId;
            if (!processInstanceId) {
                setHistory([]);
            } else {
                const historyData = await designerApi.getApprovalHistory(processInstanceId);
                setHistory(historyData);
            }
        } catch (error) {
            console.error('获取任务详情失败:', error);
            setError(error as Error);
            message.error('获取任务详情失败');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchTaskDetail();
    }, [taskId]);

    const handleSubmit = async (values: any) => {
        if (!taskId) return;
        try {
            setSubmitting(true);
            await approvalApi.completeTask(taskId, {
                action: values.action,
                comment: values.comment,
                formData: values.formData,
            });
            message.success('审批操作成功');
            navigate('/workflow/todo');
        } catch (error) {
            console.error('审批操作失败:', error);
            message.error('审批操作失败');
        } finally {
            setSubmitting(false);
        }
    };

    const handleTransfer = () => {
        confirm({
            title: '转交任务',
            icon: <ExclamationCircleOutlined/>,
            content: (
                <div style={{marginTop: 16}}>
                    <Select
                        showSearch
                        placeholder="选择转交人"
                        style={{width: '100%'}}
                        options={[]}
                    />
                </div>
            ),
            onOk: () => {
                message.success('转交成功');
            },
        });
    };

    const getActionIcon = (action: string) => {
        switch (action) {
            case 'start':
                // @ts-ignore
                return <PlayCircleOutlined className={styles.startIcon}/>;
            case 'complete':
                return <CheckCircleOutlined className={styles.completeIcon}/>;
            case 'reject':
                return <CloseCircleOutlined className={styles.rejectIcon}/>;
            case 'transfer':
                return <UserOutlined className={styles.transferIcon}/>;
            default:
                return <ClockCircleOutlined/>;
        }
    };

    const getActionText = (action: string) => {
        switch (action) {
            case 'start':
                return '发起流程';
            case 'complete':
                return '审批通过';
            case 'reject':
                return '审批驳回';
            case 'transfer':
                return '转交他人';
            case 'delegate':
                return '委托处理';
            default:
                return action;
        }
    };

    if (loading) {
        return (
            <div className={styles.loadingContainer}>
                <Spin size="large" description="加载中..."/>
            </div>
        );
    }

    if (!task) {
        return (
            <div className={styles.emptyContainer}>
                <Empty description="任务不存在或已被处理"/>
                <Button type="primary" onClick={() => navigate(-1)} style={{marginTop: 16}}>
                    返回
                </Button>
            </div>
        );
    }

    return (
        <div className={styles.container}>
            <PageHeader title="任务详情" back={() => navigate(-1)}/>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.headerActions}>
                        // @ts-ignore
                        {task.status === 'pending' && <Badge status="processing" text="待处理"/>}
                        // @ts-ignore
                        {task.status === 'claimed' && <Badge status="warning" text="处理中"/>}
                        // @ts-ignore
                        {task.status === 'completed' && <Badge status="success" text="已完成"/>}
                    </div>
                </div>

                <Divider/>

                <div className={styles.content}>
                    <div className={styles.leftPanel}>
                        <Descriptions title="任务信息" bordered column={1} className={styles.descriptions}>
                            <Descriptions.Item label="任务名称">{task.name}</Descriptions.Item>
                            <Descriptions.Item label="任务ID">{task.id}</Descriptions.Item>
                            // @ts-ignore
                            <Descriptions.Item label="所属流程">{String(task.processDefinitionName)}</Descriptions.Item>
                            <Descriptions.Item label="流程实例ID">{task.processInstanceId}</Descriptions.Item>
                            <Descriptions.Item
                                // @ts-ignore
                                label="当前处理人">{task.assigneeName || task.assignee || '-'}</Descriptions.Item>
                            <Descriptions.Item label="任务状态">
                                {task.status === 'pending' && <Tag color="blue">待处理</Tag>}
                                {task.status === 'claimed' && <Tag color="orange">处理中</Tag>}
                                {task.status === 'completed' && <Tag color="green">已完成</Tag>}
                            </Descriptions.Item>
                            <Descriptions.Item label="创建时间">
                                {dayjs(task.createTime).format('YYYY-MM-DD HH:mm:ss')}
                            </Descriptions.Item>
                            <Descriptions.Item label="认领时间">
                                // @ts-ignore
                                {task.claimTime ? dayjs(String(task.claimTime)).format('YYYY-MM-DD HH:mm:ss') : '-'}
                            </Descriptions.Item>
                            {task.dueTime && (
                                <Descriptions.Item label="到期时间">
                                    // @ts-ignore
                                    {dayjs(String(task.dueTime)).format('YYYY-MM-DD HH:mm:ss')}
                                </Descriptions.Item>
                            )}
                            <Descriptions.Item label="优先级">
                                <Tag color={task.priority >= 80 ? 'red' : task.priority >= 50 ? 'orange' : 'default'}>
                                    {task.priority >= 80 ? '紧急' : task.priority >= 50 ? '高' : '普通'}
                                </Tag>
                            </Descriptions.Item>
                        </Descriptions>

                        {task.status === 'pending' && (
                            <>
                                <Divider/>
                                <div className={styles.approvalSection}>
                                    <Title level={5}>审批操作</Title>
                                    <Form
                                        form={form}
                                        layout="vertical"
                                        onFinish={handleSubmit}
                                        className={styles.approvalForm}
                                    >
                                        <Form.Item
                                            name="action"
                                            label="审批意见"
                                            initialValue="approve"
                                        >
                                            <Radio.Group
                                                onChange={(e) => setAction(e.target.value)}
                                                buttonStyle="solid"
                                            >
                                                <Radio.Button value="approve">
                                                    <CheckOutlined/> 通过
                                                </Radio.Button>
                                                <Radio.Button value="reject">
                                                    <CloseOutlined/> 驳回
                                                </Radio.Button>
                                            </Radio.Group>
                                        </Form.Item>

                                        <Form.Item
                                            name="comment"
                                            label="审批意见"
                                            rules={[
                                                {required: action === 'reject', message: '驳回时必须填写审批意见'}
                                            ]}
                                        >
                                            <TextArea
                                                rows={4}
                                                placeholder="请输入审批意见..."
                                                showCount
                                                maxLength={500}
                                            />
                                        </Form.Item>

                                        <Form.Item className={styles.formActions}>
                                            <Space>
                                                <Button
                                                    type="primary"
                                                    htmlType="submit"
                                                    loading={submitting}
                                                    icon={<CheckCircleOutlined/>}
                                                >
                                                    提交
                                                </Button>
                                                <Button onClick={handleTransfer} icon={<UserOutlined/>}>
                                                    转交
                                                </Button>
                                                <Button onClick={() => navigate(-1)}>取消</Button>
                                            </Space>
                                        </Form.Item>
                                    </Form>
                                </div>
                            </>
                        )}
                    </div>

                    <div className={styles.rightPanel}>
                        <Card title="审批历史" className={styles.historyCard}>
                            {error ? (
                                // @ts-ignore
                                <ErrorState error={error} onRetry={fetchTaskDetail} type="inline"/>
                            ) : history.length > 0 ? (
                                <Timeline mode="left" className={styles.historyTimeline}>
                                    {history.map((item) => (
                                        <Timeline.Item
                                            key={item.id}
                                            dot={getActionIcon(item.action)}
                                            color={
                                                item.action === 'complete'
                                                    ? 'green'
                                                    : item.action === 'reject' || item.action === 'terminate'
                                                        ? 'red'
                                                        : 'blue'
                                            }
                                        >
                                            <div className={styles.historyItem}>
                                                <div className={styles.historyHeader}>
                          <span className={styles.historyAction}>
                            {getActionText(item.action)}
                          </span>
                                                    <span className={styles.historyTime}>
                            // @ts-ignore
                                                        {dayjs(String(item.createTime)).format('MM-DD HH:mm')}
                          </span>
                                                </div>
                                                <div className={styles.historyContent}>
                          <span className={styles.historyUser}>
                            // @ts-ignore
                            <UserOutlined/> {String(item.assigneeName || item.assignee)}
                          </span>
                                                </div>
                                                {item.comment && (
                                                    <div className={styles.historyComment}>{item.comment}</div>
                                                )}
                                            </div>
                                        </Timeline.Item>
                                    ))}
                                </Timeline>
                            ) : (
                                <EmptyState title="暂无审批历史" description="当前没有审批历史记录"/>
                            )}
                        </Card>
                    </div>
                </div>
            </Card>
        </div>
    );
};

export default TaskDetail;
