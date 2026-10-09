import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
    plugins: [react()],
    build: {
        lib: {entry: {index: path.resolve(__dirname, 'src/index.js'), pages: path.resolve(__dirname, 'src/pages.js')}, formats: ['es']},
        outDir: 'dist', emptyOutDir: true,
        rollupOptions: {
            external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom', 'antd', '@ant-design/icons', 'axios', '@yuku123/z-frontend-common', 'dayjs'],
        },
    },
})
