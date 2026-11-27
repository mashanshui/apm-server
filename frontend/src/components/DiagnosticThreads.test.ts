import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiagnosticThreads from './DiagnosticThreads.vue'
import type { CrashDiagnostics } from '../types/crashDiagnostics'

/** 构造只含合成数据的线程现场，覆盖同名线程与空值。 */
function diagnostics(): CrashDiagnostics {
  return {
    crashedThread: { status: 'ok', truncated: false, javaId: '1', name: 'worker', state: 'RUNNABLE', systemTid: 47 },
    otherThreads: { status: 'partial', reason: 'size_limit', truncated: true, observedCount: 3, omittedCount: 1, threads: [
      { javaId: '2', name: 'same', state: 'WAITING', priority: 5, daemon: false, mainThread: true, truncated: false,
        symbolicationStatus: 'symbolicated', symbolFileId: 'mapping-1', symbolFileRevision: 2, symbolFileSha256: 'a'.repeat(64),
        stack: { format: 'retrace_text', content: 'main.Real.run(Main.java:4)' } },
      { javaId: '3', name: 'same', state: 'BLOCKED', priority: 5, daemon: true, mainThread: false, truncated: true,
        symbolicationStatus: 'failed', symbolicationReason: 'timeout',
        stack: { format: 'raw_frames', content: [{ className: '<script>alert(1)</script>', methodName: 'run', lineNumber: 0, applicationFrame: false }] } },
    ] },
  }
}

/** 合成 Native 主栈，仅提供模块与身份，不含真实设备数据。 */
function nativeCrash(modules: Array<{ id: string; name: string; abi: string }> = []) {
  return { abi: 'arm64-v8a', systemTid: 47, signal: { number: 11, name: 'SIGSEGV', code: 1 },
    registers: { status: 'ok' as const, truncated: false, values: { pc: '0x0' } }, modules,
    backtrace: { status: 'ok' as const, truncated: false, frames: [] } }
}

