/**
 * 基座 UI 桶（@yuku123/z-frontend-common/ui）的类型声明。
 *
 * 【为什么手写而不是等基座出 d.ts】
 * 基座是纯 JS 包（dist 里只有 js，没有 .d.ts），exports 的 './ui' 子路径也没配 types，
 * TS 直接 import 会报 TS7016 "Could not find a declaration file"。本仓页面是 .tsx，
 * 所以只能手写一份最小声明。
 *
 * 【维护约定】
 * 这里只声明本仓真正用到的组件；**props 以基座源码 src/components/ui/*.jsx 为准**，
 * 基座升版改了签名要回来同步。
 */
declare module '@yuku123/z-frontend-common/ui' {
  import type {ReactElement} from 'react'

  /** 组件文档页：左侧 240px TOC + 右侧正文（lead 008 §7）。 */
  export function ComponentDoc(props: {
    markdown: string
    title?: string
    tocWidth?: number
    tocLevels?: number[]
    height?: string
  }): ReactElement
}

declare module '@yuku123/z-frontend-common/ui/*' {
  const component: any
  export default component
}

/**
 * vite `?raw` 导入声明 —— 编译期把文件内容内联成字符串，不是 fetch。
 * DOC.md 就是这么进包的。
 */
declare module '*.md?raw' {
  const content: string
  export default content
}
