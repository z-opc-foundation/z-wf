import React, {useCallback, useEffect, useRef} from 'react';
import styles from './index.module.css';
import {registerApprovalNodes} from './nodes';
import LogicFlow from '@logicflow/core';
import {Control, DndPanel, Menu, MiniMap, SelectionSelect} from '@logicflow/extension';

const nodeTypes = [
    {
        type: 'start',
        label: '开始节点',
        icon: 'https://cdn.jsdelivr.net/gh/LogicFlow/static@latest/docs/static/start.png'
    },
    {
        type: 'approval',
        label: '审批节点',
        icon: 'https://cdn.jsdelivr.net/gh/LogicFlow/static@latest/docs/static/approve.png'
    },
    {
        type: 'condition',
        label: '条件分支',
        icon: 'https://cdn.jsdelivr.net/gh/LogicFlow/static@latest/docs/static/condition.png'
    },
    {type: 'copy', label: '抄送节点', icon: 'https://cdn.jsdelivr.net/gh/LogicFlow/static@latest/docs/static/copy.png'},
    {type: 'end', label: '结束节点', icon: 'https://cdn.jsdelivr.net/gh/LogicFlow/static@latest/docs/static/end.png'},
];

interface FlowDesignerProps {
    processDefinitionId?: string;
    initialData?: any;
    readOnly?: boolean;
    onSave?: (data: any) => void;
    onDeploy?: () => void;
}

const FlowDesigner: React.FC<FlowDesignerProps> = ({
                                                       processDefinitionId,
                                                       initialData,
                                                       readOnly = false,
                                                       onSave,
                                                       onDeploy,
                                                   }) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const lfRef = useRef<any>(null);

    const initLogicFlow = useCallback(() => {
        if (!containerRef.current) return;

        LogicFlow.use(Control);
        LogicFlow.use(MiniMap);
        LogicFlow.use(Menu);
        LogicFlow.use(DndPanel);
        LogicFlow.use(SelectionSelect);

        const lf = new LogicFlow({
            container: containerRef.current,
            grid: {size: 20, visible: true, type: 'dot', config: {color: '#e5e5e5'}},
            keyboard: {enabled: true},
            snapline: true,
            outline: true,
            textEdit: !readOnly,
            isSilentMode: readOnly,
            style: {rect: {rx: 4, ry: 4}},
        });

        registerApprovalNodes(lf);

        // @ts-ignore
        lf.extension.dndPanel.setPatternItems(
            nodeTypes.map(node => ({type: node.type, label: node.label, icon: node.icon, className: styles.dndItem}))
        );

        lf.render(initialData || {
            nodes: [{id: 'start-1', type: 'start', x: 100, y: 300, text: '开始'}],
            edges: []
        });

        lfRef.current = lf;
    }, [initialData, readOnly]);

    useEffect(() => {
        // 确保CDN资源加载完成
        if (typeof LogicFlow !== 'undefined') {
            initLogicFlow();
        } else {
            const timer = setInterval(() => {
                if (typeof LogicFlow !== 'undefined') {
                    clearInterval(timer);
                    initLogicFlow();
                }
            }, 300);
            return () => clearInterval(timer);
        }
        return () => {
            lfRef.current?.destroy();
            lfRef.current = null;
        };
    }, [initLogicFlow]);

    const getGraphData = useCallback(() => lfRef.current?.getGraphData() || null, []);

    React.useImperativeHandle(
        // @ts-ignore
        (React as any).useRef<any>().current,
        () => ({getGraphData}),
        [getGraphData]
    );

    return (
        <div className={styles.designerContainer}>
            <div ref={containerRef} className={styles.canvas} style={{height: '70vh'}}/>
            <div style={{marginTop: 16, display: 'flex', gap: 12}}>
                <button onClick={() => onSave?.(getGraphData())} style={{
                    padding: '8px 16px',
                    backgroundColor: '#1677ff',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer'
                }}>保存流程
                </button>
                <button onClick={() => onDeploy?.()} style={{
                    padding: '8px 16px',
                    backgroundColor: '#52c41a',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer'
                }}>部署流程
                </button>
            </div>
        </div>
    );
};

export default FlowDesigner;
