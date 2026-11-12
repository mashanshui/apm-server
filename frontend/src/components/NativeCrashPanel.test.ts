import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NativeCrashPanel from './NativeCrashPanel.vue'
import type { NativeCrash } from '../types/crash'

/** 使用安全合成模块，不包含真实设备或地址材料。 */
function sample(elfBuildId: string | null): NativeCrash {
  return { abi: 'arm64-v8a', systemTid: 1, signal: { number: 11, name: 'SIGSEGV', code: 1 },
    registers: { status: 'ok', truncated: false, values: {} },
    modules: [{ id: 'module', name: 'libsample.so', abi: 'arm64-v8a', elfBuildId }],
    backtrace: { status: 'ok', truncated: false, frames: [] } }
}

/** 公开路由目标便于断言模块身份与应用构建互相独立。 */
const link = { name: 'SymbolLink', props: ['to'], template: '<a><slot /></a>' }

describe('NativeCrashPanel symbols', () => {
  /** 已知模块只预填实际完整 ELF 身份，不带应用 buildId。 */
  it('prefills a known module using only version and ELF identity', () => {
    const wrapper = mount(NativeCrashPanel, { props: { crash: sample('aabb'), appId: 'app', appVersion: '2.0', canManage: true }, global: { stubs: { RouterLink: link } } })
    expect(wrapper.findComponent({ name: 'SymbolLink' }).props('to')).toEqual({ name: 'app-symbols', params: { appId: 'app' },
      query: { type: 'NATIVE_SO', appVersion: '2.0', targetAbi: 'arm64-v8a', targetElfBuildId: 'aabb' } })
  })
  /** 未知模块只允许版本通用管理，不能使用文件名猜测目标。 */
  it('omits target identity when the module build id is unknown', () => {
    const wrapper = mount(NativeCrashPanel, { props: { crash: sample(null), appId: 'app', appVersion: '2.0', canManage: true }, global: { stubs: { RouterLink: link } } })
    expect(wrapper.text()).toContain('身份未知')
    expect(wrapper.findComponent({ name: 'SymbolLink' }).props('to')).toEqual({ name: 'app-symbols', params: { appId: 'app' }, query: { type: 'NATIVE_SO', appVersion: '2.0' } })
  })
  /** 普通查看者没有写操作入口。 */
  it('hides symbol writes for read only members', () => {
    const wrapper = mount(NativeCrashPanel, { props: { crash: sample('aabb'), appId: 'app', appVersion: '2.0', canManage: false }, global: { stubs: { RouterLink: link } } })
    expect(wrapper.findComponent({ name: 'SymbolLink' }).exists()).toBe(false)
  })
})
