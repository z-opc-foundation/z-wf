import React, {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Badge, Button, Card, Input, Space, Table, Tag, Tooltip, Typography,} from 'antd';
import {CheckCircleOutlined, ClockCircleOutlined, ReloadOutlined, SearchOutlined,} from '@ant-design/icons';
import {approvalApi} from '@/wf/services/api';
import {EmptyState, ErrorState, PageHeader} from '@/common/components/ui';
import {PageResult, Task} from '@/wf/types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title} = Typography;

const TodoList: React.FC = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [data, setData] = useState<PageResult<Task>>({
        records: [],
        total: 0,
        pageNum: 1,
        pageSize: 10,
    });
    const [searchKeyword, setSearchKeyword] = useState('');

    const fetchTodoList = async (pageNum = 1, pageSize = 10) => {
        try {
            setLoading(true);
            const result = await approvalApi.getTodoList('admin', pageNum, pageSize);
            setData(result);
        } catch (error) {
            console.error('获取待办列表失败:', error);
            setError(error as Error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchTodoList();
    }, []);

    // 获取优先级颜色
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

    // 检查是否即将到期（24小时内）
    const isNearDue = (dueTime?: string) => {
        if (!dueTime) return false;
        const due = dayjs(dueTime);
        const now = dayjs();
        const diffHours = due.diff(now, 'hour');
        return diffHours >= 0 && diffHours <= 24;
    };

    // 检查是否已逾期
    const isOverdue = (dueTime?: string) => {
        if (!dueTime) return false;
        return dayjs(dueTime).isBefore(dayjs());
    };

    const columns = [
        {
            title: '任务名称',
            dataIndex: 'name',
            key: 'name',
            render: (text: string, record: Task) => (
                <div className={styles.taskName}>
                    <span className={styles.name}>{text}</span>
                    // @ts-ignore
                    {isOverdue(String(record.dueTime)) && (
                        // @ts-ignore
                        <Tag color="red" size="small">已逾期</Tag>
                    )}
                    // @ts-ignore
                    {isNearDue(String(record.dueTime)) && !isOverdue(String(record.dueTime)) && (
                        // @ts-ignore
                        <Tag color="orange" size="small">即将到期</Tag>
                    )}
                </div>
            ),
        },
        {
            title: '所属流程',
            dataIndex: 'processDefinitionName',
            key: 'processDefinitionName',
            render: (text: string) => (
                <Tooltip title={text}>
                    <span className={styles.ellipsis}>{text}</span>
                </Tooltip>
            ),
        },
        {
            title: '优先级',
            dataIndex: 'priority',
            key: 'priority',
            width: 100,
            render: (priority: number) => (
                <Badge
                    status={priority >= 80 ? 'error' : priority >= 50 ? 'warning' : 'default'}
                    text={getPriorityText(priority)}
                />
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createTime',
            key: 'createTime',
            width: 180,
            render: (time: string) => dayjs(time).format('YYYY-MM-DD HH:mm'),
        },
        {
            title: '到期时间',
            dataIndex: 'dueTime',
            key: 'dueTime',
            width: 180,
            render: (time?: string) => {
                if (!time) return '-';
                const due = dayjs(time);
                const now = dayjs();
                const isOverdue = due.isBefore(now);
                return (
                    <span className={isOverdue ? styles.overdue : ''}>
            {dayjs(time).format('YYYY-MM-DD HH:mm')}
          </span>
                );
            },
        },
        {
            title: '操作',
            key: 'action',
            width: 120,
            fixed: 'right',
            render: (_: any, record: Task) => (
                <Space size="small">
                    <Button
                        type="primary"
                        size="small"
                        icon={<CheckCircleOutlined/>}
                        onClick={() => navigate(`/workflow/task/${record.id}`)}
                    >
                        审批
                    </Button>
                </Space>
            ),
        },
    ];

    return (
        <div className={styles.container}>
            <PageHeader title="待办任务"/>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.titleSection}>
                        <Title level={4} className={styles.title}>
                            <ClockCircleOutlined className={styles.titleIcon}/>
                            待办任务
                        </Title>
                        <span className={styles.subtitle}>
              共 <strong>{data.total}</strong> 条待办
            </span>
                    </div>
                    <Space>
                        <Input
                            placeholder="搜索任务名称"
                            prefix={<SearchOutlined/>}
                            value={searchKeyword}
                            onChange={(e) => setSearchKeyword(e.target.value)}
                            style={{width: 240}}
                            allowClear
                        />
                        <Button
                            icon={<ReloadOutlined/>}
                            // @ts-ignore
                            onClick={() => fetchTodoList(data.pageNum, data.pageSize)}
                        >
                            刷新
                        </Button>
                    </Space>
                </div>

                {error ? (
                    // @ts-ignore
                    <ErrorState error={error} onRetry={() => fetchTodoList(data.pageNum, data.pageSize)} type="inline"/>
                ) : (
                    <Table
                        // @ts-ignore
                        columns={columns}
                        dataSource={data.records}
                        rowKey="id"
                        loading={loading}
                        pagination={{
                            // @ts-ignore
                            current: data.pageNum,
                            pageSize: data.pageSize,
                            total: data.total,
                            showSizeChanger: true,
                            showQuickJumper: true,
                            showTotal: (total) => `共 ${total} 条`,
                        }}
                        onChange={(pagination) => {
                            fetchTodoList(pagination.current, pagination.pageSize);
                        }}
                        scroll={{x: 1400}}
                        locale={{
                            emptyText: (
                                <EmptyState title="暂无待办任务" description="当前没有待办任务"/>
                            ),
                        }}
                    />
                )}
            </Card>
        </div>
    );
};

export default TodoList;
