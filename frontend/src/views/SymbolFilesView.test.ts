import { beforeEach, describe, expect, it, vi } from 'vitest'
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
    upload: vi.fn(),
    replace: vi.fn(),
  },
}))

const mockedSymbolApi = vi.mocked(symbolApi)

const currentSymbol = {
  symbolId: 'symbol-1',
  appId: 'app-1',
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
  beforeEach(() => {
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
    routeState.query = {}
    vi.resetAllMocks()
    mockedSymbolApi.list.mockResolvedValue({ appId: 'app-1', items: [currentSymbol], nextCursor: null })
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

    expect(mockedSymbolApi.list).toHaveBeenLastCalledWith('app-1', { buildId: 'release-1' }, expect.any(AbortSignal))
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
    expect(wrapper.text()).toContain('当前应用还没有注册 mapping。')
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
    await wrapper.get('.symbol-pagination button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('release-1')
    expect(wrapper.text()).toContain('release-2')
    expect(mockedSymbolApi.list).toHaveBeenLastCalledWith('app-1', { buildId: undefined, cursor: 'opaque-cursor' }, expect.any(AbortSignal))
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
    const signal = mockedSymbolApi.upload.mock.calls[0]?.[3]
    routeState.params.appId = 'app-2'
    await flushPromises()
    expect(signal?.aborted).toBe(true)
    upload.resolve(currentSymbol)
    await flushPromises()
    expect(wrapper.text()).not.toContain('mapping 上传成功')
  })
})
