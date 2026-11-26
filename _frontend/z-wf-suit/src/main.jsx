import React from 'react'
import ReactDOM from 'react-dom/client'
import {ConfigProvider} from 'antd'
import zhCN from 'antd/locale/zh_CN'
import {antdTheme} from '@yuku123/z-frontend-common/ui'
import App from './App'
import 'antd/dist/reset.css'
import '@yuku123/z-frontend-common/blocks.css'
import '@yuku123/z-wf-component/style.css'
import './cognition.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={antdTheme}>
      <App/>
    </ConfigProvider>
  </React.StrictMode>,
)
