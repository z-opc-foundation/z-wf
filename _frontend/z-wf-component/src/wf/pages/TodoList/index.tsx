import React, {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Badge, Button, Space, Tag, Tooltip, Typography,} from 'antd';
import {CheckCircleOutlined, ClockCircleOutlined, ReloadOutlined,} from '@ant-design/icons';
import {approvalApi} from '../../services/api';
import {ErrorState, ListTable} from '@yuku123/z-frontend-common/ui';
import {PageResult, Task} from '../../types';
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
                    {isOverdue(String(record.dueTime)) && (
                        <Tag color="red" size="small">已逾期</Tag>
                    )}
                    {isNearDue(String(record.dueTime)) && !isOverdue(String(record.dueTime)) && (
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
                        onClick={() => navigate(`/z-wf/workflow/task/${record.id}`)}
                    >
                        审批
                    </Button>
                </Space>
            ),
        },
    ];

    return (
        <div className={styles.container}>
            {error ? (
                <ErrorState error={error} onRetry={() => fetchTodoList(data.pageNum, data.pageSize)} variant="inline"/>
            ) : (
                /* 服务端分页，不套客户端分页：
                   - 分页对象保留原样（current/pageSize/total 由后端驱动，翻页重查接口），
                     只补 §2 规则 2 要求的 pageSizeOptions；
                   - 过滤框用基座 ListTable 自带的端侧过滤（后端 list 接口没有 keyword 参数，
                     过滤只作用在当前页已加载的数据上）；
                   - 空态由基座 <EmptyState/> 承接。 */
                <ListTable
                    rows={data.records}
                    columns={columns}
                    rowKey="id"
                    loading={loading}
                    pagination={{
                        current: data.pageNum,
                        pageSize: data.pageSize,
                        total: data.total,
                        showSizeChanger: true,
                        showQuickJumper: true,
                        pageSizeOptions: ['10', '20', '50', '100'],
                        showTotal: (total) => `共 ${total} 条`,
                    }}
                    onChange={(pagination) => {
                        fetchTodoList(pagination.current, pagination.pageSize);
                    }}
                    scroll={{x: 1400}}
                    cardTitle={
                        <div className={styles.titleSection}>
                            <Title level={4} className={styles.title}>
                                <ClockCircleOutlined className={styles.titleIcon}/>
                                待办任务
                            </Title>
                            <span className={styles.subtitle}>
              共 <strong>{data.total}</strong> 条待办
            </span>
                        </div>
                    }
                    cardExtra={
                        <Button
                            icon={<ReloadOutlined/>}
                            onClick={() => fetchTodoList(data.pageNum, data.pageSize)}
                        >
                            刷新
                        </Button>
                    }
                    searchKeys="name,processDefinitionName"
                    searchPlaceholder="搜索任务名称"
                    emptyTitle="暂无待办任务"
                    emptyDescription="当前没有待办任务"
                    cardProps={{className: styles.card}}
                />
            )}
        </div>
    );
};

export default TodoList;
