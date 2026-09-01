<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { JankFilterForm } from '../types/jank'
import { fromDateTimeLocal, toDateTimeLocal } from '../utils/format'

const props = withDefaults(defineProps<{
  modelValue: JankFilterForm
  loading?: boolean
  showFingerprint?: boolean
}>(), {
  loading: false,
  showFingerprint: true,
})

const emit = defineEmits<{ submit: [filters: JankFilterForm] }>()
const draft = reactive({ ...props.modelValue, from: '', to: '' })

function sync(next: JankFilterForm): void {
  Object.assign(draft, next, {
    from: toDateTimeLocal(next.from),
    to: toDateTimeLocal(next.to),
  })
}

watch(() => props.modelValue, sync, { deep: true, immediate: true })

function submit(): void {
  emit('submit', {
    from: fromDateTimeLocal(draft.from),
    to: fromDateTimeLocal(draft.to),
    appVersion: draft.appVersion.trim(),
    channel: draft.channel.trim(),
    environment: draft.environment.trim(),
    osVersion: draft.osVersion.trim(),
    deviceModel: draft.deviceModel.trim(),
    fingerprint: draft.fingerprint.trim(),
    scene: draft.scene.trim(),
    algorithmVersion: draft.algorithmVersion.trim(),
    interval: draft.interval,
  })
}
</script>

<template>
  <form class="panel filter-panel" aria-label="卡顿问题筛选" @submit.prevent="submit">
    <div class="filter-grid">
      <div class="field"><label for="jank-from">开始时间</label><input id="jank-from" v-model="draft.from" type="datetime-local" required /></div>
      <div class="field"><label for="jank-to">结束时间</label><input id="jank-to" v-model="draft.to" type="datetime-local" required /></div>
      <div class="field"><label for="jank-version">应用版本</label><input id="jank-version" v-model="draft.appVersion" placeholder="例如 3.2.0" /></div>
      <div class="field"><label for="jank-channel">渠道</label><input id="jank-channel" v-model="draft.channel" placeholder="例如 official" /></div>
      <div class="field"><label for="jank-environment">环境</label><input id="jank-environment" v-model="draft.environment" placeholder="例如 production" /></div>
      <div class="field"><label for="jank-os">Android 版本</label><input id="jank-os" v-model="draft.osVersion" placeholder="例如 16" /></div>
      <div class="field"><label for="jank-device">设备型号</label><input id="jank-device" v-model="draft.deviceModel" placeholder="例如 Pixel-8" /></div>
      <div class="field"><label for="jank-scene">场景</label><input id="jank-scene" v-model="draft.scene" placeholder="例如 feed" /></div>
      <div class="field"><label for="jank-algorithm">算法版本</label><input id="jank-algorithm" v-model="draft.algorithmVersion" placeholder="例如 jank-v1" /></div>
      <div v-if="showFingerprint" class="field"><label for="jank-fingerprint">问题指纹</label><input id="jank-fingerprint" v-model="draft.fingerprint" placeholder="可选" /></div>
      <div class="field">
        <label for="jank-interval">趋势粒度</label>
        <select id="jank-interval" v-model="draft.interval"><option value="hour">按小时</option><option value="day">按天</option></select>
      </div>
    </div>
    <div class="filter-actions">
      <span class="subtitle">默认最近 24 小时，查询状态会保存在 URL 中</span>
      <button class="button button-primary" type="submit" :disabled="loading">{{ loading ? '查询中…' : '应用筛选' }}</button>
    </div>
  </form>
</template>
