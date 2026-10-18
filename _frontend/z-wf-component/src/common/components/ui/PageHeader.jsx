import {Breadcrumb} from 'antd'
import {useNavigate} from 'react-router-dom'

/**
 * 通用页面标题（紧凑单行版）。与 z-opc 主壳同名组件对齐：面包屑/标题 +
 * 副标题 + 右侧操作按钮 + 可选返回按钮。详见 z-opc/src/common/components/ui/PageHeader.jsx。
 */
export default function PageHeader({
                                       title,
                                       subtitle,
                                       breadcrumb,
                                       extra,
                                       back,
                                       onBack,
                                   }) {
    const navigate = useNavigate()
    const handleBack = () => {
        if (onBack) onBack()
        else navigate(-1)
    }

    return (
        <div style={{
            marginBottom: 12,
            paddingBottom: 10,
            borderBottom: '1px solid #f1f5f9',
        }}>
            <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                flexWrap: 'wrap',
            }}>
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    minWidth: 0,
                    flexWrap: 'wrap',
                }}>
                    {back && (
                        <button
                            onClick={handleBack}
                            aria-label="返回上一页"
                            style={{
                                border: '1px solid #e2e8f0',
                                background: '#fff',
                                borderRadius: 6,
                                padding: '4px 10px',
                                cursor: 'pointer',
                                fontSize: 12,
                                color: '#475569',
                            }}
                        >← 返回</button>
                    )}
                    {breadcrumb && breadcrumb.length > 0 ? (
                        <Breadcrumb
                            items={breadcrumb.map((b) => ({
                                title: b.href ? <a onClick={(e) => { e.preventDefault(); b.onClick ? b.onClick() : navigate(b.href) }} href={b.href}>{b.label}</a> : b.label,
                            }))}
                        />
                    ) : (
                        title && <h2 style={{margin: 0, fontSize: 18, color: '#0f172a'}}>{title}</h2>
                    )}
                    {subtitle && (
                        <span style={{color: '#94a3b8', fontSize: 12, maxWidth: 480, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}
                              title={subtitle}>
                            {subtitle}
                        </span>
                    )}
                </div>
                {extra && <div style={{display: 'flex', gap: 8}}>{extra}</div>}
            </div>
        </div>
    )
}