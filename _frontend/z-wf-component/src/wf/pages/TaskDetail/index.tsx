import React, {useEffect, useRef, useState} from 'react';
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
    PlayCircleOutlined,
    UserOutlined,
} from '@ant-design/icons';
import {approvalApi, designerApi} from '../../services/api';
import {useWfBase} from '../../routing';
import {EmptyState, ErrorState, PageHeader} from '@yuku123/z-frontend-common/ui';
import {ApprovalHistory, Task} from '../../types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title, Text} = Typography;
const {TextArea} = Input;
const TaskDetail: React.FC = () => {
    const {taskId} = useParams<{ taskId: string }>();
    const navigate = useNavigate();
    const wfBase = useWfBase();
    const [form] = Form.useForm();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [task, setTask] = useState<Task | null>(null);
    const [history, setHistory] = useState<ApprovalHistory[]>([]);
    const [action, setAction] = useState<'approve' | 'reject'>('approve');
    const [transferOpen, setTransferOpen] = useState(false);
    const [targetUserId, setTargetUserId] = useState('');
    const [transferComment, setTransferComment] = useState('');
    const [transferring, setTransferring] = useState(false);
    const transferPending = useRef(false);

    const fetchTaskDetail = async () => {
        if (!taskId) return;
        try {
            setLoading(true);
            setError(null);
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
        if (!taskId || transferPending.current || submitting) return;
        try {
            setSubmitting(true);
            await approvalApi.completeTask(taskId, {
                action: values.action,
                comment: values.comment,
                formData: values.formData,
            });
            message.success('审批操作成功');
            navigate(`${wfBase}/todo`);
        } catch (error) {
            console.error('审批操作失败:', error);
            message.error('审批操作失败');
        } finally {
            setSubmitting(false);
        }
    };

    const handleTransfer = async () => {
        // ref 同步锁住同一轮渲染内的重复点击，不能只依赖异步 state。
        if (!taskId || transferPending.current || submitting) return;
        const target = targetUserId.trim();
        if (!target) {
            message.warning('请输入目标用户 ID');
            return;
        }
        let userId = '';
        try {
            const info = JSON.parse(localStorage.getItem('userInfo') || '{}');
            userId = String(info?.userId || info?.userCode || info?.id || info?.userName || '').trim();
        } catch { /* 无有效身份时拒绝提交，不假冒任务处理人。 */ }
        if (!userId) {
            message.error('无法识别当前登录用户，请重新登录后转交');
            return;
        }

        transferPending.current = true;
        setTransferring(true);
        try {
            await approvalApi.transferTask(taskId, {
                userId,
                targetUserId: target,
                comment: transferComment.trim(),
            });
            message.success('转交成功');
            setTransferOpen(false);
            setTargetUserId('');
            setTransferComment('');
            await fetchTaskDetail();
        } catch (error) {
            // 请求失败保留弹窗及输入，以便修正或重试。
            message.error(error instanceof Error ? error.message : '转交失败');
        } finally {
            transferPending.current = false;
            setTransferring(false);
        }
    };

    const getActionIcon = (action: string) => {
        switch (action) {
            case 'start':
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
                <Button type="primary" onClick={() => navigate(`${wfBase}/todo`)} style={{marginTop: 16}}>
                    返回
                </Button>
            </div>
        );
    }

    return (
        <div className={styles.container}>
            <PageHeader title="任务详情" back={() => { if (!transferPending.current) navigate(`${wfBase}/todo`); }}/>
            <Modal
                title="转交任务"
                open={transferOpen}
                onOk={handleTransfer}
                onCancel={() => { if (!transferPending.current) setTransferOpen(false); }}
                okText="确认转交"
                cancelText="取消"
                confirmLoading={transferring}
                okButtonProps={{disabled: !targetUserId.trim() || transferring}}
                cancelButtonProps={{disabled: transferring}}
                closable={!transferring}
                keyboard={!transferring}
                maskClosable={!transferring}
            >
                <Form layout="vertical">
                    <Form.Item label="目标用户 ID" required extra="请输入系统中真实的用户 ID，不是用户姓名。">
                        <Input
                            aria-label="目标用户 ID"
                            placeholder="请输入目标用户 ID"
                            value={targetUserId}
                            onChange={(event) => setTargetUserId(event.target.value)}
                            disabled={transferring}
                        />
                    </Form.Item>
                    <Form.Item label="转交说明">
                        <TextArea
                            aria-label="转交说明"
                            value={transferComment}
                            onChange={(event) => setTransferComment(event.target.value)}
                            disabled={transferring}
                            maxLength={500}
                        />
                    </Form.Item>
                </Form>
            </Modal>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.headerActions}>
                        {task.status === 'pending' && <Badge status="processing" text="待处理"/>}
                        {task.status === 'claimed' && <Badge status="warning" text="处理中"/>}
                        {task.status === 'completed' && <Badge status="success" text="已完成"/>}
                    </div>
                </div>

                <Divider/>

                <div className={styles.content}>
                    <div className={styles.leftPanel}>
                        <Descriptions title="任务信息" bordered column={1} className={styles.descriptions}>
                            <Descriptions.Item label="任务名称">{task.name}</Descriptions.Item>
                            <Descriptions.Item label="任务ID">{task.taskId}</Descriptions.Item>
                            <Descriptions.Item label="所属流程">{String(task.processDefinitionName)}</Descriptions.Item>
                            <Descriptions.Item label="流程实例ID">{task.processInstanceId}</Descriptions.Item>
                            <Descriptions.Item
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
                                {task.claimTime ? dayjs(String(task.claimTime)).format('YYYY-MM-DD HH:mm:ss') : '-'}
                            </Descriptions.Item>
                            {task.dueTime && (
                                <Descriptions.Item label="到期时间">
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
                                                    disabled={transferring}
                                                    icon={<CheckCircleOutlined/>}
                                                >
                                                    提交
                                                </Button>
                                                <Button onClick={() => setTransferOpen(true)} disabled={submitting || transferring} icon={<UserOutlined/>}>
                                                    转交
                                                </Button>
                                                <Button disabled={transferring} onClick={() => navigate(`${wfBase}/todo`)}>取消</Button>
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
                                                        {dayjs(String(item.createTime)).format('MM-DD HH:mm')}
                          </span>
                                                </div>
                                                <div className={styles.historyContent}>
                          <span className={styles.historyUser}>
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
