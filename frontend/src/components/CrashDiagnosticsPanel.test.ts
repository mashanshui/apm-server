import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CrashDiagnosticsPanel from './CrashDiagnosticsPanel.vue'
import type { CrashDiagnostics } from '../types/crashDiagnostics'

/** 构造只含合成数据的完整现场，覆盖同名线程与空值。 */
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
    logcat: { status: 'partial', reason: 'permission_denied', truncated: false,
      main: { status: 'ok', truncated: false, lines: ['<img src=x onerror=alert(1)>'] },
      system: { status: 'unavailable', reason: 'permission_denied', truncated: false, lines: [] },
      events: { status: 'ok', truncated: false, lines: [] } },
    memory: { status: 'ok', truncated: false, javaHeapUsedBytes: 0, totalPssBytes: 1024 },
    openFiles: { status: 'ok', truncated: false, count: 0, entries: [] },
    process: { status: 'ok', truncated: false, pid: 47, foreground: false },
    device: { status: 'ok', truncated: false, apiLevel: 29, abis: ['arm64-v8a'] },
  }
}

describe('CrashDiagnosticsPanel', () => {
  /** 同名线程按 Java ID 单独选择；文本以普通文本而非 HTML 展示。 */
  it('renders separate thread outcomes and escapes untrusted text', async () => {
    const wrapper = mount(CrashDiagnosticsPanel, { props: { diagnostics: diagnostics() } })
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
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('0 B')
    expect(wrapper.text()).toContain('总数 0')
    wrapper.unmount()
  })

  /** 每个日志缓冲区保留失败与成功空结果，切换事件清除选择。 */
  it('distinguishes unavailable, empty and absent data and resets selection', async () => {
    const wrapper = mount(CrashDiagnosticsPanel, { props: { diagnostics: diagnostics() } })
    const buffers = wrapper.findAll('.segmented-control button')
    await buffers[1].trigger('click')
    expect(wrapper.text()).toContain('权限受限')
    expect(wrapper.text()).toContain('没有可展示的日志')
    await buffers[2].trigger('click')
    expect(wrapper.text()).toContain('采集成功，日志为空')
    await wrapper.findAll('.thread-list button')[1].trigger('click')
    await wrapper.setProps({ diagnostics: diagnostics() })
    expect(wrapper.findAll('.thread-list button')[0].attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('.segmented-control button')[0].attributes('aria-selected')).toBe('true')
    await wrapper.setProps({ diagnostics: null })
    expect(wrapper.text()).toContain('此事件未采集诊断上下文')
    expect(wrapper.find('.thread-list').exists()).toBe(false)
    wrapper.unmount()
  })

  /** 空帧是有效快照，不应产生虚构的栈帧。 */
  it('renders an empty thread stack without inventing frames', () => {
    const value = diagnostics()
    value.otherThreads!.threads[0].symbolicationStatus = 'not_applicable'
    value.otherThreads!.threads[0].stack = { format: 'raw_frames', content: [] }
    const wrapper = mount(CrashDiagnosticsPanel, { props: { diagnostics: value } })
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
      { systemTid: 50, name: 'same', mainThread: true, backtrace: { status: 'ok', truncated: false, frames: [{ index: 0, moduleId: 'm1', relativePc: '0x0' }] } },
      { systemTid: 51, name: 'same', backtrace: { status: 'unavailable', reason: 'timeout', truncated: false, frames: [] } },
    ] }
    const nativeCrash = { abi: 'arm64-v8a', systemTid: 47, signal: { number: 11, name: 'SIGSEGV', code: 1 },
      registers: { status: 'ok' as const, truncated: false, values: { pc: '0x0' } },
      modules: [{ id: 'm1', name: 'liba.so', abi: 'arm64-v8a' }], backtrace: { status: 'ok' as const, truncated: false, frames: [{ index: 0 }] } }
    const wrapper = mount(CrashDiagnosticsPanel, { props: { diagnostics: value, nativeCrash } })
    expect(wrapper.text()).toContain('与崩溃线程的关系未知')
    expect(wrapper.text()).toContain('liba.so · 0x0')
    const tabs = wrapper.findAll('[aria-label="其他 Native 线程"] button')
    expect(tabs).toHaveLength(2)
    await tabs[1].trigger('click')
    expect(wrapper.text()).toContain('系统 TID 51')
    expect(wrapper.text()).toContain('采集超时')
    expect(wrapper.text()).not.toContain('liba.so · 0x0')
    expect(wrapper.text()).toContain('Java ID 2')
    wrapper.unmount()
  })
})
