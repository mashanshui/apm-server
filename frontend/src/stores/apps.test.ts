import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { appApi } from '../api/appApi'
import { useAppStore } from './apps'

vi.mock('../api/appApi', () => ({
  appApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    getIngestCredential: vi.fn(),
  },
}))

const mockedAppApi = vi.mocked(appApi)

describe('app store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('clears app data when the authenticated session expires', async () => {
    mockedAppApi.list.mockResolvedValue([{
      appId: 'mobile-apm',
      name: '移动 APM',
      description: null,
      packageName: 'com.example.mobile',
      role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
    }])
    const store = useAppStore()
    await store.load()
    store.currentApp = store.apps[0]

    store.clear()

    expect(store.apps).toEqual([])
    expect(store.currentApp).toBeNull()
    expect(store.error).toBeNull()
  })

  it('keeps optional display fields when creating an app', async () => {
    const created = {
      appId: '550e8400-e29b-41d4-a716-446655440000',
      name: '线上 Demo',
      description: '用于线上验证',
      packageName: 'com.example.mobile',
      role: 'OWNER' as const,
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
    }
    mockedAppApi.create.mockResolvedValue(created)
    const store = useAppStore()

    await store.create({ name: '线上 Demo', description: '用于线上验证', packageName: 'com.example.mobile' })

    expect(mockedAppApi.create).toHaveBeenCalledWith({
      name: '线上 Demo',
      description: '用于线上验证',
      packageName: 'com.example.mobile',
    })
    expect(store.apps[0]).toMatchObject({ name: '线上 Demo', packageName: 'com.example.mobile' })
  })
})
