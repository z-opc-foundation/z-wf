import {CircleNode, h, PolygonNode, RectNode} from '@logicflow/core';

// 开始节点
class StartNode extends RectNode {
    static extendKey = 'start';

    getNodeStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getNodeStyle();
        style.fill = '#52c41a';
        style.stroke = '#52c41a';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getTextStyle();
        style.color = '#fff';
        style.fontSize = 14;
        return style;
    }

    getShape() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const {x, y, width, height} = this.getAttributes();
        const r = height / 2;
        return h('rect', {
            x: x - width / 2,
            y: y - height / 2,
            width,
            height,
            rx: r,
            ry: r,
        });
    }
}

// 审批节点
class ApprovalNode extends RectNode {
    static extendKey = 'approval';

    getNodeStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getNodeStyle();
        style.fill = '#fff';
        style.stroke = '#1677ff';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 14;
        return style;
    }
}

// 条件节点（菱形）
class ConditionNode extends PolygonNode {
    static extendKey = 'condition';

    getNodeStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getNodeStyle();
        style.fill = '#fff7e6';
        style.stroke = '#fa8c16';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 13;
        return style;
    }

    getAttributes() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const attributes = super.getAttributes();
        const width = Math.max(80, attributes.width || 80);
        const height = Math.max(80, attributes.height || 80);
        const points = [
            [attributes.x, attributes.y - height / 2],
            [attributes.x + width / 2, attributes.y],
            [attributes.x, attributes.y + height / 2],
            [attributes.x - width / 2, attributes.y],
        ];
        return {...attributes, points, width, height};
    }
}

// 抄送节点
class CopyNode extends RectNode {
    static extendKey = 'copy';

    getNodeStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getNodeStyle();
        style.fill = '#f6ffed';
        style.stroke = '#52c41a';
        style.strokeWidth = 2;
        style.strokeDasharray = '5,5';
        return style;
    }

    getTextStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 14;
        return style;
    }
}

// 结束节点
class EndNode extends CircleNode {
    static extendKey = 'end';

    getNodeStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getNodeStyle();
        style.fill = '#ff4d4f';
        style.stroke = '#ff4d4f';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        // @ts-ignore – LogicFlow runtime method not in type defs
        const style = super.getTextStyle();
        style.color = '#fff';
        style.fontSize = 14;
        return style;
    }
}

// 注册所有节点
export function registerApprovalNodes(lf: any): void {
    lf.register({
        type: 'start',
        view: StartNode,
        // @ts-ignore
        model: RectNode.model,
    });
    lf.register({
        type: 'approval',
        view: ApprovalNode,
        // @ts-ignore
        model: RectNode.model,
    });
    lf.register({
        type: 'condition',
        view: ConditionNode,
        // @ts-ignore
        model: PolygonNode.model,
    });
    lf.register({
        type: 'copy',
        view: CopyNode,
        // @ts-ignore
        model: RectNode.model,
    });
    lf.register({
        type: 'end',
        view: EndNode,
        // @ts-ignore
        model: CircleNode.model,
    });
}
