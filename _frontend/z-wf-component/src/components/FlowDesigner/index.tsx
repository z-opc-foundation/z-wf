import React, {forwardRef, useEffect, useImperativeHandle, useRef} from 'react';
import LogicFlow from '@logicflow/core';
import {SelectionSelect, Snapshot} from '@logicflow/extension';
import {registerApprovalNodes} from './nodes';
import styles from './index.module.css';

export interface FlowDesignerHandle {
    getGraphData: () => any;
    undo: () => void;
    redo: () => void;
    zoomIn: () => void;
    zoomOut: () => void;
    resetZoom: () => void;
    deleteSelected: () => void;
    exportImage: () => void;
    updateNode: (id: string, patch: {text?: string; properties?: Record<string, any>}) => void;
}

interface FlowDesignerProps {
    processDefinitionId?: string;
    initialData?: any;
    readOnly?: boolean;
    onNodeClick?: (nodeData: any) => void;
}

// 左侧节点面板。start 一般已存在，不提供拖入，避免画布出现两个起点。
const PALETTE = [
    {type: 'approval', label: '审批节点', hint: '审批步骤，可指定处理人'},
    {type: 'condition', label: '条件分支', hint: '网关，按条件走不同支路'},
    {type: 'copy', label: '抄送节点', hint: '知会（部署后按人工任务落地）'},
    {type: 'end', label: '结束节点', hint: '流程终点'},
];

// LogicFlow.use 是全局静态注册，重复 use 会往插件表里塞同名插件
let pluginsRegistered = false;

const FlowDesigner = forwardRef<FlowDesignerHandle, FlowDesignerProps>(
    ({processDefinitionId, initialData, readOnly = false, onNodeClick}, ref) => {
        const containerRef = useRef<HTMLDivElement>(null);
        const lfRef = useRef<any>(null);
        // 回调走 ref，避免父组件每次渲染都触发画布重建
        const onNodeClickRef = useRef(onNodeClick);
        onNodeClickRef.current = onNodeClick;

        const normalizeData = (raw: any) => {
            let data = raw;
            // 后端 /model/graph 返回的 graph 是 JSON 字符串，兼容对象两种形态
            if (typeof data === 'string') {
                try {
                    data = JSON.parse(data);
                } catch {
                    data = null;
                }
            }
            if (!data || !data.nodes) return null;
            // 历史设计稿里边的 type 可能是 ""：LF2 视图层按 type 查注册表查不到
            // 会静默不挂载（模型里有边、DOM 里没有），统一兜成 polyline
            if (Array.isArray(data.edges)) {
                data.edges = data.edges.map((e: any) => ({...e, type: e.type || 'polyline'}));
            }
            return data;
        };

        // 实例只建一次；initialData 变化用 lf.render 重放，不走 destroy/重建
        // （2.x 反复 destroy 再建同容器实例会留下空 graphModel 的僵尸实例）
        useEffect(() => {
            if (!containerRef.current || lfRef.current) return;

            if (!pluginsRegistered) {
                LogicFlow.use(SelectionSelect);
                LogicFlow.use(Snapshot);
                pluginsRegistered = true;
            }

            const lf = new LogicFlow({
                container: containerRef.current,
                grid: {size: 16, visible: true, type: 'dot', config: {color: '#e0e4ea'}},
                background: {backgroundColor: '#fafbfc'},
                defaultEdgeType: 'polyline',
                keyboard: {enabled: !readOnly},
                snapline: true,
                history: true,
                textEdit: !readOnly,
                isSilentMode: readOnly,
            });

            registerApprovalNodes(lf);

            lf.on('node:click', ({data}: any) => onNodeClickRef.current?.(data));

            lf.setTheme({
                polyline: {stroke: '#a3b1bf', strokeWidth: 1.5},
                arrow: {offset: 8, verticalLength: 4},
                anchor: {r: 4, fill: '#1677ff', stroke: '#fff', strokeWidth: 1},
                anchorLine: {stroke: '#1677ff', strokeWidth: 1, strokeDasharray: '4,3'},
                snapline: {stroke: '#1677ff', strokeWidth: 1},
            });

            const data = normalizeData(initialData);
            lf.render(data || {
                nodes: [
                    {id: 'start-1', type: 'start', x: 200, y: 240, text: '开始'},
                    {id: 'end-1', type: 'end', x: 560, y: 240, text: '结束'},
                ],
                edges: [{id: 'e-1', sourceNodeId: 'start-1', targetNodeId: 'end-1', type: 'polyline'}],
            });

            lfRef.current = lf;
            // 仅 dev：暴露给控制台/自动化诊断用
            if (import.meta.env.DEV) (window as any).__lf = lf;
            // eslint-disable-next-line react-hooks/exhaustive-deps
        }, []);

        // 数据到位后重放（首帧是 fallback，异步数据来了换真图）
        useEffect(() => {
            const lf = lfRef.current;
            if (!lf) return;
            const data = normalizeData(initialData);
            if (data) lf.render(data);
            // eslint-disable-next-line react-hooks/exhaustive-deps
        }, [initialData]);

        useImperativeHandle(ref, () => ({
            getGraphData: () => lfRef.current?.getGraphData() ?? null,
            undo: () => lfRef.current?.undo(),
            redo: () => lfRef.current?.redo(),
            zoomIn: () => lfRef.current?.zoom(true),
            zoomOut: () => lfRef.current?.zoom(false),
            resetZoom: () => lfRef.current?.resetZoom(),
            exportImage: () => (lfRef.current as any)?.getSnapshot?.(),
            updateNode: (id, patch) => {
                const lf = lfRef.current;
                if (!lf) return;
                if (patch.text !== undefined) lf.updateText(id, patch.text);
                if (patch.properties) lf.setProperties(id, patch.properties);
            },
            deleteSelected: () => {
                const lf = lfRef.current;
                if (!lf) return;
                const sel = lf.getSelectElements();
                (sel.edges || []).forEach((e: any) => lf.deleteEdge(e.id));
                (sel.nodes || []).forEach((n: any) => lf.deleteNode(n.id));
                lf.clearSelectElements();
            },
        }), []);

        const startDrag = (type: string) => (e: React.MouseEvent) => {
            e.preventDefault();
            lfRef.current?.dnd?.startDrag({type});
        };

        return (
            <div className={styles.designerContainer}>
                <div ref={containerRef} className={styles.canvas}/>
                {!readOnly && (
                    <div className={styles.palette}>
                        <div className={styles.paletteTitle}>节点</div>
                        {PALETTE.map((item) => (
                            <div
                                key={item.type}
                                className={styles.paletteItem}
                                onMouseDown={startDrag(item.type)}
                            >
                                <span className={`${styles.paletteDot} ${styles['dot_' + item.type]}`}/>
                                <div className={styles.paletteText}>
                                    <div className={styles.paletteLabel}>{item.label}</div>
                                    <div className={styles.paletteHint}>{item.hint}</div>
                                </div>
                            </div>
                        ))}
                        <div className={styles.paletteFooter}>
                            拖到画布添加 · Del 删除 · Ctrl+Z 撤销
                        </div>
                    </div>
                )}
            </div>
        );
    },
);

FlowDesigner.displayName = 'FlowDesigner';

export default FlowDesigner;
