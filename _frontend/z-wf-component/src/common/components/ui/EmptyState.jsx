import {Button} from 'antd'
import {InboxOutlined} from '@ant-design/icons'

const neutral = {
    surfaceAlt: '#f8fafc',
    textSecondary: '#64748b',
    border: '#e5e7eb',
}
const radius = {md: 8}

/**
 * 统一空状态 - 来自 z-opc 主壳的 ui primitives。
 * 这里抄一份是因为 z-graph 仓是 z-graph-component 的「单仓真源」，
 * 不能让组件层依赖 z-opc 主壳的路径别名（之前会带 build 炸）。
 *
 * props 兼容主壳同名组件，调用方无感切换。
 */
export default function EmptyState({
                                       icon,
                                       title = '暂无数据',
                                       description,
                                       actionText,
                                       onAction,
                                       size = 'md',
                                       style,
                                   }) {
    const padMap = {sm: 24, md: 40, lg: 64}
    const titleSize = {sm: 14, md: 16, lg: 20}

    return (
        <div
            role="status"
            aria-live="polite"
            style={{
                padding: padMap[size] || padMap.md,
                textAlign: 'center',
                background: '#ffffff',
                borderRadius: radius.md,
                border: '1px dashed ' + neutral.border,
                ...style,
            }}
        >
            <div style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 56, height: 56,
                borderRadius: '50%',
                background: neutral.surfaceAlt,
                marginBottom: 16,
                fontSize: 28,
                color: '#9ca3af',
            }}>
                {icon || <InboxOutlined/>}
            </div>
            <div style={{fontSize: titleSize[size] || titleSize.md, color: '#0f172a', fontWeight: 500, marginBottom: 4}}>
                {title}
            </div>
            {description && (
                <div style={{color: neutral.textSecondary, fontSize: 13, maxWidth: 360, margin: '0 auto 16px'}}>
                    {description}
                </div>
            )}
            {actionText && onAction && (
                <Button type="primary" onClick={onAction}>{actionText}</Button>
            )}
        </div>
    )
}