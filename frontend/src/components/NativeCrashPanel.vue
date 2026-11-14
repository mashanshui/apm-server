<script setup lang="ts">
import type { NativeCurrent } from '../types/crash'
import type { DiagnosticPartition } from '../types/crashDiagnostics'
import NativeFrameList from './NativeFrameList.vue'

/** 服务端返回的唯一原始 Native 主现场。 */
defineProps<{ crash: NativeCurrent; appId?: string; appVersion?: string; buildId?: string; canManage?: boolean }>()

/** 将原始现场的采集状态和安全原因显示为中文。 */
function collectionText(partition: DiagnosticPartition): string {
  const statuses = { ok: '采集成功', partial: '部分采集', unavailable: '不可用', skipped: '已跳过' }
  const reasons: Record<string, string> = { permission_denied: '权限受限', unsupported: '系统不支持', timeout: '采集超时',
    io_error: '读取失败', memory_pressure: '内存不足', interrupted: '采集被中断', size_limit: '大小受限', count_limit: '数量受限' }
  return `${statuses[partition.status]}${partition.reason ? ` · ${reasons[partition.reason]}` : ''}${partition.truncated ? ' · 已截断' : ''}`
}

/** Java 栈处理状态与采集状态分开展示。 */
function javaState(status: string): string {
  return ({ pending: '等待还原', failed: '还原失败', symbolicated: '已还原', not_applicable: '无帧，不适用还原' } as Record<string, string>)[status] ?? '状态未知'
}

/** 区分等待后台检查与实际缺少 mapping。 */
function javaReason(reason: string): string {
  return ({ native_symbolication_deferred: '等待后台处理', missing_mapping: '缺少匹配的 mapping' } as Record<string, string>)[reason] ?? reason
}
</script>

<template>
  <section class="panel detail-card native-crash" aria-label="Native 崩溃现场">
    <div class="panel-header" style="padding: 0 0 16px"><div><h2>Native 崩溃现场</h2><p>保留原始地址与采集状态；每帧展示独立还原结果和实际来源。</p></div></div>
    <dl class="native-meta">
      <div><dt>信号</dt><dd>{{ crash.signal.name }}（{{ crash.signal.number }}）</dd></div>
      <div><dt>信号码</dt><dd>{{ crash.signal.code }}<template v-if="crash.signal.codeName"> · {{ crash.signal.codeName }}</template></dd></div>
      <div><dt>故障地址</dt><dd><code>{{ crash.signal.faultAddress ?? '未知' }}</code></dd></div>
      <div><dt>ABI / TID</dt><dd>{{ crash.abi }} / {{ crash.systemTid }}</dd></div>
      <div><dt>线程</dt><dd>{{ crash.threadName ?? '未知' }} · {{ crash.mainThread == null ? '主线程未知' : crash.mainThread ? '主线程' : '后台线程' }}</dd></div>
      <div v-if="crash.signal.abortMessage"><dt>Abort 消息</dt><dd>{{ crash.signal.abortMessage }}</dd></div>
    </dl>
    <h3>寄存器 <small>{{ collectionText(crash.registers) }}</small></h3>
    <dl v-if="Object.keys(crash.registers.values).length" class="native-registers"><div v-for="(value, name) in crash.registers.values" :key="name"><dt>{{ name }}</dt><dd><code>{{ value }}</code></dd></div></dl>
    <p v-else class="native-empty">没有可取得的寄存器。</p>
    <h3>崩溃线程回溯 <small>{{ collectionText(crash.backtrace) }}</small></h3>
    <NativeFrameList :frames="crash.backtrace.frames" :modules="crash.modules" />
    <h3>模块</h3>
    <p v-if="!crash.modules.length" class="native-empty">没有可取得的模块信息。</p>
    <ul v-else class="native-modules"><li v-for="module in crash.modules" :key="module.id"><strong>{{ module.name }}</strong> · {{ module.abi }} · ELF Build ID {{ module.elfBuildId ?? '未知' }}<span v-if="module.path"> · {{ module.path }}</span><RouterLink v-if="canManage && appId && appVersion" class="button" :to="{ name: 'app-symbols', params: { appId }, query: { type: 'NATIVE_SO', appVersion, ...(module.elfBuildId ? { targetAbi: module.abi, targetElfBuildId: module.elfBuildId } : {}) } }">{{ module.elfBuildId ? '上传此模块 SO' : '身份未知 · 管理此版本 SO' }}</RouterLink></li></ul>
    <template v-if="crash.javaStack">
      <h3>关联的 Java 栈 <small>{{ collectionText(crash.javaStack) }}</small></h3>
        <p>Java 栈状态：{{ javaState(crash.javaStack.symbolicationStatus) }}<template v-if="crash.javaStack.symbolicationReason"> · {{ javaReason(crash.javaStack.symbolicationReason) }}</template></p>
        <p v-if="crash.javaStack.source">mapping {{ crash.javaStack.source.symbolFileId }} · revision {{ crash.javaStack.source.revision }} · SHA-256 {{ crash.javaStack.source.sha256 }}</p>
        <pre v-if="crash.javaStack.stack.format === 'retrace_text'">{{ crash.javaStack.stack.content }}</pre>
        <p v-else-if="!crash.javaStack.stack.content.length" class="native-empty">没有可可靠关联的 Java 堆栈帧。</p>
        <ol v-else class="native-java"><li v-for="(frame, index) in crash.javaStack.stack.content" :key="index"><code>{{ frame.className }}.{{ frame.methodName }}（{{ frame.fileName ?? '未知文件' }}:{{ frame.lineNumber ?? '?' }}）</code></li></ol>
        <RouterLink v-if="canManage && appId && appVersion && crash.javaStack.symbolicationStatus !== 'symbolicated'" class="button" :to="{ name: 'app-symbols', params: { appId }, query: { type: 'MAPPING', appVersion, ...(buildId ? { buildId } : {}) } }">上传此版本 mapping</RouterLink>
    </template>
  </section>
</template>

<style scoped>
.native-crash h3 { margin: 22px 0 10px; }
.native-crash h3 small { font-size: 12px; color: var(--text-secondary, #667085); font-weight: normal; }
.native-meta, .native-registers { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px 20px; margin: 0; }
.native-meta dt, .native-registers dt { color: var(--text-secondary, #667085); font-size: 12px; }
.native-meta dd, .native-registers dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.native-empty { color: var(--text-secondary, #667085); }
.native-modules, .native-java { padding-left: 20px; overflow-wrap: anywhere; }
.native-modules li, .native-java li { padding: 4px 0; }
</style>
