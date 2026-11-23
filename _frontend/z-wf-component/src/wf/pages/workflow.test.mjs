import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync, existsSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import path from 'node:path'
import vm from 'node:vm'
import {createRequire} from 'node:module'
import React from 'react'
import {matchRoutes, MemoryRouter} from 'react-router-dom'
import {renderToStaticMarkup} from 'react-dom/server'
import {useWfBase} from '../routing.js'

// 无新增依赖：执行实际 TSX 页面与服务代码，替换网络、UI 和 hooks。
// 这是事件/状态单元测试，不模拟 DOM、AntD 校验或浏览器布局。
const require = createRequire(import.meta.url)
const {transformSync} = createRequire(require.resolve('vite/package.json'))('esbuild')
const src = fileURLToPath(new URL('../../', import.meta.url))
const deferred = () => {
    let resolve, reject
    const promise = new Promise((yes, no) => { resolve = yes; reject = no })
    return {promise, resolve, reject}
}
const tick = () => new Promise(resolve => setImmediate(resolve))

function fixture() {
    const env = {
        pathname: '/z-wf/workflow/task/task-1', params: {taskId: 'task-1'},
        calls: [], notices: [], navigations: [], user: {userId: 'operator-1'},
        response: async (method, url) => {
            if (url.endsWith('/tasks/get')) return {taskId: 'task-1', processInstanceId: 'instance-1', status: 'pending', assignee: 'not-the-login-user'}
            if (url.endsWith('/trail')) return []
            if (url.endsWith('/definitions')) return [{id: 'definition-1', key: 'process-key', name: '流程', version: 2}]
            if (url.endsWith('/graph')) return {graph: {nodes: [{id: 'node-1'}], edges: []}}
            if (url.endsWith('/dashboard')) return {todoList: [{taskId: 'task/1'}]}
            return {success: true, data: {}}
        },
    }
    const client = {defaults: {}}
    for (const method of ['get', 'post', 'put', 'delete']) {
        client[method] = async (url, ...args) => {
            env.calls.push({method, url, args})
            return {data: await env.response(method, url, ...args)}
        }
    }
    env.client = client
    let current
    const hooks = {
        ...React,
        useState(initial) {
            const owner = current, index = owner.cursor++
            if (!(index in owner.slots)) owner.slots[index] = typeof initial === 'function' ? initial() : initial
            return [owner.slots[index], value => { owner.slots[index] = typeof value === 'function' ? value(owner.slots[index]) : value }]
        },
        useRef(initial) {
            const owner = current, index = owner.cursor++
            return owner.slots[index] ??= {current: initial}
        },
        useMemo(fn) { return fn() },
        useEffect(fn, deps) {
            const owner = current, index = owner.cursor++, previous = owner.slots[index]
            if (!previous || deps.some((value, i) => value !== previous.deps[i])) {
                previous?.cleanup?.()
                const effect = {deps}
                owner.slots[index] = effect
                owner.effects.push(() => { effect.cleanup = fn() })
            }
        },
    }
    const component = name => Object.assign(() => null, {displayName: name})
    const ui = Object.fromEntries(['Button', 'Card', 'Form', 'Input', 'Modal', 'Select', 'Radio', 'Typography', 'Descriptions', 'Timeline', 'List', 'Steps', 'Spin', 'Popconfirm', 'Table'].map(name => [name, component(name)]))
    for (const [name, children] of Object.entries({Form: ['Item'], Input: ['TextArea'], Select: ['Option'], Radio: ['Group', 'Button'], Typography: ['Title', 'Text'], Descriptions: ['Item'], Timeline: ['Item'], List: ['Item'], Steps: ['Step']})) {
        for (const child of children) ui[name][child] = component(`${name}.${child}`)
    }
    const form = {setFieldsValue() {}, resetFields() {}}
    ui.Form.useForm = () => [form]
    ui.message = Object.fromEntries(['success', 'error', 'warning'].map(kind => [kind, text => env.notices.push({kind, text})]))
    env.ui = ui
    const cache = new Map()
    env.load = relative => load(path.resolve(src, relative))
    function load(filename) {
        filename = [filename, `${filename}.js`, `${filename}.jsx`, `${filename}.tsx`, path.join(filename, 'index.tsx')].find(existsSyncAndFile) || filename
        if (cache.has(filename)) return cache.get(filename).exports
        if (filename.endsWith('.css')) return {}
        if (filename.includes('/components/FlowDesigner/') || filename.endsWith('/components/FlowDesigner')) return {default: 'FlowDesigner', __esModule: true}
        if (/\/pages\/(HomePage|LoginPage|ComponentDoc)\./.test(filename)) return {default: component(path.basename(filename)), __esModule: true}
        const module = {exports: {}}
        cache.set(filename, module)
        const code = transformSync(readFileSync(filename, 'utf8'), {loader: /\.tsx$/.test(filename) ? 'tsx' : 'jsx', format: 'cjs', jsx: 'automatic'}).code
        const mockRequire = id => {
            if (id === 'react') return hooks
            if (id === 'react-router-dom') return {useLocation: () => ({pathname: env.pathname}), useParams: () => env.params, useNavigate: () => value => env.navigations.push(value), Link: 'Link', Routes: 'Routes', Route: 'Route', Navigate: 'Navigate'}
            if (id === 'antd') return new Proxy(ui, {get: (target, key) => target[key] ?? key})
            if (id === '@ant-design/icons') return new Proxy({}, {get: (_, key) => key})
            if (id === '@yuku123/z-frontend-common') return {createRequest: () => client}
            if (id === '@yuku123/z-frontend-common/ui') return {ListTable: 'ListTable', ErrorState: 'ErrorState', EmptyState: 'EmptyState', PageHeader: 'PageHeader'}
            if (id.startsWith('.')) return load(path.resolve(path.dirname(filename), id))
            return require(id)
        }
        const context = {module, exports: module.exports, require: mockRequire, console: {error() {}}, localStorage: {getItem: () => JSON.stringify(env.user)}}
        vm.runInNewContext(code, context, {filename})
        return module.exports
    }
    function existsSyncAndFile(file) { return existsSync(file) && /\.(js|jsx|tsx|css)$/.test(file) }
    env.mount = Component => {
        const owner = {slots: [], effects: [], cursor: 0}
        return {
            render() { current = owner; owner.cursor = 0; return Component() },
            async settle() { for (const effect of owner.effects.splice(0)) effect(); await tick() },
        }
    }
    return env
}