describe('DiagnosticThreads', () => {
  /** Native 的 Java 快照按角色显示 mapping 入口，未知关系和构建范围独立保留。 */
  it('prefills mapping for unresolved native Java threads and hides writes from viewers', async () => {
    const value = diagnostics()
    value.otherThreads!.threads[1].relationToCrash = 'unknown'
    value.otherThreads!.threads[1].symbolicationStatus = 'pending'
    value.otherThreads!.threads[1].symbolicationReason = 'missing_mapping'
    const link = { name: 'SymbolLink', props: ['to'], template: '<a><slot /></a>' }
    const wrapper = mount(DiagnosticThreads, { props: { diagnostics: value, nativeCrash: nativeCrash(), appId: 'app', appVersion: '2.0',
      buildId: 'build-1', canManage: true }, global: { stubs: { RouterLink: link } } })
    await wrapper.findAll('.thread-list button')[1].trigger('click')
    expect(wrapper.text()).toContain('与崩溃线程的关系未知')
    expect(wrapper.text()).toContain('等待 mapping')
    expect(wrapper.text()).not.toContain('处理失败')
    expect(wrapper.getComponent({ name: 'SymbolLink' }).props('to')).toEqual({ name: 'app-symbols', params: { appId: 'app' },
      query: { type: 'MAPPING', appVersion: '2.0', buildId: 'build-1' } })
    await wrapper.setProps({ buildId: undefined })
    expect(wrapper.getComponent({ name: 'SymbolLink' }).props('to')).toEqual({ name: 'app-symbols', params: { appId: 'app' },
      query: { type: 'MAPPING', appVersion: '2.0' } })
    await wrapper.setProps({ canManage: false })
    expect(wrapper.findComponent({ name: 'SymbolLink' }).exists()).toBe(false)
  })

  /** 辅助 Native 帧展示各自来源，与 Java 线程身份及结果分离。 */
  it('renders structured native auxiliary frame sources', () => {
    const value = diagnostics()
    value.nativeThreads = { status: 'ok', truncated: false, threads: [{ systemTid: 50, name: 'same',
      backtrace: { status: 'ok', truncated: false, frames: [{ index: 0, moduleId: 'm1', relativePc: '0xffffffffffffffff',
        resolution: { status: 'symbolicated', inline: [{ function: 'worker', file: null, line: null, column: null }],
          source: { type: 'NATIVE_SO', symbolFileId: 'so-deleted', revision: 1, sha256: 'a'.repeat(64) }, processedAt: '2026-11-12T00:00:00Z' } } as import('../types/crash').NativeCurrentFrame] } }] }
    const wrapper = mount(DiagnosticThreads, { props: { diagnostics: value, nativeCrash: nativeCrash([{ id: 'm1', name: 'liba.so', abi: 'arm64-v8a' }]) } })
    expect(wrapper.text()).toContain('0xffffffffffffffff')
    expect(wrapper.get('.frame-function').text()).toBe('worker')
    expect(wrapper.get('.frame-file').text()).toContain('源码未知')
    expect(wrapper.text()).toContain('so-deleted')
    expect(wrapper.text()).toContain('Java ID 2')
  })

  /** 同名线程按 Java ID 单独选择；文本以普通文本而非 HTML 展示。 */
  it('renders separate thread outcomes and escapes untrusted text', async () => {
    const wrapper = mount(DiagnosticThreads, { props: { diagnostics: diagnostics() } })
    const threadTabs = wrapper.findAll('.thread-list button')
    expect(threadTabs).toHaveLength(2)
    expect(threadTabs[0].text()).toContain('Java ID 2')
    expect(threadTabs[1].text()).toContain('Java ID 3')
    expect(wrapper.text()).toContain('main.Real.run')
    expect(wrapper.text()).toContain('mapping mapping-1')
    await threadTabs[1].trigger('click')
    expect(wrapper.text()).toContain('还原失败 · 保留原始帧')
    expect(wrapper.text()).toContain('采集超时')
    expect(wrapper.text()).toContain('<script>alert(1)</script>')
    expect(wrapper.find('script').exists()).toBe(false)
    wrapper.unmount()
  })

  /** 空帧是有效快照，不应产生虚构的栈帧。 */
  it('renders an empty thread stack without inventing frames', () => {
    const value = diagnostics()
    value.otherThreads!.threads[0].symbolicationStatus = 'not_applicable'
    value.otherThreads!.threads[0].stack = { format: 'raw_frames', content: [] }
    const wrapper = mount(DiagnosticThreads, { props: { diagnostics: value } })
    expect(wrapper.text()).toContain('没有可取得的 Java 堆栈帧')
    expect(wrapper.find('.diagnostic-frames').exists()).toBe(false)
    wrapper.unmount()
  })

  /** Native 与 Java 同名线程分别按 TID 和 Java ID 选择并保留关系未知。 */
  it('keeps native and java thread selection independent', async () => {
    const value = diagnostics()
    value.otherThreads!.threads[0] = { ...value.otherThreads!.threads[0], relationToCrash: 'unknown', symbolicationStatus: 'not_applicable',
      stack: { format: 'raw_frames', content: [] } }
    value.nativeThreads = { status: 'partial', reason: 'count_limit', truncated: true, observedCount: 3, omittedCount: 1, threads: [
      { systemTid: 50, name: 'same', mainThread: true, backtrace: { status: 'ok', truncated: false, frames: [{ index: 0, moduleId: 'm1', relativePc: '0x0', resolution: { status: 'pending', reason: 'missing_so' } }] } },
      { systemTid: 51, name: 'same', backtrace: { status: 'unavailable', reason: 'timeout', truncated: false, frames: [] } },
    ] }
    const wrapper = mount(DiagnosticThreads, { props: { diagnostics: value, nativeCrash: nativeCrash([{ id: 'm1', name: 'liba.so', abi: 'arm64-v8a' }]) } })
    expect(wrapper.text()).toContain('与崩溃线程的关系未知')
    expect(wrapper.get('.frame-raw').text()).toContain('#00 pc 0x0 liba.so')
    const tabs = wrapper.findAll('[aria-label="其他 Native 线程"] button')
    expect(tabs).toHaveLength(2)
    await tabs[1].trigger('click')
    expect(wrapper.text()).toContain('系统 TID 51')
    expect(wrapper.text()).toContain('采集超时')
    expect(wrapper.find('.frame-raw').exists()).toBe(false)
    expect(wrapper.text()).toContain('Java ID 2')
    wrapper.unmount()
  })

  /** 未采集现场给出明确空态；更换事件后线程选择回到主线程。 */
  it('shows an explicit empty state and resets selection on event change', async () => {
    const wrapper = mount(DiagnosticThreads, { props: { diagnostics: null } })
    expect(wrapper.text()).toContain('此事件未采集诊断上下文')
    expect(wrapper.find('.thread-list').exists()).toBe(false)
    await wrapper.setProps({ diagnostics: diagnostics() })
    await wrapper.findAll('.thread-list button')[1].trigger('click')
    expect(wrapper.findAll('.thread-list button')[1].attributes('aria-selected')).toBe('true')
    await wrapper.setProps({ diagnostics: diagnostics() })
    expect(wrapper.findAll('.thread-list button')[0].attributes('aria-selected')).toBe('true')
    wrapper.unmount()
  })
})
