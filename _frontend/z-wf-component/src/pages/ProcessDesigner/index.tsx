import React, {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Alert, Button, Divider, Drawer, Form, Input, message, Popconfirm, Radio, Select, Spin, Tooltip,} from 'antd';
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
import FlowDesigner, {FlowDesignerHandle} from '../../components/FlowDesigner';
import {approvalApi, designerApi} from '../../wf/services/api';
import {ProcessDefinition} from '../../wf/types';
import styles from './index.module.css';

// 审批人类型。2026-11-12 对齐后端 LogicFlowGraphConverter#resolveAssignee：
// 只有 specify_user 会静态落成 camunda:assignee 表达式，其余三类需运行时求解钩子（暂缺），留空走人工认领。
const approverOptions = [
    {value: 'specify_user', label: '指定人员'},
    {value: 'direct_leader', label: '直属上级（暂按人工认领）'},
    {value: 'dept_leader', label: '部门负责人（暂按人工认领）'},
    {value: 'role', label: '角色（暂按人工认领）'},
];

const ProcessDesigner: React.FC = () => {
    const {processDefinitionId} = useParams<{ processDefinitionId: string }>();
    const navigate = useNavigate();
    const designerRef = useRef<FlowDesignerHandle>(null);
    const [form] = Form.useForm();

    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [deploying, setDeploying] = useState(false);
    const [processInfo, setProcessInfo] = useState<ProcessDefinition | null>(null);
    // 设计相关接口按 **流程 key** 工作，不按 definition id ——
    // 同一个 key 每次发布会产生新的 definition id，用 id 会把设计稿存到一次性版本上。
    const [processKey, setProcessKey] = useState<string>('');
    const [selectedNode, setSelectedNode] = useState<any>(null);
    const [drawerVisible, setDrawerVisible] = useState(false);
    const [graphData, setGraphData] = useState<any>(null);
    const approverType = Form.useWatch('approverType', form);

    // 加载流程定义信息
    useEffect(() => {
        if (processDefinitionId) {
            fetchProcessDefinition();
        }
    }, [processDefinitionId]);

    const fetchProcessDefinition = async () => {
        try {
            setLoading(true);
            const definitions = await approvalApi.getProcessDefinitions();
            const current = definitions.find(d => d.id === processDefinitionId);
            if (current) {
                setProcessInfo(current);
                setProcessKey(String(current.key ?? ''));
            } else {
                message.warning('未找到该流程定义，可能已被删除或未发布');
            }

            if (!current?.key) {
                setGraphData(null);
                return;
            }

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
            message.success('设计稿已保存');
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
    const handleExportImage = () => {
        designerRef.current?.exportImage();
    };

    // 打开节点属性面板
    const handleOpenNodeProperties = (nodeData: any) => {
        setSelectedNode(nodeData);
        setDrawerVisible(true);
        form.setFieldsValue({
            name: nodeData.text?.value || '',
            approverType: nodeData.properties?.approverType || 'specify_user',
            approver: nodeData.properties?.approver,
            multipleApproval: nodeData.properties?.multipleApproval || 'sequential',
            allowTransfer: nodeData.properties?.allowTransfer ?? true,
            allowAddSign: nodeData.properties?.allowAddSign ?? false,
            description: nodeData.properties?.description,
        });
    };

    // 保存节点属性。node:click 事件给的是数据快照（没有 setText 方法），
    // 改画布必须走 lf.updateText / lf.setProperties。
    const handleSaveNodeProperties = (values: any) => {
        if (!selectedNode) return;
        designerRef.current?.updateNode(selectedNode.id, {
            text: values.name,
            properties: values,
        });
        message.success('属性已保存（记得点「保存」写入设计稿）');
        setDrawerVisible(false);
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
                    <Divider orientation="vertical"/>
                    {processInfo && (
                        <div className={styles.processInfo}>
                            <span className={styles.processName}>{processInfo.name}</span>
                            <span className={styles.processVersion}>v{processInfo.version}</span>
                            <span className={styles.processKey}>{processKey}</span>
                        </div>
                    )}
                </div>
                <div className={styles.toolbarCenter}>
                    <Tooltip title="撤销 (Ctrl+Z)">
                        <Button icon={<UndoOutlined/>} onClick={() => designerRef.current?.undo()}/>
                    </Tooltip>
                    <Tooltip title="重做 (Ctrl+Shift+Z)">
                        <Button icon={<RedoOutlined/>} onClick={() => designerRef.current?.redo()}/>
                    </Tooltip>
                    <Divider orientation="vertical"/>
                    <Tooltip title="缩小">
                        <Button icon={<ZoomOutOutlined/>} onClick={() => designerRef.current?.zoomOut()}/>
                    </Tooltip>
                    <Tooltip title="放大">
                        <Button icon={<ZoomInOutlined/>} onClick={() => designerRef.current?.zoomIn()}/>
                    </Tooltip>
                    <Tooltip title="删除选中（Del）">
                        <Button
                            icon={<DeleteOutlined/>}
                            danger
                            onClick={() => designerRef.current?.deleteSelected()}
                        />
                    </Tooltip>
                </div>
                <div className={styles.toolbarRight}>
                    <Button
                        icon={<DownloadOutlined/>}
                        onClick={handleExportImage}
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
            <Spin spinning={loading} classNames={{root: styles.designerSpin}}>
                <div className={styles.designerContainer}>
                    <FlowDesigner
                        ref={designerRef}
                        processDefinitionId={processDefinitionId}
                        initialData={graphData}
                        readOnly={false}
                        onNodeClick={handleOpenNodeProperties}
                    />
                </div>
            </Spin>

            {/* 节点属性面板 */}
            <Drawer
                title="节点属性"
                placement="right"
                size="default"
                onClose={() => setDrawerVisible(false)}
                open={drawerVisible}
            >
                <Form form={form} layout="vertical" onFinish={handleSaveNodeProperties}>
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
                        extra="仅「指定人员」会写入流程定义；其余类型运行时走人工认领"
                    >
                        <Select options={approverOptions} placeholder="请选择审批人类型"/>
                    </Form.Item>

                    {approverType === 'specify_user' && (
                        <Form.Item
                            label="审批人（用户名）"
                            name="approver"
                            extra="输入用户名后回车确认；多个逗号分隔，任一人通过即流转"
                            rules={[{required: true, message: '至少填写一个审批人'}]}
                        >
                            <Select
                                mode="tags"
                                tokenSeparators={[',']}
                                placeholder="如 admin"
                                options={[{value: 'admin', label: 'admin'}]}
                            />
                        </Form.Item>
                    )}

                    <Form.Item label="多人审批方式" name="multipleApproval">
                        <Radio.Group>
                            <Radio value="sequential">依次审批</Radio>
                            <Radio value="parallel">会签</Radio>
                            <Radio value="any">或签</Radio>
                        </Radio.Group>
                    </Form.Item>

                    <Form.Item label="允许转交" name="allowTransfer" valuePropName="checked">
                        <Radio.Group>
                            <Radio value={true}>是</Radio>
                            <Radio value={false}>否</Radio>
                        </Radio.Group>
                    </Form.Item>

                    <Form.Item label="允许加签" name="allowAddSign" valuePropName="checked">
                        <Radio.Group>
                            <Radio value={true}>是</Radio>
                            <Radio value={false}>否</Radio>
                        </Radio.Group>
                    </Form.Item>

                    <Form.Item label="节点说明" name="description">
                        <Input.TextArea rows={4} placeholder="请输入节点说明"/>
                    </Form.Item>

                    <Form.Item>
                        <Button type="primary" htmlType="submit" block>
                            保存节点属性
                        </Button>
                    </Form.Item>
                </Form>
            </Drawer>

            <Alert
                className={styles.deployHint}
                type="info"
                showIcon
                title="设计稿保存后点「部署」才会生成新版本的流程定义；分支条件与会签/或签暂未落成 BPMN 语义，运行时按人工任务流转。"
            />
        </div>
    );
};

export default ProcessDesigner;
