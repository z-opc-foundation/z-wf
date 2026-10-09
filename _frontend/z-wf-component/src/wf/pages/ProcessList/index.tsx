import React, {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Badge, Button, Card, Dropdown, Form, Input, message, Modal, Select, Space, Table, Tag, Typography,} from 'antd';
import {
    BranchesOutlined,
    CopyOutlined,
    DeleteOutlined,
    EditOutlined,
    ExclamationCircleOutlined,
    MoreOutlined,
    PlusOutlined,
    ReloadOutlined,
    SearchOutlined,
} from '@ant-design/icons';
import {approvalApi} from '../../services/api';
import {EmptyState, ErrorState, PageHeader} from '../../ui';
import {ProcessDefinition} from '../../types';
import dayjs from 'dayjs';
import styles from './index.module.css';

const {Title} = Typography;
const {confirm} = Modal;

const ProcessList: React.FC = () => {
    const navigate = useNavigate();
    const [form] = Form.useForm();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const [data, setData] = useState<ProcessDefinition[]>([]);
    const [searchKeyword, setSearchKeyword] = useState('');
    const [modalVisible, setModalVisible] = useState(false);
    const [editingProcess, setEditingProcess] = useState<ProcessDefinition | null>(null);

    const fetchProcessList = async () => {
        try {
            setLoading(true);
            const result = await approvalApi.getProcessDefinitions();
            setData(result);
        } catch (error) {
            console.error('获取流程列表失败:', error);
            setError(error as Error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchProcessList();
    }, []);

    const handleCreate = () => {
        setEditingProcess(null);
        form.resetFields();
        setModalVisible(true);
    };

    const handleEdit = (record: ProcessDefinition) => {
        setEditingProcess(record);
        form.setFieldsValue({
            name: record.name,
            key: record.key,
            description: record.description,
            category: record.category,
        });
        setModalVisible(true);
    };

    const handleDelete = (record: ProcessDefinition) => {
        confirm({
            title: '确认删除',
            icon: <ExclamationCircleOutlined/>,
            content: `确定要删除流程 "${record.name}" 吗？此操作不可恢复。`,
            okText: '删除',
            okType: 'danger',
            cancelText: '取消',
            onOk: async () => {
                try {
                    // 调用删除API
                    message.success('删除成功');
                    fetchProcessList();
                } catch (error) {
                    message.error('删除失败');
                }
            },
        });
    };

    const handleSave = async (values: any) => {
        try {
            if (editingProcess) {
                // 更新
                message.success('更新成功');
            } else {
                // 创建
                message.success('创建成功');
            }
            setModalVisible(false);
            fetchProcessList();
        } catch (error) {
            message.error('保存失败');
        }
    };

    const getStatusBadge = (status: string) => {
        switch (status) {
            case 'active':
                return <Badge status="processing" text="已部署"/>;
            case 'suspended':
                return <Badge status="warning" text="已暂停"/>;
            default:
                return <Badge status="default" text="草稿"/>;
        }
    };

    const columns = [
        {
            title: '流程名称',
            dataIndex: 'name',
            key: 'name',
            render: (text: string, record: ProcessDefinition) => (
                <div className={styles.processName}>
                    <BranchesOutlined className={styles.processIcon}/>
                    <div className={styles.nameInfo}>
                        <span className={styles.name}>{text}</span>
                        // @ts-ignore
                        <span className={styles.key}>{String(record.key)}</span>
                    </div>
                </div>
            ),
        },
        {
            title: '版本',
            dataIndex: 'version',
            key: 'version',
            width: 80,
            render: (version: number) => (
                <Tag color="blue">v{version}</Tag>
            ),
        },
        {
            title: '分类',
            dataIndex: 'category',
            key: 'category',
            width: 120,
            render: (category?: string) => category || '-',
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (status: string) => getStatusBadge(status),
        },
        {
            title: '更新时间',
            dataIndex: 'updateTime',
            key: 'updateTime',
            width: 180,
            render: (time: string) => dayjs(time).format('YYYY-MM-DD HH:mm:ss'),
        },
        {
            title: '操作',
            key: 'action',
            width: 180,
            fixed: 'right',
            render: (_: any, record: ProcessDefinition) => (
                <Space size="small">
                    <Button
                        type="primary"
                        size="small"
                        icon={<EditOutlined/>}
                        onClick={() => navigate(`/workflow/designer/${record.id}`)}
                    >
                        设计
                    </Button>
                    <Dropdown
                        menu={{
                            items: [
                                {
                                    key: 'edit',
                                    icon: <EditOutlined/>,
                                    label: '编辑信息',
                                    onClick: () => handleEdit(record),
                                },
                                {
                                    key: 'copy',
                                    icon: <CopyOutlined/>,
                                    label: '复制',
                                },
                                {
                                    type: 'divider',
                                },
                                {
                                    key: 'delete',
                                    icon: <DeleteOutlined/>,
                                    label: '删除',
                                    danger: true,
                                    onClick: () => handleDelete(record),
                                },
                            ],
                        }}
                    >
                        <Button size="small">
                            <MoreOutlined/>
                        </Button>
                    </Dropdown>
                </Space>
            ),
        },
    ];

    return (
        <div className={styles.container}>
            <PageHeader title="流程设计"/>
            <Card className={styles.card}>
                <div className={styles.header}>
                    <div className={styles.titleSection}>
                        <Title level={4} className={styles.title}>
                            <BranchesOutlined className={styles.titleIcon}/>
                            流程设计
                        </Title>
                        <span className={styles.subtitle}>
              共 <strong>{data.length}</strong> 个流程
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
                            onClick={fetchProcessList}
                        >
                            刷新
                        </Button>
                        <Button
                            type="primary"
                            icon={<PlusOutlined/>}
                            onClick={handleCreate}
                        >
                            新建流程
                        </Button>
                    </Space>
                </div>

                {error ? (
                    // @ts-ignore
                    <ErrorState error={error} onRetry={fetchProcessList} type="inline"/>
                ) : (
                    <Table
                        // @ts-ignore
                        columns={columns}
                        dataSource={data}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1400}}
                        locale={{
                            emptyText: (
                                <EmptyState
                                    title="暂无流程定义"
                                    description="当前没有流程定义"
                                    // @ts-ignore
                                    action={
                                        <Button type="primary" icon={<PlusOutlined/>} onClick={handleCreate}>
                                            新建流程
                                        </Button>
                                    }
                                />
                            ),
                        }}
                    />
                )}
            </Card>

            {/* 新建/编辑流程弹窗 */}
            <Modal
                title={editingProcess ? '编辑流程' : '新建流程'}
                open={modalVisible}
                onOk={() => form.submit()}
                onCancel={() => setModalVisible(false)}
            >
                <Form
                    form={form}
                    layout="vertical"
                    onFinish={handleSave}
                >
                    <Form.Item
                        label="流程名称"
                        name="name"
                        rules={[{required: true, message: '请输入流程名称'}]}
                    >
                        <Input placeholder="请输入流程名称"/>
                    </Form.Item>

                    <Form.Item
                        label="流程标识"
                        name="key"
                        rules={[
                            {required: true, message: '请输入流程标识'},
                            {pattern: /^[a-z0-9_]+$/, message: '只能包含小写字母、数字和下划线'}
                        ]}
                    >
                        <Input placeholder="请输入流程标识，如：leave_process" disabled={!!editingProcess}/>
                    </Form.Item>

                    <Form.Item
                        label="分类"
                        name="category"
                    >
                        <Select placeholder="请选择分类" allowClear>
                            <Select.Option value="人事">人事</Select.Option>
                            <Select.Option value="财务">财务</Select.Option>
                            <Select.Option value="行政">行政</Select.Option>
                            <Select.Option value="业务">业务</Select.Option>
                            <Select.Option value="IT">IT</Select.Option>
                        </Select>
                    </Form.Item>

                    <Form.Item
                        label="描述"
                        name="description"
                    >
                        <Input.TextArea
                            rows={4}
                            placeholder="请输入流程描述"
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default ProcessList;
