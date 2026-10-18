import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
    plugins: [react()],
    // library mode 的 rollup 不读 tsconfig paths，@/ 必须在 resolve.alias 里再声明一次
    resolve: {
        alias: {'@': path.resolve(__dirname, 'src')},
        dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
    },
    build: {
        lib: {entry: {index: path.resolve(__dirname, 'src/index.js'), pages: path.resolve(__dirname, 'src/pages.js')}, formats: ['es']},
        outDir: 'dist', emptyOutDir: true,
        rollupOptions: {
            external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom', 'antd', '@ant-design/icons', 'axios', '@yuku123/z-frontend-common', 'dayjs'],
        },
    },
})
