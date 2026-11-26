import React, {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Badge, Button, Card, Descriptions, Divider, Empty, Spin, Steps, Timeline, Typography,} from 'antd';
import {
    CheckCircleOutlined,
    ClockCircleOutlined,
    CloseCircleOutlined,
    ExclamationCircleOutlined,
    PlayCircleOutlined,
    UserOutlined,
} from '@ant-design/icons';
import {approvalApi, designerApi} from '../../wf/services/api';
import {EmptyState, ErrorState, PageHeader} from '@yuku123/z-frontend-common/ui';
import {ApprovalHistory, ProcessInstance} from '../../wf/types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title, Text} = Typography;
// @ts-ignore
const {Step} = Steps;

/** 轨迹端点给的是毫秒数（ProcessOperationController#getTrail durationInMillis），转人话。 */
function formatMillis(ms: number): string {
    if (!ms || ms <= 0) return '0分钟';
    const minutes = Math.floor(ms / 60000);
    if (minutes < 1) return '不足1分钟';
    const hours = Math.floor(minutes / 60);
    if (hours > 0) return `${hours}小时${minutes % 60}分钟`;
    return `${minutes}分钟`;
}

const ProcessDetail: React.FC = () => {
    const {processInstanceId} = useParams<{ processInstanceId: string }>();
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [process, setProcess] = useState<ProcessInstance | null>(null);
    const [history, setHistory] = useState<ApprovalHistory[]>([]);
    const [activeStep, setActiveStep] = useState(0);

    const fetchProcessDetail = async () => {
        if (!processInstanceId) return;
        try {
            setLoading(true);
            const [processData, historyData] = await Promise.all([
                approvalApi.getProcessDetail(processInstanceId),
                designerApi.getApprovalHistory(processInstanceId),
            ]);
            setProcess(processData);
            setHistory(historyData);

            // 计算当前步骤
            const completedSteps = historyData.filter(h => h.action === 'complete').length;
            setActiveStep(completedSteps);
        } catch (error) {
            console.error('获取流程详情失败:', error);
            setError(error as Error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchProcessDetail();
    }, [processInstanceId]);

    const getStatusTag = (status: string) => {
        switch (status) {
            case 'running':
                return <Badge status="processing" text="运行中"/>;
            case 'completed':
                return <Badge status="success" text="已完成"/>;
            case 'suspended':
                return <Badge status="warning" text="已暂停"/>;
            case 'terminated':
                return <Badge status="error" text="已终止"/>;
            default:
                return <Badge status="default" text="未知"/>;
        }
    };

    /** 轨迹项语义：引擎 trail 只有「有无 endTime」，用 activityType 区分开始/网关/任务节点。 */
    const describeTrail = (item: ApprovalHistory) => {
        const type = String((item as any).activityType || '').toLowerCase();
        if (type.includes('startevent')) return '发起流程';
        if (type.includes('endevent')) return item.action === 'complete' ? '流程结束（已批准）' : '流程结束';
        if (type.includes('gateway')) return '网关判断';
        return getActionText(item.action);
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
            case 'terminate':
                return <ExclamationCircleOutlined className={styles.terminateIcon}/>;
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
                return '转交';
            case 'delegate':
                return '委托';
            case 'terminate':
                return '终止流程';
            case 'processing':
                return '进行中';
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

    if (!process) {
        return (
            <div className={styles.emptyContainer}>
                <Empty description="流程实例不存在或已被删除"/>
                <Button type="primary" onClick={() => navigate(-1)} style={{marginTop: 16}}>
                    返回
                </Button>
            </div>
        );
    }

    return (
        <div className={styles.container}>
            <PageHeader title="流程详情" back={() => navigate('/workflow/my-processes')}/>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.headerActions}>
                        {getStatusTag(process.status)}
                    </div>
                </div>

                <Divider/>

                <Descriptions title="基本信息" bordered column={2} className={styles.descriptions}>
                    <Descriptions.Item label="流程名称">
                        {/* 2026-11-12: ProcessDetailVO 字段是 processName/processInstanceId/initiator */}
                        {(process as any).processName || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item label="流程实例ID">{(process as any).processInstanceId || (process as any).id}</Descriptions.Item>
                    <Descriptions.Item label="状态">{getStatusTag(process.status)}</Descriptions.Item>
                    <Descriptions.Item label="发起人">{String((process as any).initiator || (process as any).startUserName || '系统')}</Descriptions.Item>
                    <Descriptions.Item label="发起时间">
                        {process.startTime ? dayjs(process.startTime).format('YYYY-MM-DD HH:mm:ss') : '-'}
                    </Descriptions.Item>
                    <Descriptions.Item label="当前节点">
                        {String((process as any).currentTaskName || '-')}
                    </Descriptions.Item>
                    {process.endTime && (
                        <>
                            <Descriptions.Item label="结束时间">
                                {dayjs(process.endTime).format('YYYY-MM-DD HH:mm:ss')}
                            </Descriptions.Item>
                            {/* ProcessDetailVO.duration 已是后端 formatDuration 的字符串（如 "9分钟"） */}
                            <Descriptions.Item label="总耗时">
                                {String((process as any).duration || '-')}
                            </Descriptions.Item>
                        </>
                    )}
                </Descriptions>

                <Divider/>

                <div className={styles.timelineSection}>
                    <Title level={5}>审批历史</Title>
                    {error ? (
                        // @ts-ignore
                        <ErrorState error={error} onRetry={fetchProcessDetail} type="inline"/>
                    ) : history.length > 0 ? (
                        <Timeline mode="left" className={styles.timeline}>
                            {history.map((item, index) => (
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
                                    <div className={styles.timelineItem}>
                                        <div className={styles.timelineHeader}>
                      <span className={styles.timelineAction}>
                        {describeTrail(item)}
                      </span>
                                            <span className={styles.timelineTask}>{item.taskName}</span>
                                        </div>
                                        <div className={styles.timelineContent}>
                      <span className={styles.timelineUser}>
                        <UserOutlined/> {String((item as any).operator || item.assignee || '-')}
                      </span>
                                            <span className={styles.timelineTime}>
                        <ClockCircleOutlined/>{' '}
                                                {item.time ? dayjs(String(item.time)).format('YYYY-MM-DD HH:mm:ss') : ''}
                      </span>
                                            {item.duration != null && (
                                                <span className={styles.timelineDuration}>
                          耗时: {formatMillis(Number(item.duration))}
                        </span>
                                            )}
                                        </div>
                                        {item.comment && (
                                            <div className={styles.timelineComment}>
                                                审批意见: {item.comment}
                                            </div>
                                        )}
                                    </div>
                                </Timeline.Item>
                            ))}
                        </Timeline>
                    ) : (
                        <EmptyState title="暂无审批历史" description="当前没有审批历史记录"/>
                    )}
                </div>
            </Card>
        </div>
    );
};

export default ProcessDetail;