function findAll(tree, predicate) {
    if (Array.isArray(tree)) return tree.flatMap(child => findAll(child, predicate))
    if (!React.isValidElement(tree)) return []
    return [...(predicate(tree) ? [tree] : []), ...findAll(tree.props.children, predicate)]
}
const find = (tree, type) => {
    const item = findAll(tree, element => element.type === type)[0]
    assert.ok(item, `missing ${type.displayName || type}`)
    return item
}
const button = (tree, label) => {
    const item = findAll(tree, element => element.type?.displayName === 'Button' && element.props.children === label)[0]
    assert.ok(item, `missing button ${label}`)
    return item
}
const plain = value => JSON.parse(JSON.stringify(value))
const transferCalls = env => env.calls.filter(call => call.url === '/wf/task/transfer')

async function taskPage() {
    const env = fixture()
    const page = env.mount(env.load('wf/pages/TaskDetail/index.tsx').default)
    page.render()
    await page.settle()
    button(page.render(), '转交').props.onClick()
    const fill = (label, value) => {
        const input = findAll(page.render(), element => element.props['aria-label'] === label)[0]
        input.props.onChange({target: {value}})
    }
    return {env, page, fill, modal: () => find(page.render(), env.ui.Modal)}
}

test('manifest and embedded router both register :id and use wf/pages', () => {
    const env = fixture()
    const {routes} = env.load('pages-manifest.jsx')
    for (const [suffix, name] of [['todo', 'TodoList'], ['designer/:id', 'ProcessDesigner'], ['task/:taskId', 'TaskDetail']]) {
        assert.equal(routes.find(route => route.path === `/z-wf/workflow/${suffix}`).Component, env.load(`wf/pages/${name}/index.tsx`).default)
    }
    const matched = matchRoutes(routes, '/z-wf/workflow/designer/definition-1')
    assert.equal(matched.at(-1).params.id, 'definition-1')
    const embedded = env.load('wf/pages/WorkflowApp.jsx').default()
    assert.ok(findAll(embedded, element => element.type === 'Route' && element.props.path === 'designer/:id').length)
})

test('route roots follow standalone, host and explicit configureWf roots', () => {
    const env = fixture()
    const {resolveWfBase} = env.load('wf/routing.js')
    for (const root of ['/z-wf/workflow', '/workflow', '/host/approval', '']) {
        for (const suffix of ['todo', 'done', 'dashboard', 'processes', 'my-processes', 'task/a%2Fb', 'designer/a', 'process/a']) {
            assert.equal(resolveWfBase(`${root}/${suffix}/`), root)
        }
    }
    const {configureWf} = env.load('wf/services/api.js')
    configureWf({wfBase: '/tenant/reviews/', apiBase: '/api'})
    assert.equal(resolveWfBase('/workflow/todo'), '/tenant/reviews')
    assert.equal(env.client.defaults.baseURL, '/api')
    configureWf({wfBase: '/'})
    assert.equal(resolveWfBase('/workflow/todo'), '')
    configureWf({wfBase: undefined})
    assert.equal(resolveWfBase('/workflow/todo'), '/workflow')
})

