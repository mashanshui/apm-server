import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NativeCrashPanel from './NativeCrashPanel.vue'
import type { NativeCurrent } from '../types/crash'

/** 使用安全合成模块，不包含真实设备或地址材料。 */
function sample(elfBuildId: string | null): NativeCurrent {
  return { abi: 'arm64-v8a', systemTid: 1, signal: { number: 11, name: 'SIGSEGV', code: 1 },
    registers: { status: 'ok', truncated: false, values: {} },
    modules: [{ id: 'module', name: 'libsample.so', abi: 'arm64-v8a', elfBuildId }],
    backtrace: { status: 'ok', truncated: false, frames: [] } }
}

/** 公开路由目标便于断言模块身份与应用构建互相独立。 */
const link = { name: 'SymbolLink', props: ['to'], template: '<a><slot /></a>' }

describe('NativeCrashPanel symbols', () => {
  /** 物理帧和内联链独立展示，只有函数名时不伪造行号。 */
  it('shows per-frame sources and independent Java stack', () => {
    const source = { type: 'NATIVE_SO' as const, symbolFileId: '11111111-1111-4111-8111-111111111111', revision: 2, sha256: 'a'.repeat(64) }
    const mapping = { type: 'MAPPING' as const, symbolFileId: '22222222-2222-4222-8222-222222222222', revision: 1, sha256: 'b'.repeat(64) }
    const current: NativeCurrent = { ...sample('aabb'), backtrace: { status: 'partial', reason: 'size_limit', truncated: true, frames: [
      { index: 0, moduleId: 'module', relativePc: '0xffffffffffffffff', resolution: { status: 'symbolicated', source, attemptedSource: source,
        processedAt: '2026-11-12T00:00:00Z', inline: [{ function: 'function', file: 'sample.c', line: null, column: null },
          { function: 'outer', file: 'outer.c', line: 42, column: 3 }] } },
      { index: 1, moduleId: 'module', relativePc: '0x0', resolution: { status: 'pending', reason: 'missing_so' } },
    ] }, javaStack: { status: 'ok', truncated: false, symbolicationStatus: 'symbolicated', source: mapping, attemptedSource: mapping,
      processedAt: '2026-11-12T00:00:00Z', stack: { format: 'retrace_text', content: 'at A.run(A.java:1)' } } }
    const wrapper = mount(NativeCrashPanel, { props: { crash: current, appId: 'app', appVersion: '2.0', canManage: true }, global: { stubs: { RouterLink: link } } })
    expect(wrapper.text()).toContain('0xffffffffffffffff')
    expect(wrapper.findAll('.frame-index').map(item => item.text())).toEqual(['1', '2'])
    expect(wrapper.findAll('.frame-raw').map(item => item.text())).toEqual([
      '#00 pc 0xffffffffffffffff libsample.so [arm64-v8a::aabb]',
      '#01 pc 0x0 libsample.so [arm64-v8a::aabb]',
    ])
    expect(wrapper.findAll('.frame-function').map(item => item.text())).toEqual(['function', 'outer'])
    expect(wrapper.findAll('.frame-file').map(item => item.text())).toEqual(['sample.c · 行号未知', 'outer.c:42:3'])
    expect(wrapper.text()).toContain('已还原的帧会保留首次成功结果')
    expect(wrapper.text()).toContain('缺少匹配的 SO 符号文件')
    expect(wrapper.text()).not.toContain('missing_so')
    expect(wrapper.get('.frame-source').attributes('open')).toBeUndefined()
    expect(wrapper.get('.frame-source').text()).toContain(source.sha256)
    expect(wrapper.text()).toContain('等待还原')
    expect(wrapper.text()).toContain('已截断')
    expect(wrapper.text()).toContain(source.symbolFileId)
    expect(wrapper.text()).toContain(mapping.symbolFileId)
    expect(wrapper.text()).toContain('at A.run(A.java:1)')
  })
  /** 现场地址、脱敏路径和函数偏移原样显示，缺少 Build ID 不补猜。 */
  it('keeps the captured native stack line and unknown identity', () => {
    const raw = sample(null)
    raw.modules[0].path = '/data/app/example/libsample.so'
    raw.backtrace.frames = [{ index: 0, moduleId: 'module', relativePc: '0x0', functionName: 'captured', functionOffset: '0x8',
      resolution: { status: 'not_applicable', reason: 'missing_module_identity' } }]
    const wrapper = mount(NativeCrashPanel, { props: { crash: raw }, global: { stubs: { RouterLink: link } } })
    expect(wrapper.get('.frame-raw').text()).toBe('#00 pc 0x0 /data/app/example/libsample.so (captured+0x8) [arm64-v8a::Build ID 未知]')
    expect(wrapper.find('.frame-source').exists()).toBe(false)
  })
  /** 尚未检查来源时显示等待处理，不能声称 SO 或 mapping 已缺失。 */
  it('does not report missing symbols before background inspection', () => {
    const current = sample('aabb')
    current.backtrace.frames = [{ index: 0, moduleId: 'module', relativePc: '0x1',
      resolution: { status: 'pending', reason: 'native_symbolication_deferred' } }]
    current.javaStack = { status: 'ok', truncated: false, symbolicationStatus: 'pending', symbolicationReason: 'native_symbolication_deferred',
      stack: { format: 'raw_frames', content: [{ className: 'A', methodName: 'a' }] } }
    const wrapper = mount(NativeCrashPanel, { props: { crash: current }, global: { stubs: { RouterLink: link } } })
    expect(wrapper.text().match(/等待后台处理/g)).toHaveLength(2)
    expect(wrapper.text()).not.toContain('缺少匹配')
    expect(wrapper.text()).not.toContain('native_symbolication_deferred')
  })
  /** 关联 Java 栈上传入口预填版本与构建，只读用户不显示写入口。 */
  it('offers mapping upload for unresolved associated Java only to managers', () => {
    const current: NativeCurrent = { ...sample('aabb'), backtrace: { status: 'unavailable', reason: 'io_error', truncated: false, frames: [] },
      javaStack: { status: 'ok', truncated: false, symbolicationStatus: 'pending', symbolicationReason: 'missing_mapping',
        stack: { format: 'raw_frames', content: [{ className: 'A', methodName: 'a' }] } } }
    const manager = mount(NativeCrashPanel, { props: { crash: current, appId: 'app', appVersion: '2.0', buildId: 'build', canManage: true }, global: { stubs: { RouterLink: link } } })
    const links = manager.findAllComponents({ name: 'SymbolLink' })
    expect(links.map(item => item.props('to'))).toContainEqual({ name: 'app-symbols', params: { appId: 'app' }, query: { type: 'MAPPING', appVersion: '2.0', buildId: 'build' } })
    const viewer = mount(NativeCrashPanel, { props: { crash: current, appId: 'app', appVersion: '2.0', buildId: 'build', canManage: false }, global: { stubs: { RouterLink: link } } })
    expect(viewer.findComponent({ name: 'SymbolLink' }).exists()).toBe(false)
  })
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
