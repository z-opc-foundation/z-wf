import React, {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Button, Divider, Drawer, Form, Input, message, Popconfirm, Radio, Select,} from 'antd';
import {
    ArrowLeftOutlined,
    DeleteOutlined,
    DownloadOutlined,
    PlayCircleOutlined,
    RedoOutlined,
    SaveOutlined,
    UndoOutlined,
    ZoomInOutlined,
    ZoomOutOutlined,
} from '@ant-design/icons';
import FlowDesigner from '../../components/FlowDesigner';
import {approvalApi, designerApi} from '../../services/api';
import {ProcessDefinition} from '../../types';
import styles from './index.module.css';

const {Option} = Select;
const {TextArea} = Input;

// 审批人选项
const approverOptions = [
    {value: 'direct_leader', label: '直属上级'},
    {value: 'specify_user', label: '指定人员'},
    {value: 'role', label: '角色'},
    {value: 'dept_leader', label: '部门负责人'},
];

const ProcessDesigner: React.FC = () => {
    const {processDefinitionId} = useParams<{ processDefinitionId: string }>();
    const navigate = useNavigate();
    const designerRef = useRef<any>(null);
    const [form] = Form.useForm();

    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [deploying, setDeploying] = useState(false);
    const [processInfo, setProcessInfo] = useState<ProcessDefinition | null>(null);
    // 2026-10-04: 设计相关接口按 **流程 key** 工作，不按 definition id ——
    // 同一个 key 每次发布会产生新的 definition id，用 id 会把设计稿存到一次性版本上。
    const [processKey, setProcessKey] = useState<string>('');
    const [selectedNode, setSelectedNode] = useState<any>(null);
    const [drawerVisible, setDrawerVisible] = useState(false);
    const [graphData, setGraphData] = useState<any>(null);

    // 加载流程定义信息
    useEffect(() => {
        if (processDefinitionId) {
            fetchProcessDefinition();
        }
    }, [processDefinitionId]);

    const fetchProcessDefinition = async () => {
        try {
            setLoading(true);
            // 获取流程定义列表，找到当前编辑的流程
            const definitions = await approvalApi.getProcessDefinitions();
            const current = definitions.find(d => d.id === processDefinitionId);
            if (current) {
                setProcessInfo(current);
                // 后端 getProcessDefinitions 返回的 key 就是流程定义 key
                setProcessKey(String(current.key ?? ''));
            } else {
                message.warning('未找到该流程定义，可能已被删除或未发布');
            }

            if (!current?.key) {
                // 没有 key 就无法定位设计稿, 再往下调只会拿到 400，不如停在这里
                setGraphData(null);
                return;
            }

            // 获取流程图数据
            const flowData = await designerApi.getFlowGraph(String(current.key));
            setGraphData(flowData);
        } catch (error) {
            console.error('加载流程定义失败:', error);
            message.error('加载流程定义失败');
        } finally {
            setLoading(false);
        }
    };

    // 保存流程图
    const handleSave = async () => {
        if (!designerRef.current) return;
        if (!processKey) {
            message.warning('流程 key 还没解析出来，无法保存');
            return;
        }

        try {
            setSaving(true);
            const data = designerRef.current.getGraphData();
            await designerApi.saveFlowGraph(processKey, data);
            message.success('保存成功');
        } catch (error) {
            console.error('保存失败:', error);
            message.error('保存失败');
        } finally {
            setSaving(false);
        }
    };

    // 部署流程
    const handleDeploy = async () => {
        if (!processKey) {
            message.warning('流程 key 还没解析出来，无法部署');
            return;
        }
        try {
            setDeploying(true);
            const r: any = await designerApi.deployProcess(processKey, processInfo?.name);
            message.success(`部署成功，当前版本 v${r?.version ?? '?'}`);
        } catch (error) {
            console.error('部署失败:', error);
            message.error('部署失败');
        } finally {
            setDeploying(false);
        }
    };

    // 导出图片
    const handleExportImage = (type: 'png' | 'svg') => {
        if (designerRef.current) {
            designerRef.current.exportImage(type);
        }
    };

    // 打开节点属性面板
    const handleOpenNodeProperties = (nodeData: any) => {
        setSelectedNode(nodeData);
        setDrawerVisible(true);
        // 设置表单初始值
        form.setFieldsValue({
            name: nodeData.text?.value || '',
            approverType: nodeData.properties?.approverType || 'direct_leader',
            approver: nodeData.properties?.approver,
            multipleApproval: nodeData.properties?.multipleApproval || 'sequential',
            allowTransfer: nodeData.properties?.allowTransfer || true,
            allowAddSign: nodeData.properties?.allowAddSign || false,
            description: nodeData.properties?.description,
        });
    };

    // 保存节点属性
    const handleSaveNodeProperties = (values: any) => {
        if (selectedNode) {
            // 更新节点文本
            selectedNode.setText(values.name);
            // 更新节点属性
            selectedNode.properties = {
                ...selectedNode.properties,
                ...values,
            };
            message.success('属性保存成功');
            setDrawerVisible(false);
        }
    };

    return (
        <div className={styles.designerPage}>
            {/* 顶部工具栏 */}
            <div className={styles.toolbar}>
                <div className={styles.toolbarLeft}>
                    <Button
                        icon={<ArrowLeftOutlined/>}
                        onClick={() => navigate('/workflow/processes')}
                    >
                        返回
                    </Button>
                    <Divider type="vertical"/>
                    {processInfo && (
                        <div className={styles.processInfo}>
                            <span className={styles.processName}>{processInfo.name}</span>
                            <span className={styles.processVersion}>v{processInfo.version}</span>
                        </div>
                    )}
                </div>
                <div className={styles.toolbarCenter}>
                    <Button icon={<UndoOutlined/>} title="撤销"/>
                    <Button icon={<RedoOutlined/>} title="重做"/>
                    <Divider type="vertical"/>
                    <Button icon={<ZoomOutOutlined/>} title="缩小"/>
                    <Button icon={<ZoomInOutlined/>} title="放大"/>
                    <Divider type="vertical"/>
                    <Button icon={<DeleteOutlined/>} title="删除" danger/>
                </div>
                <div className={styles.toolbarRight}>
                    <Button
                        icon={<DownloadOutlined/>}
                        onClick={() => handleExportImage('png')}
                    >
                        导出图片
                    </Button>
                    <Button
                        icon={<SaveOutlined/>}
                        loading={saving}
                        onClick={handleSave}
                    >
                        保存
                    </Button>
                    <Popconfirm
                        title="部署流程"
                        description="部署后新的流程实例将使用此版本，确定部署吗？"
                        onConfirm={handleDeploy}
                        okText="确定"
                        cancelText="取消"
                    >
                        <Button
                            type="primary"
                            icon={<PlayCircleOutlined/>}
                            loading={deploying}
                        >
                            部署
                        </Button>
                    </Popconfirm>
                </div>
            </div>

            {/* 流程设计器 */}
            <div className={styles.designerContainer}>
                <FlowDesigner
                    // @ts-ignore
                    ref={designerRef}
                    processDefinitionId={processDefinitionId}
                    initialData={graphData}
                    readOnly={false}
                />
            </div>

            {/* 节点属性面板 */}
            <Drawer
                title="节点属性"
                placement="right"
                size="large"
                onClose={() => setDrawerVisible(false)}
                open={drawerVisible}
            >
                <Form
                    form={form}
                    layout="vertical"
                    onFinish={handleSaveNodeProperties}
                >
                    <Form.Item
                        label="节点名称"
                        name="name"
                        rules={[{required: true, message: '请输入节点名称'}]}
                    >
                        <Input placeholder="请输入节点名称"/>
                    </Form.Item>

                    <Form.Item
                        label="审批人类型"
                        name="approverType"
                    >
                        <Select placeholder="请选择审批人类型">
                            <Select.Option value="direct_leader">直属上级</Select.Option>
                            <Select.Option value="specify_user">指定人员</Select.Option>
                            <Select.Option value="role">角色</Select.Option>
                            <Select.Option value="dept_leader">部门负责人</Select.Option>
                        </Select>
                    </Form.Item>

                    <Form.Item
                        label="指定审批人"
                        name="approver"
                    >
                        <Select
                            mode="multiple"
                            placeholder="请选择审批人"
                            allowClear
                        />
                    </Form.Item>

                    <Form.Item
                        label="多人审批方式"
                        name="multipleApproval"
                    >
                        <Radio.Group>
                            <Radio value="sequential">依次审批</Radio>
                            <Radio value="parallel">会签（同时审批）</Radio>
                            <Radio value="any">或签（任一人审批）</Radio>
                        </Radio.Group>
                    </Form.Item>

                    <Form.Item
                        label="允许转交"
                        name="allowTransfer"
                        valuePropName="checked"
                    >
                        <Radio.Group>
                            <Radio value={true}>是</Radio>
                            <Radio value={false}>否</Radio>
                        </Radio.Group>
                    </Form.Item>

                    <Form.Item
                        label="允许加签"
                        name="allowAddSign"
                        valuePropName="checked"
                    >
                        <Radio.Group>
                            <Radio value={true}>是</Radio>
                            <Radio value={false}>否</Radio>
                        </Radio.Group>
                    </Form.Item>

                    <Form.Item
                        label="节点说明"
                        name="description"
                    >
                        <Input.TextArea
                            rows={4}
                            placeholder="请输入节点说明"
                        />
                    </Form.Item>

                    <Form.Item>
                        <Button type="primary" htmlType="submit" block>
                            保存
                        </Button>
                    </Form.Item>
                </Form>
            </Drawer>
        </div>
    );
};

export default ProcessDesigner;
