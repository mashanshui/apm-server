<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { MemoryLeakFilterForm } from '../types/memoryLeak'
import { fromDateTimeLocal, toDateTimeLocal } from '../utils/format'
import { normalizeMemoryLeakFilters } from '../utils/memoryLeakQuery'

const props = defineProps<{ modelValue: MemoryLeakFilterForm; loading?: boolean }>()
const emit = defineEmits<{ submit: [filters: MemoryLeakFilterForm] }>()
const draft = reactive({ ...props.modelValue, from: '', to: '' })

watch(() => props.modelValue, (next) => Object.assign(draft, next, {
  from: toDateTimeLocal(next.from), to: toDateTimeLocal(next.to),
}), { deep: true, immediate: true })

function submit(): void {
  emit('submit', normalizeMemoryLeakFilters({
    ...draft,
    from: fromDateTimeLocal(draft.from), to: fromDateTimeLocal(draft.to),
    appVersion: draft.appVersion.trim(), deviceModel: draft.deviceModel.trim(), processName: draft.processName.trim(),
    scene: draft.scene.trim(), manufacturer: draft.manufacturer.trim(), sdkInt: draft.sdkInt.trim(),
    dumpReason: draft.dumpReason.trim(), anonymousDeviceId: draft.anonymousDeviceId.trim(),
    signature: draft.signature.trim(), keyword: draft.keyword.trim(), page: 1,
  }))
}
</script>

<template>
  <form class="panel filter-panel memory-leak-filter" aria-label="内存泄漏报告筛选" @submit.prevent="submit">
    <div class="filter-grid">
      <div class="field"><label for="memory-leak-from">开始时间</label><input id="memory-leak-from" v-model="draft.from" type="datetime-local" required /></div>
      <div class="field"><label for="memory-leak-to">结束时间</label><input id="memory-leak-to" v-model="draft.to" type="datetime-local" required /></div>
      <div class="field"><label for="memory-leak-version">应用版本</label><input id="memory-leak-version" v-model="draft.appVersion" placeholder="例如 3.2.0" /></div>
      <div class="field"><label for="memory-leak-keyword">关键词</label><input id="memory-leak-keyword" v-model="draft.keyword" placeholder="类名、引用或原因" /></div>
    </div>
    <details class="advanced-filter">
      <summary>高级筛选</summary>
      <div class="filter-grid advanced-filter-grid">
        <div class="field"><label for="memory-leak-device">设备型号</label><input id="memory-leak-device" v-model="draft.deviceModel" /></div>
        <div class="field"><label for="memory-leak-process">进程名</label><input id="memory-leak-process" v-model="draft.processName" /></div>
        <div class="field"><label for="memory-leak-scene">场景</label><input id="memory-leak-scene" v-model="draft.scene" /></div>
        <div class="field"><label for="memory-leak-manufacturer">厂商</label><input id="memory-leak-manufacturer" v-model="draft.manufacturer" /></div>
        <div class="field"><label for="memory-leak-sdk">SDK 整数版本</label><input id="memory-leak-sdk" v-model="draft.sdkInt" inputmode="numeric" /></div>
        <div class="field"><label for="memory-leak-reason">触发原因</label><input id="memory-leak-reason" v-model="draft.dumpReason" /></div>
        <div class="field"><label for="memory-leak-device-id">匿名设备 ID</label><input id="memory-leak-device-id" v-model="draft.anonymousDeviceId" /></div>
        <div class="field"><label for="memory-leak-signature">Signature</label><input id="memory-leak-signature" v-model="draft.signature" /></div>
      </div>
    </details>
    <div class="filter-actions">
      <span class="subtitle">展示 SDK 报告的疑似问题，查询范围按 UTC 计算</span>
      <button class="button button-primary" type="submit" :disabled="loading">{{ loading ? '查询中…' : '应用筛选' }}</button>
    </div>
  </form>
</template>
