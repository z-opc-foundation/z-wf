import React, {useEffect, useState} from 'react';
import {Button, Card, Col, List, Row, Statistic, Tag} from 'antd';
import {
    CheckCircleOutlined,
    ClockCircleOutlined,
    ExclamationCircleOutlined,
    FileTextOutlined,
    RightOutlined,
} from '@ant-design/icons';
import {Link, useNavigate} from 'react-router-dom';
import {approvalApi} from '../../wf/services/api';
import { EmptyState, ErrorState } from '@yuku123/z-frontend-common/ui'
import {Task} from '../../types/workflow';

const styles: Record<string, any> = {
    dashboard: {padding: 24},
    statsRow: {marginBottom: 24},
    statCard: {marginBottom: 16},
    todoIcon: {color: '#1677ff'},
    doneIcon: {color: '#52c41a'},
    initiatedIcon: {color: '#722ed1'},
    statFooter: {textAlign: 'right', marginTop: 8},
    todoCard: {marginTop: 16},
    taskTitle: {display: 'flex', justifyContent: 'space-between', alignItems: 'center'},
    taskDesc: {fontSize: 12, color: '#888'},
};

interface Stats {
    todoCount: number;
    doneCount: number;
    myProcessCount: number;
}

const Dashboard: React.FC = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    const [stats, setStats] = useState<Stats>({todoCount: 0, doneCount: 0, myProcessCount: 0});
    // 后端 DashboardStatsVO 只有计数；待办卡片自己拉一页真实待办
    const [todoList, setTodoList] = useState<Task[]>([]);

    const currentUserId = (): string => {
        try {
            const info = JSON.parse(localStorage.getItem('userInfo') || '{}');
            // 流程引擎里 assignee/initiator 记的是登录名（admin），不是数字 id
            return info.userName || info.username || info.userCode || info.id || 'admin';
        } catch {
            return 'admin';
        }
    };

    const fetchDashboardData = async () => {
        try {
            setLoading(true);
            setError(null);
            const user = currentUserId();
            const [data, todoPage] = await Promise.all([
                approvalApi.getDashboardStats(user),
                approvalApi.getTodoList(user, 1, 5).catch(() => ({records: []})),
            ]);
            setStats({
                // 2026-11-12 修：后端字段是 myProcessCount，原来读 initiatedCount 恒 undefined
                todoCount: Number(data?.todoCount ?? 0),
                doneCount: Number(data?.doneCount ?? 0),
                myProcessCount: Number(data?.myProcessCount ?? (data as any)?.initiatedCount ?? 0),
            });
            setTodoList((todoPage as any)?.records || []);
        } catch (error) {
            console.error('获取仪表盘数据失败:', error);
            setError(error as Error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchDashboardData();
    }, []);

    // 获取优先级标签颜色
    const getPriorityColor = (priority: number) => {
        if (priority >= 80) return 'red';
        if (priority >= 50) return 'orange';
        return 'default';
    };

    // 获取优先级文本
    const getPriorityText = (priority: number) => {
        if (priority >= 80) return '紧急';
        if (priority >= 50) return '高';
        return '普通';
    };

    return (
        <div className={styles.dashboard}>
            {/* 统计卡片 */}
            <Row gutter={16} className={styles.statsRow}>
                <Col xs={24} sm={8}>
                    <Card loading={loading} className={styles.statCard}>
                        <Statistic
                            title="待办任务"
                            value={stats.todoCount}
                            prefix={<ClockCircleOutlined className={styles.todoIcon}/>}
                            styles={{content: {color: '#1677ff'}}}
                        />
                        <div className={styles.statFooter}>
                            <Link to="/workflow/todo">
                                去处理 <RightOutlined/>
                            </Link>
                        </div>
                    </Card>
                </Col>
                <Col xs={24} sm={8}>
                    <Card loading={loading} className={styles.statCard}>
                        <Statistic
                            title="已办任务"
                            value={stats.doneCount}
                            prefix={<CheckCircleOutlined className={styles.doneIcon}/>}
                            styles={{content: {color: '#52c41a'}}}
                        />
                        <div className={styles.statFooter}>
                            <Link to="/workflow/done">
                                查看全部 <RightOutlined/>
                            </Link>
                        </div>
                    </Card>
                </Col>
                <Col xs={24} sm={8}>
                    <Card loading={loading} className={styles.statCard}>
                        <Statistic
                            title="我发起的"
                            value={stats.myProcessCount}
                            prefix={<FileTextOutlined className={styles.initiatedIcon}/>}
                            styles={{content: {color: '#722ed1'}}}
                        />
                        <div className={styles.statFooter}>
                            <Link to="/workflow/my-processes">
                                查看全部 <RightOutlined/>
                            </Link>
                        </div>
                    </Card>
                </Col>
            </Row>

            {/* 待办任务列表 */}
            <Card
                title={
                    <span>
            <ExclamationCircleOutlined style={{color: '#faad14', marginRight: 8}}/>
            待办任务
          </span>
                }
                extra={<Link to="/workflow/todo">查看更多</Link>}
                loading={loading}
                className={styles.todoCard}
            >
                {error ? (
                    <ErrorState error={error} onRetry={fetchDashboardData} variant="inline"/>
                ) : todoList.length > 0 ? (
                    <List
                        dataSource={todoList.slice(0, 5)}
                        renderItem={(task: any) => (
                            <List.Item
                                actions={[
                                    <Button
                                        type="primary"
                                        size="small"
                                        onClick={() => navigate(`/workflow/task/${task.taskId || task.id}`)}
                                    >
                                        处理
                                    </Button>,
                                ]}
                            >
                                <List.Item.Meta
                                    title={
                                        <div className={styles.taskTitle}>
                                            <span>{task.taskName || task.name}</span>
                                            <Tag color={getPriorityColor(Number(task.priority) || 0)}>
                                                {getPriorityText(Number(task.priority) || 0)}
                                            </Tag>
                                        </div>
                                    }
                                    description={
                                        <div className={styles.taskDesc}>
                                            <span>流程: {String(task.processName || task.processDefinitionName || '-')}</span>
                                            <span>创建时间: {task.createTime || '-'}</span>
                                        </div>
                                    }
                                />
                            </List.Item>
                        )}
                    />
                ) : (
                    <EmptyState title="暂无待办任务" description="当前没有待办任务"/>
                )}
            </Card>
        </div>
    );
};

export default Dashboard;
