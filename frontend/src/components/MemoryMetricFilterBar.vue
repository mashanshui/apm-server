<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { MemoryMetricFilterForm } from '../types/memory'
import { fromDateTimeLocal, toDateTimeLocal } from '../utils/format'
import { normalizeMemoryMetricFilters } from '../utils/memoryQuery'

const props = defineProps<{ modelValue: MemoryMetricFilterForm; loading?: boolean }>()
const emit = defineEmits<{ submit: [filters: MemoryMetricFilterForm] }>()
const draft = reactive({ ...props.modelValue, from: '', to: '' })

watch(() => props.modelValue, (next) => Object.assign(draft, next, {
  from: toDateTimeLocal(next.from), to: toDateTimeLocal(next.to),
}), { deep: true, immediate: true })

function submit(): void {
  emit('submit', normalizeMemoryMetricFilters({
    ...draft,
    from: fromDateTimeLocal(draft.from), to: fromDateTimeLocal(draft.to),
    appVersion: draft.appVersion.trim(), osVersion: draft.osVersion.trim(), deviceModel: draft.deviceModel.trim(),
    processName: draft.processName.trim(), scene: draft.scene.trim(),
  }))
}
</script>

<template>
  <form class="panel filter-panel" aria-label="内存指标筛选" @submit.prevent="submit">
    <div class="filter-grid">
      <div class="field"><label for="memory-from">开始时间</label><input id="memory-from" v-model="draft.from" type="datetime-local" required /></div>
      <div class="field"><label for="memory-to">结束时间</label><input id="memory-to" v-model="draft.to" type="datetime-local" required /></div>
      <div class="field"><label for="memory-version">应用版本</label><input id="memory-version" v-model="draft.appVersion" /></div>
      <div class="field"><label for="memory-os">Android 版本</label><input id="memory-os" v-model="draft.osVersion" /></div>
      <div class="field"><label for="memory-device">设备型号</label><input id="memory-device" v-model="draft.deviceModel" /></div>
      <div class="field"><label for="memory-process">进程名</label><input id="memory-process" v-model="draft.processName" placeholder="例如 com.example.app" /></div>
      <div class="field"><label for="memory-scene">Activity 名称</label><input id="memory-scene" v-model="draft.scene" placeholder="例如 com.example.HomeActivity" /></div>
      <div class="field"><label for="memory-foreground">前后台</label><select id="memory-foreground" v-model="draft.foreground"><option value="">全部</option><option value="true">前台</option><option value="false">后台</option></select></div>
      <div class="field"><label for="memory-interval">趋势粒度</label><select id="memory-interval" v-model="draft.interval"><option value="hour">按小时（UTC）</option><option value="day">按天（UTC）</option></select></div>
    </div>
    <div class="filter-actions"><span class="subtitle">PSS、VSS、Java 堆按采样字节统计，缺失值不补零</span><button class="button button-primary" type="submit" :disabled="loading">{{ loading ? '查询中…' : '应用筛选' }}</button></div>
  </form>
</template>
