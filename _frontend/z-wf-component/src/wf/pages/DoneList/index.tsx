import React, {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Button, Space, Tag, Tooltip, Typography,} from 'antd';
import {EyeOutlined, HistoryOutlined, ReloadOutlined,} from '@ant-design/icons';
import {approvalApi} from '../../services/api';
import {ErrorState, ListTable, PageHeader} from '@yuku123/z-frontend-common/ui';
import {PageResult, Task} from '../../types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title} = Typography;

const DoneList: React.FC = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [data, setData] = useState<PageResult<Task>>({
        records: [],
        total: 0,
        pageNum: 1,
        pageSize: 10,
    });

    const fetchDoneList = async (pageNum = 1, pageSize = 10) => {
        try {
            setLoading(true);
            const result = await approvalApi.getDoneList('admin', pageNum, pageSize);
            setData(result);
        } catch (error) {
            console.error('获取已办列表失败:', error);
            setError(error as Error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchDoneList();
    }, []);

    const columns = [
        {
            title: '任务名称',
            dataIndex: 'name',
            key: 'name',
            render: (text: string) => (
                <span className={styles.taskName}>{text}</span>
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
            title: '处理结果',
            dataIndex: 'action',
            key: 'action',
            width: 120,
            render: (action: string) => (
                <Tag color={action === 'approve' ? 'success' : 'error'}>
                    {action === 'approve' ? '已通过' : '已驳回'}
                </Tag>
            ),
        },
        {
            title: '处理时间',
            dataIndex: 'endTime',
            key: 'endTime',
            width: 180,
            render: (time: string) => dayjs(time).format('YYYY-MM-DD HH:mm:ss'),
        },
        {
            title: '操作',
            key: 'action',
            width: 120,
            fixed: 'right',
            render: (_: any, record: Task) => (
                <Space size="small">
                    <Button
                        type="text"
                        icon={<EyeOutlined/>}
                        onClick={() => navigate(`/workflow/task/${record.id}`)}
                    >
                        查看
                    </Button>
                </Space>
            ),
        },
    ];

    return (
        <div className={styles.container}>
            <PageHeader title="已办任务"/>
            {error ? (
                // ErrorState 的 prop 是 variant（'inline' | 'full'），type 是 antd 4 旧名
                // @ts-ignore
                <ErrorState error={error} onRetry={() => fetchDoneList(data.pageNum, data.pageSize)}
                            variant="inline"/>
            ) : (
                /* 服务端分页，不套客户端分页：
                   - 分页对象保留原样（current/pageSize/total 由后端驱动，翻页重查接口），
                     只补 §2 规则 2 要求的 pageSizeOptions；
                   - 过滤框用基座 ListTable 自带的端侧过滤（后端 list 接口没有 keyword 参数，
                     过滤只作用在当前页已加载的数据上）；
                   - 空态由基座 <EmptyState/> 承接。 */
                <ListTable
                    rows={data.records}
                    // @ts-ignore
                    columns={columns}
                    rowKey="id"
                    loading={loading}
                    pagination={{
                        // @ts-ignore
                        current: data.pageNum,
                        pageSize: data.pageSize,
                        total: data.total,
                        showSizeChanger: true,
                        showQuickJumper: true,
                        pageSizeOptions: ['10', '20', '50', '100'],
                        showTotal: (total) => `共 ${total} 条`,
                    }}
                    onChange={(pagination) => {
                        fetchDoneList(pagination.current, pagination.pageSize);
                    }}
                    cardTitle={
                        <div className={styles.titleSection}>
                            <Title level={4} className={styles.title}>
                                <HistoryOutlined className={styles.titleIcon}/>
                                已办任务
                            </Title>
                            <span className={styles.subtitle}>
              共 <strong>{data.total}</strong> 条记录
            </span>
                        </div>
                    }
                    cardExtra={
                        <Button
                            icon={<ReloadOutlined/>}
                            // @ts-ignore
                            onClick={() => fetchDoneList(data.pageNum, data.pageSize)}
                        >
                            刷新
                        </Button>
                    }
                    searchKeys="name,processDefinitionName"
                    searchPlaceholder="搜索任务名称"
                    emptyTitle="暂无已办任务"
                    emptyDescription="当前没有已办任务"
                    cardProps={{className: styles.card}}
                />
            )}
        </div>
    );
};

export default DoneList;
