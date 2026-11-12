import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import SymbolFilesView from './SymbolFilesView.vue'
import { useAppStore } from '../stores/apps'
import { ApiError } from '../api/http'
import { symbolApi } from '../api/symbolApi'

const routeState = reactive({ params: { appId: 'app-1' }, query: {} as Record<string, string> })

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('../api/symbolApi', () => ({
  symbolApi: {
    list: vi.fn(),
    progress: vi.fn(),
    remove: vi.fn(),
    upload: vi.fn(),
    uploadNative: vi.fn(),
    replace: vi.fn(),
  },
}))

const mockedSymbolApi = vi.mocked(symbolApi)

const currentSymbol = {
  symbolId: 'symbol-1',
  appId: 'app-1',
  type: 'MAPPING' as const, appVersion: '1', mappingScope: 'build' as const,
  abi: null, elfBuildId: null, hasFunctionSymbols: null, hasDebugInfo: null, hasDebugLine: null,
  buildId: 'release-1',
  revision: 2,
  originalFilename: 'mapping.txt',
  sizeBytes: 24,
  sha256: 'a'.repeat(64),
  uploadedBy: 'user-1',
  uploadedAt: '2026-09-19T00:00:00Z',
  updatedAt: '2026-09-19T00:00:00Z',
}

/** 构造第二个应用的列表响应，供应用切换隔离测试使用。 */
const secondAppSymbol = { ...currentSymbol, symbolId: 'symbol-2', appId: 'app-2', buildId: 'release-2' }

/** 创建可由测试主动完成的异步响应。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

/** 构造与文本内容匹配的 File，并为 jsdom 补充标准浏览器的 arrayBuffer。 */
function createFile(content: string, filename: string) {
  const file = new File([content], filename, { type: 'text/plain' })
  Object.defineProperty(file, 'arrayBuffer', {
    configurable: true,
    value: vi.fn().mockResolvedValue(new TextEncoder().encode(content).buffer),
  })
  return file
}

