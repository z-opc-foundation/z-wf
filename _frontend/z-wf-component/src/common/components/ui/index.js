/**
 * z-wf 本地 UI 变体桶 —— 只保留**与共享基座有实质差异**的组件。
 *
 * 为什么要留这个桶：
 *   共享基座 '@yuku123/z-frontend-common/ui' 是全库唯一真源。凡是与基座
 *   逐字相同的文件（ErrorState / tokens）本仓一律不留副本，直接转基座，
 *   否则同一组件会有两份拷贝，改一处漏一处。
 *
 * 为什么 EmptyState / PageHeader 还在本地：
 *   这两个文件与基座**不是逐字相同**，而是本地有意保留的老口径视觉变体
 *   （见下方两处注释里的差异清单）。基座没有这些差异，删掉就等于丢功能，
 *   所以按「保留本地差异」的口径留在本仓，只由本桶导出。
 *
 * 用法：需要基座口径的组件一律
 *   import {ErrorState, PageHeader} from '@yuku123/z-frontend-common/ui'
 */

// 与基座逐字相同 ⇒ 转基座，仓内不再留副本
export {ErrorState} from '@yuku123/z-frontend-common/ui'
// 设计令牌与基座逐字相同 ⇒ 转基座，保证全库同一套色板/圆角/阴影
export {brand, palette, neutral, radius, shadow, lift, space, fontSize, antdTheme} from '@yuku123/z-frontend-common/ui'

// 本地变体（与基座有实质差异，保留）
// EmptyState：基座用 tokens 取色 + 渐变主按钮 + titleSize 三档；本地是老口径
//             （内联硬编码色值、普通主按钮、标题固定字重），两者视觉不同。
export {default as EmptyState} from './EmptyState'
// PageHeader：基座返回按钮是纯图标 + 面包屑末级加粗；本地是「← 返回」文字按钮，
//             且面包屑项支持 onClick 回调（基座只支持 href 跳转）。
export {default as PageHeader} from './PageHeader'
