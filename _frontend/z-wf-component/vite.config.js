import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
    // library mode 的 rollup 不读 tsconfig paths，@/ 必须在 resolve.alias 里再声明一次
    resolve: {
        alias: {'@': path.resolve(__dirname, 'src')},
        dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
    },
    build: {
        lib: {entry: {index: path.resolve(__dirname, 'src/index.js'), pages: path.resolve(__dirname, 'src/pages.js')}, formats: ['es']},
        outDir: 'dist', emptyOutDir: false,
        rollupOptions: {
            // rollup 的 external 是精确字符串匹配，不是前缀匹配：只写主桶的话，
            // 源码里的 '@yuku123/z-frontend-common/ui' 会被当普通依赖打包，基座 UI 组件
            // 被复制进本包 dist（打破「共享基座单一真源」+ 包体膨胀）。子路径须逐个列出。
            external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom', 'antd', '@ant-design/icons', 'axios', '@yuku123/z-frontend-common', '@yuku123/z-frontend-common/ui', '@yuku123/z-frontend-common/ui/echarts', 'dayjs'],
        },
    },
})