describe('SymbolFilesView', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })
  beforeEach(() => {
    // jsdom 未实现原生 dialog 的显示和关闭，模拟标准浏览器的 open 状态。
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
    setActivePinia(createPinia())
    const apps = useAppStore()
    apps.apps = [{
      appId: 'app-1', name: '演示应用', description: null, packageName: 'com.example.demo', role: 'OWNER',
      createdAt: '2026-09-19T00:00:00Z', updatedAt: '2026-09-19T00:00:00Z',
    }, {
      appId: 'app-2', name: '第二应用', description: null, packageName: 'com.example.other', role: 'ADMIN',
      createdAt: '2026-09-19T00:00:00Z', updatedAt: '2026-09-19T00:00:00Z',
    }]
    routeState.params.appId = 'app-1'
    routeState.query = { appVersion: '1' }
    vi.resetAllMocks()
    mockedSymbolApi.list.mockResolvedValue({ appId: 'app-1', items: [currentSymbol], nextCursor: null })
    mockedSymbolApi.progress.mockResolvedValue({state: 'idle', succeededCount: 0, failedCount: 0, updatedAt: null, errorCategory: null})
    mockedSymbolApi.remove.mockResolvedValue(undefined)
    mockedSymbolApi.upload.mockResolvedValue(currentSymbol)
    mockedSymbolApi.replace.mockResolvedValue({ ...currentSymbol, revision: 3 })
  })

  /** Owner 可以加载列表并按 buildId 重新请求。 */
  it('loads and filters the current app symbols', async () => {
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    expect(wrapper.text()).toContain('release-1')
    expect(wrapper.text()).toContain('user-1')

    await wrapper.get('#symbol-filter-build-id').setValue('release-1')
    await wrapper.get('.symbol-filter-form').trigger('submit')
    await flushPromises()

    expect(mockedSymbolApi.list).toHaveBeenLastCalledWith('app-1', expect.objectContaining({ type: 'MAPPING', appVersion: '1', buildId: 'release-1' }), expect.any(AbortSignal))
  })

  /** 不同摘要先展示当前元数据，确认前不得调用替换接口。 */
  it('requires explicit confirmation before replacing a conflicting mapping', async () => {
    mockedSymbolApi.upload.mockRejectedValue(new ApiError({
      status: 409,
      code: 'SYMBOL_CONFLICT',
      message: 'mapping 已存在',
      details: [currentSymbol],
    }))
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    await wrapper.get('#symbol-build-id').setValue('release-1')
    const file = createFile('new mapping', 'new-mapping.txt')
    Object.defineProperty(window.crypto, 'subtle', {
      configurable: true,
      value: { digest: vi.fn().mockResolvedValue(new Uint8Array(32).buffer) },
    })
    const fileInput = wrapper.get('#symbol-file').element as HTMLInputElement
    Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] })
    await wrapper.get('#symbol-file').trigger('change')
    await wrapper.get('.symbol-upload-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('当前 SHA-256')
    expect(wrapper.text()).toContain('新 SHA-256')
    expect(wrapper.text()).toContain('00'.repeat(32))
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.get('.symbol-conflict-panel .button-primary').trigger('click')
    expect(mockedSymbolApi.replace).not.toHaveBeenCalled()

    vi.mocked(window.confirm).mockReturnValue(true)
    await wrapper.get('.symbol-conflict-panel .button-primary').trigger('click')
    await flushPromises()
    expect(mockedSymbolApi.replace).toHaveBeenCalledWith('app-1', 'symbol-1', 2, file, expect.any(AbortSignal))
  })

  /** 版本冲突必须刷新列表并保留重新确认所需的当前提示。 */
  it('refreshes the list after an optimistic replacement loses a revision race', async () => {
    mockedSymbolApi.upload.mockRejectedValue(new ApiError({
      status: 409,
      code: 'SYMBOL_CONFLICT',
      message: 'mapping 已存在',
      details: [currentSymbol],
    }))
    mockedSymbolApi.replace.mockRejectedValue(new ApiError({
      status: 409,
      code: 'SYMBOL_VERSION_CONFLICT',
      message: 'revision 已变化',
      details: [{ ...currentSymbol, revision: 3 }],
    }))
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    await wrapper.get('#symbol-build-id').setValue('release-1')
    const file = createFile('new mapping', 'new-mapping.txt')
    Object.defineProperty(window.crypto, 'subtle', {
      configurable: true,
      value: { digest: vi.fn().mockResolvedValue(new Uint8Array(32).buffer) },
    })
    const fileInput = wrapper.get('#symbol-file').element as HTMLInputElement
    Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] })
    await wrapper.get('#symbol-file').trigger('change')
    await wrapper.get('.symbol-upload-form').trigger('submit')
    await flushPromises()

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.get('.symbol-conflict-panel .button-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('版本已变化，请刷新列表后重新确认')
    expect(mockedSymbolApi.list.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  /** 普通成员可以查看列表但看不到上传控件。 */
  it('hides upload controls for a viewer', async () => {
    const apps = useAppStore()
    apps.apps[0].role = 'VIEWER'
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    expect(wrapper.find('.symbol-upload-panel').exists()).toBe(false)
  })

  /** 列表权限错误只展示通用 API 提示，不泄漏其他应用内容。 */
  it('shows a safe permission error when the list is rejected', async () => {
    mockedSymbolApi.list.mockRejectedValue(new ApiError({
      status: 403,
      code: 'FORBIDDEN',
      message: '当前用户没有应用访问权限',
    }))
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
      StatusMessage: { template: '<div class="status-error">{{ message }}</div>', props: ['message'] },
    } } })
    await flushPromises()
    expect(wrapper.text()).toContain('当前用户没有应用访问权限')
    expect(wrapper.text()).not.toContain(currentSymbol.sha256)
  })

  /** 空列表展示空态，不显示需要管理员操作的表格行。 */
  it('shows the empty state when no mappings are registered', async () => {
    mockedSymbolApi.list.mockResolvedValue({ appId: 'app-1', items: [], nextCursor: null })
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    expect(wrapper.text()).toContain('当前筛选没有符号文件。')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  /** 多页数据追加显示，续页请求携带 API 返回的不透明游标。 */
  it('appends a cursor page without losing the first page', async () => {
    mockedSymbolApi.list
      .mockResolvedValueOnce({ appId: 'app-1', items: [currentSymbol], nextCursor: 'opaque-cursor' })
      .mockResolvedValueOnce({ appId: 'app-1', items: [secondAppSymbol], nextCursor: null })
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    await wrapper.get('.symbol-pagination').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('release-1')
    expect(wrapper.text()).toContain('release-2')
    expect(mockedSymbolApi.list).toHaveBeenLastCalledWith('app-1', expect.objectContaining({ type: 'MAPPING', appVersion: '1', buildId: undefined, cursor: 'opaque-cursor' }), expect.any(AbortSignal))
  })

  /** 应用切换取消未完成的旧列表请求，迟到响应不能覆盖新应用列表。 */
  it('aborts and isolates a pending list request after switching apps', async () => {
    const oldPage = deferred<{ appId: string; items: typeof currentSymbol[]; nextCursor: null }>()
    const newPage = deferred<{ appId: string; items: typeof currentSymbol[]; nextCursor: null }>()
    mockedSymbolApi.list.mockImplementation((requestedAppId) =>
      requestedAppId === 'app-1' ? oldPage.promise : newPage.promise)
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    const oldSignal = mockedSymbolApi.list.mock.calls[0]?.[2]
    routeState.params.appId = 'app-2'
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    newPage.resolve({ appId: 'app-2', items: [secondAppSymbol], nextCursor: null })
    await flushPromises()
    oldPage.resolve({ appId: 'app-1', items: [currentSymbol], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('release-2')
    expect(wrapper.text()).not.toContain('release-1')
  })

  /** 上传中显示可访问的无百分比进度，避免向用户伪造浏览器无法取得的进度。 */
  it('shows an indeterminate progress state while upload and server validation are pending', async () => {
    const upload = deferred<typeof currentSymbol>()
    mockedSymbolApi.upload.mockReturnValue(upload.promise)
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    await wrapper.get('#symbol-build-id').setValue('release-new')
    const file = createFile('mapping', 'mapping.txt')
    const input = wrapper.get('#symbol-file').element as HTMLInputElement
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    await wrapper.get('#symbol-file').trigger('change')
    await wrapper.get('.symbol-upload-form').trigger('submit')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBeUndefined()
    expect(wrapper.text()).toContain('正在校验并上传')
    upload.resolve(currentSymbol)
    await flushPromises()
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
  })

  /** 应用切换会取消在途上传，并丢弃迟到的成功提示。 */
  it('aborts an upload and ignores its completion after switching apps', async () => {
    const wrapper = mount(SymbolFilesView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    await wrapper.get('#symbol-build-id').setValue('release-new')
    const file = createFile('mapping', 'mapping.txt')
    const input = wrapper.get('#symbol-file').element as HTMLInputElement
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    await wrapper.get('#symbol-file').trigger('change')
    const upload = deferred<typeof currentSymbol>()
    mockedSymbolApi.upload.mockReturnValue(upload.promise)
    await wrapper.get('.symbol-upload-form').trigger('submit')
    const signal = mockedSymbolApi.upload.mock.calls[0]?.[4]
    routeState.params.appId = 'app-2'
    await flushPromises()
    expect(signal?.aborted).toBe(true)
    upload.resolve(currentSymbol)
    await flushPromises()
    expect(wrapper.text()).not.toContain('mapping 上传成功')
  })
  /** 删除必须明确确认 revision；版本冲突不假装成功。 */
  it('confirms revision deletion and refreshes after a conflict', async () => {
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: true } } })
    await flushPromises()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const button = () => wrapper.findAll('button').find(item => item.text() === '删除')!
    await button().trigger('click')
    expect(mockedSymbolApi.remove).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    mockedSymbolApi.remove.mockRejectedValueOnce(new ApiError({status:409, code:'SYMBOL_VERSION_CONFLICT',message:'冲突'}))
    await button().trigger('click'); await flushPromises()
    expect(mockedSymbolApi.remove).toHaveBeenCalledWith('app-1', 'symbol-1', 2, expect.any(AbortSignal))
    expect(wrapper.text()).toContain('版本已变化')
    mockedSymbolApi.list.mockResolvedValue({ appId:'app-1', items:[], nextCursor:null })
    await button().trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('文件已删除并保留审计')
    wrapper.unmount()
  })

  /** 只读角色无删除按钮，单构建轮询有上限并可离页取消。 */
  it('bounds polling and cancels progress on unmount', async () => {
    vi.useFakeTimers()
    useAppStore().apps[0].role = 'VIEWER'
    mockedSymbolApi.progress.mockResolvedValue({state:'running',succeededCount:2,failedCount:1,updatedAt:null,errorCategory:null})
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink:true } } })
    await flushPromises()
    expect(wrapper.findAll('button').some(item=>item.text()==='删除')).toBe(false)
    await wrapper.findAll('button').find(item=>item.text()==='查看进度')!.trigger('click'); await flushPromises()
    await vi.advanceTimersByTimeAsync(310000); await flushPromises()
    expect(mockedSymbolApi.progress).toHaveBeenCalledTimes(60)
    expect(wrapper.text()).toContain('本轮成功确认 2')
    const pending = deferred<Awaited<ReturnType<typeof symbolApi.progress>>>()
    mockedSymbolApi.progress.mockReturnValue(pending.promise)
    await wrapper.findAll('button').find(item=>item.text()==='刷新进度')!.trigger('click')
    const signal = mockedSymbolApi.progress.mock.calls.at(-1)?.[2]
    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
    pending.resolve({state:'idle',succeededCount:99,failedCount:0,updatedAt:null,errorCategory:null})
    await flushPromises()
    expect(mockedSymbolApi.progress).toHaveBeenCalledTimes(61)
  })

  /** 相同 buildId 重传获得新 symbolId，旧请求不能写入新进度。 */
  it('isolates delayed progress after a symbol replacement and app switch', async () => {
    const pending = deferred<Awaited<ReturnType<typeof symbolApi.progress>>>()
    mockedSymbolApi.progress.mockReturnValueOnce(pending.promise)
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink:true } } })
    await flushPromises()
    await wrapper.findAll('button').find(item=>item.text()==='查看进度')!.trigger('click')
    const oldSignal = mockedSymbolApi.progress.mock.calls[0]?.[2]
    mockedSymbolApi.list.mockResolvedValue({appId:'app-1',items:[{...currentSymbol,symbolId:'new-symbol'}],nextCursor:null})
    await wrapper.get('.symbol-filter-form').trigger('submit'); await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    await wrapper.findAll('button').find(item=>item.text()==='查看进度')!.trigger('click'); await flushPromises()
    pending.resolve({state:'running',succeededCount:999,failedCount:0,updatedAt:null,errorCategory:null}); await flushPromises()
    expect(wrapper.text()).not.toContain('999')
    expect(mockedSymbolApi.progress.mock.calls.at(-1)?.[1]).toBe('new-symbol')
    routeState.params.appId='app-2'; await flushPromises()
    expect(wrapper.find('[aria-label="构建还原进度"]').exists()).toBe(false)
    wrapper.unmount()
  })

  /** 管理员可清空预填构建登记版本默认文件。 */
  it('uploads a version default mapping without an application build', async () => {
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: true } } })
    await flushPromises()
    const file = createFile('mapping', 'mapping.txt')
    Object.defineProperty(wrapper.get('#symbol-file').element, 'files', { configurable: true, value: [file] })
    await wrapper.get('#symbol-file').trigger('change')
    await wrapper.get('.symbol-upload-form').trigger('submit')
    await flushPromises()
    expect(mockedSymbolApi.upload).toHaveBeenCalledWith('app-1', '1', null, file, expect.any(AbortSignal))
    wrapper.unmount()
  })

  /** SO 全批确认包含预期不存在项，成功后不轮询 JVM。 */
  it('confirms every native identity and never polls native progress', async () => {
    routeState.query = { type: 'NATIVE_SO', appVersion: '2.0', targetAbi: 'arm64-v8a', targetElfBuildId: 'aabb' }
    const existing = { ...currentSymbol, type: 'NATIVE_SO' as const, appVersion: '2.0', mappingScope: null, buildId: null, abi: 'arm64-v8a', elfBuildId: 'aabb' }
    const entries = [{ abi: 'arm64-v8a', elfBuildId: 'aabb', incomingSha256: 'b'.repeat(64), current: existing },
      { abi: 'x86', elfBuildId: 'ccdd', incomingSha256: 'c'.repeat(64), current: null }]
    mockedSymbolApi.list.mockResolvedValue({ appId: 'app-1', items: [existing], nextCursor: null })
    mockedSymbolApi.uploadNative.mockRejectedValueOnce(new ApiError({ status: 409, code: 'SYMBOL_BATCH_CONFLICT', message: '确认', retryable: false, details: entries }))
      .mockResolvedValueOnce({ items: [existing], createdCount: 1, replacedCount: 1, unchangedCount: 0 })
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: true } } })
    await flushPromises()
    expect(wrapper.find('#symbol-build-id').exists()).toBe(false)
    const file = createFile('zip', 'symbols.zip')
    Object.defineProperty(wrapper.get('#symbol-file').element, 'files', { configurable: true, value: [file] })
    await wrapper.get('#symbol-file').trigger('change')
    await wrapper.get('.symbol-upload-form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('新建（预期不存在）')
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.findAll('button').find(button => button.text() === '确认整批')!.trigger('click'); await flushPromises()
    expect(mockedSymbolApi.uploadNative).toHaveBeenLastCalledWith('app-1', '2.0', file,
      { abi: 'arm64-v8a', elfBuildId: 'aabb' }, [{ abi: 'arm64-v8a', elfBuildId: 'aabb', symbolId: 'symbol-1', revision: 2 },
        { abi: 'x86', elfBuildId: 'ccdd', symbolId: null, revision: null }], expect.any(AbortSignal))
    expect(mockedSymbolApi.progress).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('本期未提供 Native 还原')
    wrapper.unmount()
  })

  /** 类型切换取消在途 SO 请求、目标和旧文件，迟到结果无权改写页面。 */
  it('clears native context and ignores an old upload after switching type', async () => {
    routeState.query = { type: 'NATIVE_SO', appVersion: '2.0', targetAbi: 'arm64-v8a', targetElfBuildId: 'aabb' }
    const pending = deferred<{ items: typeof currentSymbol[]; createdCount: number; replacedCount: number; unchangedCount: number }>()
    mockedSymbolApi.uploadNative.mockReturnValue(pending.promise)
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: true } } })
    await flushPromises()
    Object.defineProperty(wrapper.get('#symbol-file').element, 'files', { configurable: true, value: [createFile('zip', 'symbols.zip')] })
    await wrapper.get('#symbol-file').trigger('change'); await wrapper.get('.symbol-upload-form').trigger('submit')
    const signal = mockedSymbolApi.uploadNative.mock.calls[0]?.[5]
    await wrapper.get('#symbol-tab-MAPPING').trigger('click')
    expect(signal?.aborted).toBe(true)
    expect(wrapper.find('#symbol-build-id').exists()).toBe(true)
    pending.resolve({ items: [], createdCount: 1, replacedCount: 0, unchangedCount: 0 }); await flushPromises()
    expect(wrapper.text()).not.toContain('SO 文件已保存')
    wrapper.unmount()
  })

  /** 列表入口按需打开弹窗，Escape 关闭后不能复用上一份文件。 */
  it('opens upload on demand and clears the file when dismissing the dialog', async () => {
    routeState.query = {}
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: true } } })
    await flushPromises()
    expect(wrapper.get('dialog').attributes('open')).toBeUndefined()
    await wrapper.get('.symbol-upload-trigger').trigger('click')
    await flushPromises()
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    Object.defineProperty(wrapper.get('#symbol-file').element, 'files', { configurable: true, value: [createFile('mapping', 'selected.txt')] })
    await wrapper.get('#symbol-file').trigger('change')
    expect(wrapper.get('.symbol-file-picker').text()).toContain('selected.txt')
    await wrapper.get('dialog').trigger('cancel')
    await flushPromises()
    expect(wrapper.get('dialog').attributes('open')).toBeUndefined()
    await wrapper.get('.symbol-upload-trigger').trigger('click')
    await flushPromises()
    expect(wrapper.get('.symbol-file-picker').text()).not.toContain('selected.txt')
    expect(mockedSymbolApi.upload).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  /** 重置清除所有列表条件，键盘标签切换只使用目标类型适用的筛选。 */
  it('resets filters and changes symbol tabs with the keyboard', async () => {
    routeState.query = {}
    const wrapper = mount(SymbolFilesView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: true } } })
    await flushPromises()
    await wrapper.get('#symbol-filter-version').setValue('2.0')
    await wrapper.get('#symbol-filter-build-id').setValue('release-2')
    await wrapper.get('#symbol-filter-scope').setValue('build')
    await wrapper.findAll('button').find(button => button.text() === '重置')!.trigger('click')
    await flushPromises()
    expect(mockedSymbolApi.list).toHaveBeenLastCalledWith('app-1', expect.objectContaining({ appVersion: undefined, buildId: undefined, mappingScope: undefined }), expect.any(AbortSignal))
    await wrapper.get('#symbol-tab-MAPPING').trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    expect(wrapper.get('#symbol-tab-NATIVE_SO').attributes('aria-selected')).toBe('true')
    expect(wrapper.find('#symbol-filter-build-id').exists()).toBe(false)
    expect(mockedSymbolApi.list).toHaveBeenLastCalledWith('app-1', expect.objectContaining({ type: 'NATIVE_SO', buildId: undefined, mappingScope: undefined }), expect.any(AbortSignal))
    wrapper.unmount()
  })

})