test('real router location preserves host mount and respects BrowserRouter basename semantics', () => {
    function Probe() { return React.createElement('span', null, useWfBase()) }
    for (const root of ['/z-wf/workflow', '/workflow', '/host/approval']) {
        const html = renderToStaticMarkup(React.createElement(MemoryRouter, {
            basename: '/shell', initialEntries: [`/shell${root}/task/task-1`],
        }, React.createElement(Probe)))
        assert.equal(html, `<span>${root}</span>`)
    }
})

test('list actions, dashboard links and detail returns stay under their mount', async () => {
    for (const root of ['/z-wf/workflow', '/workflow', '/host/approval']) {
        for (const [name, suffix, record, expected] of [
            ['TodoList', 'todo', {taskId: 'task/1'}, 'task/task%2F1'],
            ['DoneList', 'done', {taskId: 'task/1'}, 'task/task%2F1'],
            ['MyProcesses', 'my-processes', {processInstanceId: 'instance/1'}, 'process/instance%2F1'],
            ['ProcessList', 'processes', {id: 'definition/1'}, 'designer/definition%2F1'],
        ]) {
            const env = fixture()
            env.pathname = `${root}/${suffix}`
            const page = env.mount(env.load(`wf/pages/${name}/index.tsx`).default)
            const table = find(page.render(), name === 'ProcessList' ? env.ui.Table : 'ListTable')
            const actions = table.props.columns.find(column => column.title === '操作').render(null, record)
            find(actions, env.ui.Button).props.onClick()
            assert.equal(env.navigations.at(-1), `${root}/${expected}`)
        }
        const env = fixture()
        env.pathname = `${root}/dashboard`
        const page = env.mount(env.load('wf/pages/Dashboard/index.tsx').default)
        page.render(); await page.settle()
        const tree = page.render()
        assert.deepEqual(findAll(tree, element => element.type === 'Link').map(link => link.props.to), [`${root}/todo`, `${root}/done`, `${root}/my-processes`])
        const list = find(tree, env.ui.List)
        list.props.renderItem({taskId: 'task/1'}).props.actions[0].props.onClick()
        assert.equal(env.navigations.at(-1), `${root}/task/task%2F1`)
        for (const [name, suffix, destination] of [['TaskDetail', 'task/t1', 'todo'], ['ProcessDetail', 'process/p1', 'my-processes'], ['ProcessDesigner', 'designer/d1', 'processes']]) {
            env.pathname = `${root}/${suffix}`
            const detail = env.mount(env.load(`wf/pages/${name}/index.tsx`).default)
            button(detail.render(), '返回').props.onClick()
            assert.equal(env.navigations.at(-1), `${root}/${destination}`)
        }
    }
})

test('designer resolves :id to definition key, then loads graph and enables saving', async () => {
    const env = fixture()
    env.pathname = '/host/approval/designer/definition-1'
    env.params = {id: 'definition-1'}
    const page = env.mount(env.load('wf/pages/ProcessDesigner/index.tsx').default)
    assert.equal(button(page.render(), '保存').props.disabled, true)
    await page.settle()
    const tree = page.render()
    const graphCall = env.calls.find(call => call.url.endsWith('/graph'))
    assert.deepEqual(plain(graphCall.args), [{params: {processKey: 'process-key'}}])
    assert.equal(find(tree, 'FlowDesigner').props.initialData.nodes[0].id, 'node-1')
    assert.equal(find(tree, 'FlowDesigner').props.readOnly, false)
    assert.equal(button(tree, '保存').props.disabled, false)
})

test('designer missing definition or failed graph never enables blank save', async () => {
    for (const missing of [true, false]) {
        const env = fixture(), respond = env.response
        env.params = {id: missing ? 'missing' : 'definition-1'}
        env.response = (method, url) => url.endsWith('/graph') ? Promise.reject(new Error('graph failed')) : respond(method, url)
        const page = env.mount(env.load('wf/pages/ProcessDesigner/index.tsx').default)
        page.render(); await page.settle()
        const tree = page.render()
        assert.equal(button(tree, '保存').props.disabled, true)
        assert.equal(button(tree, '部署').props.disabled, true)
        assert.equal(find(tree, 'FlowDesigner').props.initialData, null)
        assert.equal(env.calls.filter(call => call.url.endsWith('/graph')).length, missing ? 0 : 1)
    }
})

