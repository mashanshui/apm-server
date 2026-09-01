<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { JankDimension, JankMetricFilterForm } from '../types/jank'
import { fromDateTimeLocal, toDateTimeLocal } from '../utils/format'
import { normalizeJankMetricFilters } from '../utils/jankQuery'

const props = defineProps<{ modelValue: JankMetricFilterForm; loading?: boolean }>()
const emit = defineEmits<{ submit: [filters: JankMetricFilterForm] }>()
const draft = reactive({ ...props.modelValue, from: '', to: '' })
const dimensions: { value: JankDimension; label: string }[] = [
  { value: 'appVersion', label: '应用版本' }, { value: 'channel', label: '渠道' }, { value: 'environment', label: '环境' },
  { value: 'osVersion', label: 'Android 版本' }, { value: 'deviceModel', label: '设备型号' }, { value: 'scene', label: '场景' },
  { value: 'algorithmVersion', label: '算法版本' },
]
const availableDimensions = computed(() => dimensions.filter((item) => props.modelValue.metric === 'fps' || item.value !== 'scene'))

watch(() => props.modelValue, (next) => Object.assign(draft, next, { from: toDateTimeLocal(next.from), to: toDateTimeLocal(next.to) }), { deep: true, immediate: true })

function submit(): void {
  emit('submit', normalizeJankMetricFilters({
    from: fromDateTimeLocal(draft.from), to: fromDateTimeLocal(draft.to), metric: draft.metric,
    interval: draft.interval, dimension: draft.dimension, appVersion: draft.appVersion.trim(), channel: draft.channel.trim(),
    environment: draft.environment.trim(), osVersion: draft.osVersion.trim(), deviceModel: draft.deviceModel.trim(),
    scene: draft.scene.trim(), algorithmVersion: draft.algorithmVersion.trim(),
  }))
}
</script>

<template>
  <form class="panel filter-panel" aria-label="卡顿指标筛选" @submit.prevent="submit">
    <div class="filter-grid">
      <div class="field"><label for="metric-from">开始时间</label><input id="metric-from" v-model="draft.from" type="datetime-local" required /></div>
      <div class="field"><label for="metric-to">结束时间</label><input id="metric-to" v-model="draft.to" type="datetime-local" required /></div>
      <div class="field"><label for="metric-version">应用版本</label><input id="metric-version" v-model="draft.appVersion" /></div>
      <div class="field"><label for="metric-channel">渠道</label><input id="metric-channel" v-model="draft.channel" /></div>
      <div class="field"><label for="metric-environment">环境</label><input id="metric-environment" v-model="draft.environment" /></div>
      <div class="field"><label for="metric-os">Android 版本</label><input id="metric-os" v-model="draft.osVersion" /></div>
      <div class="field"><label for="metric-device">设备型号</label><input id="metric-device" v-model="draft.deviceModel" /></div>
      <div v-if="modelValue.metric === 'fps'" class="field"><label for="metric-scene">场景</label><input id="metric-scene" v-model="draft.scene" /></div>
      <div class="field"><label for="metric-algorithm">算法版本</label><input id="metric-algorithm" v-model="draft.algorithmVersion" /></div>
      <div class="field"><label for="metric-interval">趋势粒度</label><select id="metric-interval" v-model="draft.interval" :disabled="modelValue.metric === 'suspension_rate'"><option value="hour">按小时</option><option value="day">按天（UTC）</option></select></div>
      <div class="field"><label for="metric-dimension">多维分析</label><select id="metric-dimension" v-model="draft.dimension"><option v-for="item in availableDimensions" :key="item.value" :value="item.value">{{ item.label }}</option></select></div>
    </div>
    <div class="filter-actions"><span class="subtitle">{{ modelValue.metric === 'fps' ? 'FPS 分位数按从高到低口径解释' : '挂起率固定按 UTC 设备日聚合' }}</span><button class="button button-primary" type="submit" :disabled="loading">{{ loading ? '查询中…' : '应用筛选' }}</button></div>
  </form>
</template>
