import {
    CircleNode,
    CircleNodeModel,
    DiamondNode,
    DiamondNodeModel,
    RectNode,
    RectNodeModel,
} from '@logicflow/core';

// LogicFlow 2.x：view 必须是内置视图类（它从 this.props.model 取坐标），
// 自定义只做在 Model 上（配色/文字色）。1.x 的 this.getAttributes() 写法在 2.x 会直接崩。

class StartNodeModel extends RectNodeModel {
    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#52c41a';
        style.stroke = '#52c41a';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#fff';
        style.fontSize = 14;
        return style;
    }
}

class ApprovalNodeModel extends RectNodeModel {
    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#fff';
        style.stroke = '#1677ff';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 14;
        return style;
    }
}

class ConditionNodeModel extends DiamondNodeModel {
    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#fff7e6';
        style.stroke = '#fa8c16';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 13;
        return style;
    }
}

class CopyNodeModel extends RectNodeModel {
    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#f6ffed';
        style.stroke = '#52c41a';
        style.strokeWidth = 2;
        style.strokeDasharray = '5,5';
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 14;
        return style;
    }
}

class EndNodeModel extends CircleNodeModel {
    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#ff4d4f';
        style.stroke = '#ff4d4f';
        style.strokeWidth = 2;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#fff';
        style.fontSize = 14;
        return style;
    }
}

// 注册所有节点
export function registerApprovalNodes(lf: any): void {
    lf.register({type: 'start', view: RectNode, model: StartNodeModel});
    lf.register({type: 'approval', view: RectNode, model: ApprovalNodeModel});
    lf.register({type: 'condition', view: DiamondNode, model: ConditionNodeModel});
    lf.register({type: 'copy', view: RectNode, model: CopyNodeModel});
    lf.register({type: 'end', view: CircleNode, model: EndNodeModel});
}
