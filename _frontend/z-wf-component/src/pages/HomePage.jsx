import { Card, Col, Row, Space, Tag, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { menuItems } from '../pages-manifest'

const { Title, Paragraph } = Typography

/** 首页（lead 008 §16）—— 登录后着陆页：欢迎语 + 快捷入口卡（菜单动态生成）。 */
export default function HomePage() {
    const navigate = useNavigate()
    const [user, setUser] = useState(null)

    useEffect(() => {
        const raw = localStorage.getItem('userInfo')
        if (raw) { try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) } }
    }, [])

    const cards = menuItems.filter((m) => m.key !== '/z-wf/home')

    return (
        <div style={{ margin: '0 calc(-1 * var(--zfc-page-x))', marginTop: 0, padding: 0, paddingTop: 0, borderRadius: 0, boxShadow: 'none' }}>
            <Card style={{ margin: 0, background: 'var(--zfc-primary)', border: 'none', borderRadius: 8, boxShadow: 'none' }}>
                <Space direction="vertical" size={4} style={{ color: '#fff' }}>
                    <Title level={3} style={{ color: '#fff', margin: 0 }}>
                        欢迎{user?.name ? `，${user.name}` : ''}
                    </Title>
                    <Paragraph style={{ color: 'rgba(255,255,255,0.85)', margin: 0 }}>
                        z-wf 流程中心 管理台
                    </Paragraph>
                    {user?.role && (
                        <Tag style={{ marginTop: 8, background: 'rgba(255,255,255,0.2)', color: '#fff', border: 'none' }}>{user.role}</Tag>
                    )}
                </Space>
            </Card>

            <Row gutter={[0, 0]} style={{ margin: '-1px 0 0 1px' }}>
                {cards.map((m) => (
                    <Col key={m.key} xs={24} sm={12} md={12} lg={8}>
                        <Card hoverable onClick={() => navigate(m.key)} style={{ marginLeft: -1, marginTop: -1, borderTop: '3px solid var(--zfc-primary)', borderRadius: 8, boxShadow: 'none' }}>
                            <Space align="start" size={12}>
                                <div style={{
                                    width: 44, height: 44, borderRadius: 8, flexShrink: 0,
                                    background: 'var(--zfc-primary-soft)', color: 'var(--zfc-primary)',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 20,
                                }}>
                                    {m.icon}
                                </div>
                                <div style={{ minWidth: 0 }}>
                                    <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--zfc-ink)' }}>{m.label}</div>
                                    {m.desc && (
                                        <div style={{ fontSize: 12, color: 'var(--zfc-muted-2)', marginTop: 2 }}>{m.desc}</div>
                                    )}
                                </div>
                            </Space>
                        </Card>
                    </Col>
                ))}
            </Row>
        </div>
    )
}
