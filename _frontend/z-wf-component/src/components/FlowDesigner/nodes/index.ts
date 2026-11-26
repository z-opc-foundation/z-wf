/**
 * 审批流节点注册（LogicFlow 2.x 正确写法）。
 *
 * ⚠ 2026-11-12 重写：原文件是 1.x 写法 —— register({view, model: RectNode.model})，
 * 而 2.x 的视图类没有 `model` 静态属性，注册进去的 model 是 undefined，
 * render 时直接抛「找不到start对应的节点」，整个设计器页面炸到错误边界。
 * 2.x 必须为每个 type 配一对 Model（继承 *NodeModel，管形状/样式数据）+ View（继承 *Node，管渲染）。
 */
import {CircleNode, CircleNodeModel, PolygonNode, PolygonNodeModel, RectNode, RectNodeModel} from '@logicflow/core';

// —— 开始节点：绿色圆角胶囊 ——
class StartModel extends RectNodeModel {
    initNodeData(data: any) {
        super.initNodeData(data);
        this.width = 88;
        this.height = 36;
        this.radius = 18;
    }

    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#52c41a';
        style.stroke = '#3f9b12';
        style.strokeWidth = 1;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#fff';
        style.fontSize = 13;
        return style;
    }
}

class StartView extends RectNode {}

// —— 审批节点：白底蓝边圆角卡片 ——
class ApprovalModel extends RectNodeModel {
    initNodeData(data: any) {
        super.initNodeData(data);
        this.width = 150;
        this.height = 52;
        this.radius = 8;
    }

    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#fff';
        style.stroke = '#1677ff';
        style.strokeWidth = 1.5;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#262626';
        style.fontSize = 13;
        return style;
    }
}

class ApprovalView extends RectNode {}

// —— 条件分支：橙色菱形（PolygonNodeModel 默认 points 即菱形） ——
class ConditionModel extends PolygonNodeModel {
    initNodeData(data: any) {
        super.initNodeData(data);
        this.width = 160;
        this.height = 80;
    }

    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#fff7e6';
        style.stroke = '#fa8c16';
        style.strokeWidth = 1.5;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#874d00';
        style.fontSize = 12;
        return style;
    }
}

class ConditionView extends PolygonNode {}

// —— 抄送节点：灰底虚线 ——
class CopyModel extends RectNodeModel {
    initNodeData(data: any) {
        super.initNodeData(data);
        this.width = 140;
        this.height = 44;
        this.radius = 6;
    }

    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#fafafa';
        style.stroke = '#8c8c8c';
        style.strokeWidth = 1.2;
        style.strokeDasharray = '5,3';
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#595959';
        style.fontSize = 12;
        return style;
    }
}

class CopyView extends RectNode {}

// —— 结束节点：红色圆形 ——
class EndModel extends CircleNodeModel {
    initNodeData(data: any) {
        super.initNodeData(data);
        // CircleNodeModel 的 width/height 是由 r 派生的 computed，只能设 r
        this.r = 24;
    }

    getNodeStyle() {
        const style = super.getNodeStyle();
        style.fill = '#ff4d4f';
        style.stroke = '#d9363e';
        style.strokeWidth = 1;
        return style;
    }

    getTextStyle() {
        const style = super.getTextStyle();
        style.color = '#fff';
        style.fontSize = 13;
        return style;
    }
}

class EndView extends CircleNode {}

export function registerApprovalNodes(lf: any): void {
    lf.batchRegister([
        {type: 'start', view: StartView, model: StartModel},
        {type: 'approval', view: ApprovalView, model: ApprovalModel},
        {type: 'condition', view: ConditionView, model: ConditionModel},
        {type: 'copy', view: CopyView, model: CopyModel},
        {type: 'end', view: EndView, model: EndModel},
    ]);
}
