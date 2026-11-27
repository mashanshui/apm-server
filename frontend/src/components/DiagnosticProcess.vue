<script setup lang="ts">
import type { DiagnosticDevice, DiagnosticProcess } from '../types/crashDiagnostics'
import { partitionText, timestamp } from '../utils/diagnostics'

/** 当前事件已经授权返回的进程与设备分区；null 表示对应分区未采集。 */
defineProps<{ process: DiagnosticProcess | null; device: DiagnosticDevice | null }>()
</script>

<template>
  <section class="panel detail-card diagnostic-card" aria-label="进程与设备">
    <div class="panel-header">
      <div><h2>进程信息</h2><p>进程上下文与公开设备环境；未知前后台和启动时间保留为空。</p></div>
      <span class="badge">进程：{{ partitionText(process) }} · 设备：{{ partitionText(device) }}</span>
    </div>
    <div class="diagnostic-section">
      <dl class="diagnostic-grid">
        <div><dt>进程 PID</dt><dd>{{ process?.pid ?? '—' }}</dd></div>
        <div><dt>进程名</dt><dd>{{ process?.name ?? '—' }}</dd></div>
        <div><dt>前后台</dt><dd>{{ process?.foreground == null ? '未知' : process.foreground ? '前台' : '后台' }}</dd></div>
        <div><dt>进程启动时间</dt><dd>{{ timestamp(process?.startedAt) }}</dd></div>
        <div><dt>SDK 初始化时间</dt><dd>{{ timestamp(process?.sdkInitializedAt) }}</dd></div>
        <div><dt>Android API</dt><dd>{{ device?.apiLevel ?? '—' }}</dd></div>
        <div><dt>ABI</dt><dd>{{ device?.abis.length ? device.abis.join(' / ') : '—' }}</dd></div>
        <div><dt>厂商 / 品牌</dt><dd>{{ device?.manufacturer ?? '—' }} / {{ device?.brand ?? '—' }}</dd></div>
        <div class="diagnostic-wide"><dt>系统构建 fingerprint</dt><dd>{{ device?.buildFingerprint ?? '—' }}</dd></div>
      </dl>
    </div>
  </section>
</template>