test('designer ignores stale graph when route id changes', async () => {
    const env = fixture(), oldGraph = deferred()
    env.params = {id: 'old'}
    env.response = async (method, url, config) => url.endsWith('/definitions')
        ? [{id: 'old', key: 'old-key'}, {id: 'new', key: 'new-key'}]
        : config.params.processKey === 'old-key' ? oldGraph.promise : {nodes: [{id: 'new-node'}], edges: []}
    const page = env.mount(env.load('wf/pages/ProcessDesigner/index.tsx').default)
    page.render(); await page.settle()
    env.params = {id: 'new'}
    page.render(); await page.settle()
    oldGraph.resolve({nodes: [{id: 'old-node'}], edges: []})
    await tick()
    assert.equal(find(page.render(), 'FlowDesigner').props.initialData.nodes[0].id, 'new-node')
})

test('transfer validates whitespace and refuses to invent an operator identity', async () => {
    const {env, fill, modal} = await taskPage()
    fill('目标用户 ID', '   ')
    assert.equal(modal().props.okButtonProps.disabled, true)
    await modal().props.onOk()
    assert.equal(transferCalls(env).length, 0)
    fill('目标用户 ID', 'real-user')
    env.user = {name: 'Display name only'}
    await modal().props.onOk()
    assert.equal(transferCalls(env).length, 0)
    assert.equal(modal().props.open, true)
    assert.ok(env.notices.some(notice => notice.text.includes('当前登录用户')))
})

test('transfer waits for server, locks duplicate clicks/cancel, then closes and refreshes', async () => {
    const {env, page, fill, modal} = await taskPage(), request = deferred(), respond = env.response
    env.response = (method, url, ...args) => url.endsWith('/transfer') ? request.promise : respond(method, url, ...args)
    fill('目标用户 ID', ' real-user ')
    fill('转交说明', ' please review ')
    const onOk = modal().props.onOk
    const pending = onOk()
    await onOk()
    assert.equal(transferCalls(env).length, 1)
    assert.deepEqual(plain(transferCalls(env)[0]), {method: 'post', url: '/wf/task/transfer', args: [{taskId: 'task-1', userId: 'operator-1', targetUserId: 'real-user', comment: 'please review'}, null]})
    assert.equal(env.notices.filter(notice => notice.kind === 'success').length, 0)
    assert.equal(modal().props.confirmLoading, true)
    modal().props.onCancel()
    assert.equal(modal().props.open, true)
    assert.equal(findAll(page.render(), element => element.props['aria-label'] === '目标用户 ID')[0].props.disabled, true)
    assert.equal(env.calls.filter(call => call.url.endsWith('/tasks/get')).length, 1)
    request.resolve({success: true, data: {assignee: 'real-user'}})
    await pending
    assert.equal(modal().props.open, false)
    assert.equal(modal().props.confirmLoading, false)
    assert.equal(env.notices.filter(notice => notice.kind === 'success').length, 1)
    assert.equal(env.calls.filter(call => call.url.endsWith('/tasks/get')).length, 2)
    assert.equal(env.calls.filter(call => call.url.endsWith('/trail')).length, 2)
})

test('HTTP and business failures preserve modal/input, release pending and allow retry', async () => {
    for (const businessFailure of [false, true]) {
        const {env, page, fill, modal} = await taskPage(), respond = env.response
        env.response = (method, url, ...args) => url.endsWith('/transfer')
            ? businessFailure ? Promise.resolve({success: false, code: 400, message: '拒绝转交'}) : Promise.reject(new Error('network failed'))
            : respond(method, url, ...args)
        fill('目标用户 ID', 'real-user')
        fill('转交说明', 'keep this comment')
        await modal().props.onOk()
        assert.equal(modal().props.open, true)
        assert.equal(modal().props.confirmLoading, false)
        assert.equal(findAll(page.render(), element => element.props['aria-label'] === '目标用户 ID')[0].props.value, 'real-user')
        assert.equal(findAll(page.render(), element => element.props['aria-label'] === '转交说明')[0].props.value, 'keep this comment')
        assert.equal(env.notices.filter(notice => notice.kind === 'success').length, 0)
        assert.equal(env.calls.filter(call => call.url.endsWith('/tasks/get')).length, 1)
        env.response = respond
        await modal().props.onOk()
        assert.equal(transferCalls(env).length, 2)
        assert.equal(modal().props.open, false)
    }
})
