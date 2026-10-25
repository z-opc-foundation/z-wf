import React, {forwardRef, useCallback, useEffect, useImperativeHandle, useRef} from 'react';
import styles from './index.module.css';
import {registerApprovalNodes} from './nodes';
import LogicFlow from '@logicflow/core';
import {Control, DndPanel, Menu, MiniMap, SelectionSelect, Snapshot} from '@logicflow/extension';

// 画板图标自带：LogicFlow 官方示例图挂在 jsdelivr，内网/离线环境一律加载失败。
const svgIcon = (svg: string) => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;

const nodeTypes = [
    {
        type: 'start',
        label: '开始节点',
        icon: svgIcon('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect x="4" y="10" width="24" height="12" rx="6" fill="#52c41a"/></svg>'),
    },
    {
        type: 'approval',
        label: '审批节点',
        icon: svgIcon('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect x="4" y="8" width="24" height="16" rx="3" fill="#fff" stroke="#1677ff" stroke-width="2"/><rect x="8" y="13" width="16" height="2" rx="1" fill="#1677ff"/><rect x="8" y="18" width="10" height="2" rx="1" fill="#1677ff"/></svg>'),
    },
    {
        type: 'condition',
        label: '条件分支',
        icon: svgIcon('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><path d="M16 3 L29 16 L16 29 L3 16 Z" fill="#fff7e6" stroke="#fa8c16" stroke-width="2"/></svg>'),
    },
    {
        type: 'copy',
        label: '抄送节点',
        icon: svgIcon('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect x="4" y="8" width="24" height="16" rx="3" fill="#f6ffed" stroke="#52c41a" stroke-width="2" stroke-dasharray="4 3"/></svg>'),
    },
    {
        type: 'end',
        label: '结束节点',
        icon: svgIcon('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><circle cx="16" cy="16" r="12" fill="#ff4d4f"/></svg>'),
    },
];

export interface FlowDesignerRef {
    getGraphData: () => any;
    exportImage: () => Promise<void>;
    undo: () => void;
    redo: () => void;
    zoomIn: () => void;
    zoomOut: () => void;
    deleteSelection: () => void;
}

interface FlowDesignerProps {
    processDefinitionId?: string;
    initialData?: any;
    readOnly?: boolean;
    onNodeClick?: (nodeModel: any) => void;
}

const FlowDesigner = forwardRef<FlowDesignerRef, FlowDesignerProps>(({
                                                                        initialData,
                                                                        readOnly = false,
                                                                        onNodeClick,
                                                                    }, ref) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const lfRef = useRef<any>(null);
    // 回调放 ref：页面每次 setState 都会换一个新函数，进 deps 会导致画布被销毁重建
    const onNodeClickRef = useRef(onNodeClick);
    onNodeClickRef.current = onNodeClick;

    useImperativeHandle(ref, () => ({
        getGraphData: () => lfRef.current?.getGraphData() || null,
        exportImage: async () => {
            const lf = lfRef.current;
            if (!lf) return;
            await lf.extension.snapshot.getSnapshot(`flow-${Date.now()}.png`);
        },
        undo: () => lfRef.current?.undo(),
        redo: () => lfRef.current?.redo(),
        zoomIn: () => lfRef.current?.zoom(true),
        zoomOut: () => lfRef.current?.zoom(false),
        deleteSelection: () => {
            const lf = lfRef.current;
            if (!lf) return;
            const {nodes, edges} = lf.getSelectElements(true) || {};
            edges?.forEach((edge: any) => lf.deleteEdge(edge.id));
            nodes?.forEach((node: any) => lf.deleteNode(node.id));
        },
    }), []);

    const initLogicFlow = useCallback(() => {
        if (!containerRef.current) return;

        LogicFlow.use(Control);
        LogicFlow.use(MiniMap);
        LogicFlow.use(Menu);
        LogicFlow.use(DndPanel);
        LogicFlow.use(SelectionSelect);
        LogicFlow.use(Snapshot);

        lfRef.current?.destroy();

        const lf = new LogicFlow({
            container: containerRef.current,
            grid: {size: 20, visible: true, type: 'dot', config: {color: '#e5e5e5'}},
            keyboard: {enabled: !readOnly},
            snapline: true,
            outline: true,
            textEdit: !readOnly,
            isSilentMode: readOnly,
            style: {rect: {rx: 4, ry: 4}},
        });

        registerApprovalNodes(lf);

        lf.extension.dndPanel.setPatternItems(
            nodeTypes.map(node => ({type: node.type, label: node.label, icon: node.icon, className: styles.dndItem}))
        );

        lf.on('node:click', ({data}: any) => {
            const model = lf.getNodeModelById(data.id);
            if (model) onNodeClickRef.current?.(model);
        });

        lf.render(initialData || {
            nodes: [{id: 'start-1', type: 'start', x: 100, y: 300, text: '开始'}],
            edges: []
        });

        lfRef.current = lf;
    }, [initialData, readOnly]);

    useEffect(() => {
        initLogicFlow();
        return () => {
            lfRef.current?.destroy();
            lfRef.current = null;
        };
    }, [initLogicFlow]);

    return (
        <div className={styles.designerContainer}>
            <div ref={containerRef} className={styles.canvas} style={{height: '70vh'}}/>
        </div>
    );
});

export default FlowDesigner;
