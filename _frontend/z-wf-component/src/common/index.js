import axios from 'axios'

/**
 * z-graph-component 内置最小 request helper。
 *
 * 库件以 baseURL 为空起步：调用方（suit / 主壳 domainRoutes）启动时调一次
 * `configureGraph('/api' 或其他)` 把所有 graphApi 相对路径拼到同一前缀，
 * 与 vite proxy / nginx 反代 / 宿主后端同源。
 *
 * 不要写死 baseURL —— z-graph 后端（GraphControlServer，JDK HttpServer）
 * 在不同部署里可能在 8090 端口直连、也可能在 z-opc 后端 /api/graph/** 后面
 * 通过 GraphProxyController 转一道，宿主自己挑。
 */
export const request = axios.create({
    baseURL: '',
    timeout: 30000,
    headers: { Accept: 'application/json' },
})

request.interceptors.response.use(
    (r) => r,
    (err) => {
        const data = err?.response?.data
        if (data && typeof data === 'object' && (data.message || data.error)) {
            err.message = `${data.message || data.error}${data.source ? ' (' + data.source + ')' : ''}`
        }
        return Promise.reject(err)
    }
)

export default request