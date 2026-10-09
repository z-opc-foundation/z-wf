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
import {approvalApi} from '../../services/api';
import {EmptyState, ErrorState, PageHeader} from '../../ui';
import {DashboardStats, Task} from '../../types/workflow';

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

const Dashboard: React.FC = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    // @ts-ignore
    const [stats, setStats] = useState<DashboardStats>({
        todoCount: 0,
        doneCount: 0,
        initiatedCount: 0,
        todoList: [],
    });

    const fetchDashboardData = async () => {
        try {
            setLoading(true);
            // 从 localStorage.userInfo 读取当前登录用户 (兼容后端字段命名 userId/userCode)
            const info = JSON.parse(localStorage.getItem('userInfo') || '{}');
            const currentUser = info.userId || info.userCode || info.id || info.userName || '';
            const data = await approvalApi.getDashboardStats(currentUser);
            setStats({
                ...data,
                todoList: data.todoList || []
            });
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
        // @ts-ignore
        <div className={styles.dashboard}>
            <PageHeader title="流程仪表盘"/>
            {/* 统计卡片 */}
            // @ts-ignore
            <Row gutter={16} className={styles.statsRow}>
                <Col xs={24} sm={8}>
                    // @ts-ignore
                    <Card loading={loading} className={styles.statCard}>
                        <Statistic
                            title="待办任务"
                            value={stats.todoCount}
                            prefix={<ClockCircleOutlined className={styles.todoIcon}/>}
                            styles={{content: {color: '#1677ff'}}}
                        />
                        // @ts-ignore
                        <div className={styles.statFooter}>
                            <Link to="/workflow/todo">
                                去处理 <RightOutlined/>
                            </Link>
                        </div>
                    </Card>
                </Col>
                <Col xs={24} sm={8}>
                    // @ts-ignore
                    <Card loading={loading} className={styles.statCard}>
                        <Statistic
                            title="已办任务"
                            value={stats.doneCount}
                            prefix={<CheckCircleOutlined className={styles.doneIcon}/>}
                            styles={{content: {color: '#52c41a'}}}
                        />
                        // @ts-ignore
                        <div className={styles.statFooter}>
                            <Link to="/workflow/done">
                                查看全部 <RightOutlined/>
                            </Link>
                        </div>
                    </Card>
                </Col>
                <Col xs={24} sm={8}>
                    // @ts-ignore
                    <Card loading={loading} className={styles.statCard}>
                        <Statistic
                            title="我发起的"
                            // @ts-ignore
                            value={stats.initiatedCount}
                            prefix={<FileTextOutlined className={styles.initiatedIcon}/>}
                            styles={{content: {color: '#722ed1'}}}
                        />
                        // @ts-ignore
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
                // @ts-ignore
                className={styles.todoCard}
            >
                {error ? (
                    // @ts-ignore
                    <ErrorState error={error} onRetry={fetchDashboardData} type="inline"/>
                    // @ts-ignore
                ) : stats.todoList.length > 0 ? (
                    <List
                        // @ts-ignore
                        dataSource={stats.todoList.slice(0, 5)}
                        renderItem={(task: Task) => (
                            <List.Item
                                actions={[
                                    <Button
                                        type="primary"
                                        size="small"
                                        onClick={() => navigate(`/workflow/task/${task.id}`)}
                                    >
                                        处理
                                    </Button>,
                                ]}
                            >
                                <List.Item.Meta
                                    title={
                                        // @ts-ignore
                                        <div className={styles.taskTitle}>
                                            <span>{task.name}</span>
                                            <Tag color={getPriorityColor(task.priority)}>
                                                {getPriorityText(task.priority)}
                                            </Tag>
                                        </div>
                                    }
                                    description={
                                        // @ts-ignore
                                        <div className={styles.taskDesc}>
                                            <span>流程: {String(task.processDefinitionName)}</span>
                                            <span>创建时间: {task.createTime}</span>
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
