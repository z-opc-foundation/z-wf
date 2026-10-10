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
import {approvalApi, designerApi} from '@/wf/services/api';
import {EmptyState, ErrorState, PageHeader} from '@/common/components/ui';
import {ApprovalHistory, ProcessInstance} from '@/wf/types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title, Text} = Typography;
// @ts-ignore
const {Step} = Steps;

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
            <PageHeader title="流程详情" back={() => navigate('/wf/pages/ProcessList')}/>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.headerActions}>
                        {getStatusTag(process.status)}
                    </div>
                </div>

                <Divider/>

                <Descriptions title="基本信息" bordered column={2} className={styles.descriptions}>
                    <Descriptions.Item label="流程名称">{process.processDefinitionName}</Descriptions.Item>
                    <Descriptions.Item label="流程实例ID">{process.id}</Descriptions.Item>
                    <Descriptions.Item label="业务标识">{process.businessKey || '-'}</Descriptions.Item>
                    <Descriptions.Item label="状态">{getStatusTag(process.status)}</Descriptions.Item>
                    // @ts-ignore
                    <Descriptions.Item label="发起人">{String(process.startUserName || '系统')}</Descriptions.Item>
                    <Descriptions.Item label="发起时间">
                        {dayjs(process.startTime).format('YYYY-MM-DD HH:mm:ss')}
                    </Descriptions.Item>
                    {process.endTime && (
                        <>
                            <Descriptions.Item label="结束时间">
                                {dayjs(process.endTime).format('YYYY-MM-DD HH:mm:ss')}
                            </Descriptions.Item>
                            <Descriptions.Item label="总耗时">
                                {(() => {
                                    const duration = process.duration || 0;
                                    // @ts-ignore
                                    const days = Math.floor(duration / 86400000);
                                    // @ts-ignore
                                    const hours = Math.floor((duration % 86400000) / 3600000);
                                    // @ts-ignore
                                    const minutes = Math.floor((duration % 3600000) / 60000);
                                    if (days > 0) return `${days}天${hours}小时`;
                                    if (hours > 0) return `${hours}小时${minutes}分钟`;
                                    return `${minutes}分钟`;
                                })()}
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
                        {getActionText(item.action)}
                      </span>
                                            <span className={styles.timelineTask}>{item.taskName}</span>
                                        </div>
                                        <div className={styles.timelineContent}>
                      <span className={styles.timelineUser}>
                        // @ts-ignore
                        <UserOutlined/> {String(item.assigneeName || item.assignee)}
                      </span>
                                            <span className={styles.timelineTime}>
                        <ClockCircleOutlined/>{' '}
                                                // @ts-ignore
                                                {dayjs(String(item.createTime)).format('YYYY-MM-DD HH:mm:ss')}
                      </span>
                                            {item.duration && (
                                                <span className={styles.timelineDuration}>
                          耗时: {Math.floor(item.duration / 1000 / 60)}分钟
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
