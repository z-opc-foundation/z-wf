import React, {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Badge, Button, Card, Input, message, Modal, Space, Table, Typography,} from 'antd';
import {
    DeleteOutlined,
    ExclamationCircleOutlined,
    EyeOutlined,
    FileTextOutlined,
    ReloadOutlined,
    SearchOutlined,
} from '@ant-design/icons';
import {approvalApi} from '../../services/api';
import {EmptyState, ErrorState, PageHeader} from '../../ui';
import {PageResult, ProcessInstance} from '../../types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title} = Typography;
const {confirm} = Modal;

const MyProcesses: React.FC = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [data, setData] = useState<PageResult<ProcessInstance>>({
        records: [],
        total: 0,
        pageNum: 1,
        pageSize: 10,
    });
    const [searchKeyword, setSearchKeyword] = useState('');

    const fetchMyProcesses = async (pageNum = 1, pageSize = 10) => {
        try {
            setLoading(true);
            const result = await approvalApi.getMyProcesses('admin', pageNum, pageSize);
            setData(result);
        } catch (error) {
            console.error('获取我的流程列表失败:', error);
            setError(error as Error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchMyProcesses();
    }, []);

    const handleDelete = (record: ProcessInstance) => {
        confirm({
            title: '确认删除',
            icon: <ExclamationCircleOutlined/>,
            content: `确定要删除流程实例 "${record.processDefinitionName}" 吗？此操作不可恢复。`,
            okText: '删除',
            okType: 'danger',
            cancelText: '取消',
            onOk: async () => {
                try {
                    // 2026-10-04 修：后端 DELETE /api/approval-center/processes 收
                    // processInstanceId，而列表 VO 里也没有 id 字段（只有 processInstanceId）。
                    // 原来传 record.id 恒为 undefined，点删除必然 400。
                    await approvalApi.deleteProcessInstance(record.processInstanceId);
                    message.success('删除成功');
                    fetchMyProcesses();
                } catch (error) {
                    // @ts-ignore
                    message.error('删除失败');
                }
            },
        });
    };

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

    const columns = [
        {
            title: '流程名称',
            dataIndex: 'processDefinitionName',
            key: 'processDefinitionName',
            render: (text: string) => (
                <div className={styles.processName}>
                    <FileTextOutlined className={styles.processIcon}/>
                    <span className={styles.name}>{text}</span>
                </div>
            ),
        },
        {
            title: '业务标识',
            dataIndex: 'businessKey',
            key: 'businessKey',
            render: (text?: string) => text || '-',
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (status: string) => getStatusTag(status),
        },
        {
            title: '发起人',
            dataIndex: 'startUserName',
            key: 'startUserName',
            width: 120,
            render: (text?: string) => text || '系统',
        },
        {
            title: '启动时间',
            dataIndex: 'startTime',
            key: 'startTime',
            width: 180,
            render: (time: string) => dayjs(time).format('YYYY-MM-DD HH:mm:ss'),
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            key: 'endTime',
            width: 180,
            render: (time?: string) => time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-',
        },
        {
            title: '耗时',
            dataIndex: 'duration',
            key: 'duration',
            width: 120,
            render: (duration?: number) => {
                if (!duration) return '-';
                const hours = Math.floor(duration / 3600000);
                const minutes = Math.floor((duration % 3600000) / 60000);
                if (hours > 0) {
                    return `${hours}小时${minutes}分钟`;
                }
                return `${minutes}分钟`;
            },
        },
        {
            title: '操作',
            key: 'action',
            width: 150,
            fixed: 'right',
            render: (_: any, record: ProcessInstance) => (
                <Space size="small">
                    <Button
                        type="primary"
                        size="small"
                        icon={<EyeOutlined/>}
                        onClick={() => navigate(`/workflow/process/${record.id}`)}
                    >
                        详情
                    </Button>
                    {record.status !== 'completed' && record.status !== 'terminated' && (
                        <Button
                            size="small"
                            danger
                            icon={<DeleteOutlined/>}
                            onClick={() => handleDelete(record)}
                        >
                            终止
                        </Button>
                    )}
                </Space>
            ),
        },
    ];

    return (
        <div className={styles.container}>
            <PageHeader title="我发起的流程"/>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.titleSection}>
                        <Title level={4} className={styles.title}>
                            <FileTextOutlined className={styles.titleIcon}/>
                            我发起的流程
                        </Title>
                        <span className={styles.subtitle}>
              共 <strong>{data.total}</strong> 个流程实例
            </span>
                    </div>
                    <Space>
                        <Input
                            placeholder="搜索流程名称"
                            prefix={<SearchOutlined/>}
                            value={searchKeyword}
                            onChange={(e) => setSearchKeyword(e.target.value)}
                            style={{width: 240}}
                            allowClear
                        />
                        <Button
                            icon={<ReloadOutlined/>}
                            // @ts-ignore
                            onClick={() => fetchMyProcesses(data.pageNum, data.pageSize)}
                        >
                            刷新
                        </Button>
                    </Space>
                </div>

                {error ? (
                    // ErrorState 的 prop 是 variant（'inline' | 'full'），type 是 antd 4 旧名
                    <ErrorState error={error} onRetry={() => fetchMyProcesses(data.pageNum, data.pageSize)}
                                variant="inline"/>
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
                            fetchMyProcesses(pagination.current, pagination.pageSize);
                        }}
                        scroll={{x: 1800}}
                        locale={{
                            emptyText: (
                                <EmptyState title="暂无流程实例" description="当前没有流程实例"/>
                            ),
                        }}
                    />
                )}
            </Card>
        </div>
    );
};

export default MyProcesses;
