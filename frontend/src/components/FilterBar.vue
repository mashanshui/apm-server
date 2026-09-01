<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { CrashFilterForm } from '../types/crash'
import { fromDateTimeLocal, toDateTimeLocal } from '../utils/format'

const props = defineProps<{
  modelValue: CrashFilterForm
  loading?: boolean
}>()

const emit = defineEmits<{
  submit: [filters: CrashFilterForm]
}>()

const draft = reactive({
  from: toDateTimeLocal(props.modelValue.from),
  to: toDateTimeLocal(props.modelValue.to),
  appVersion: props.modelValue.appVersion,
  channel: props.modelValue.channel,
  environment: props.modelValue.environment,
  osVersion: props.modelValue.osVersion,
  deviceModel: props.modelValue.deviceModel,
  fingerprint: props.modelValue.fingerprint,
  interval: props.modelValue.interval,
})

watch(() => props.modelValue, (next) => {
  draft.from = toDateTimeLocal(next.from)
  draft.to = toDateTimeLocal(next.to)
  draft.appVersion = next.appVersion
  draft.channel = next.channel
  draft.environment = next.environment
  draft.osVersion = next.osVersion
  draft.deviceModel = next.deviceModel
  draft.fingerprint = next.fingerprint
  draft.interval = next.interval
}, { deep: true })

function submit() {
  emit('submit', {
    from: fromDateTimeLocal(draft.from),
    to: fromDateTimeLocal(draft.to),
    appVersion: draft.appVersion.trim(),
    channel: draft.channel.trim(),
    environment: draft.environment.trim(),
    osVersion: draft.osVersion.trim(),
    deviceModel: draft.deviceModel.trim(),
    fingerprint: draft.fingerprint.trim(),
    interval: draft.interval,
  })
}
</script>

<template>
  <form class="panel filter-panel" @submit.prevent="submit">
    <div class="filter-grid">
      <div class="field">
        <label for="from">开始时间</label>
        <input id="from" v-model="draft.from" type="datetime-local" required />
      </div>
      <div class="field">
        <label for="to">结束时间</label>
        <input id="to" v-model="draft.to" type="datetime-local" required />
      </div>
      <div class="field">
        <label for="app-version">应用版本</label>
        <input id="app-version" v-model="draft.appVersion" placeholder="例如 3.2.0" />
      </div>
      <div class="field">
        <label for="channel">渠道</label>
        <input id="channel" v-model="draft.channel" placeholder="例如 official" />
      </div>
      <div class="field">
        <label for="environment">环境</label>
        <input id="environment" v-model="draft.environment" placeholder="例如 production" />
      </div>
      <div class="field">
        <label for="os-version">Android 版本</label>
        <input id="os-version" v-model="draft.osVersion" placeholder="例如 16" />
      </div>
      <div class="field">
        <label for="device-model">设备型号</label>
        <input id="device-model" v-model="draft.deviceModel" placeholder="例如 Pixel-8" />
      </div>
      <div class="field">
        <label for="fingerprint">问题指纹</label>
        <input id="fingerprint" v-model="draft.fingerprint" placeholder="可选" />
      </div>
      <div class="field">
        <label for="interval">趋势粒度</label>
        <select id="interval" v-model="draft.interval">
          <option value="hour">按小时</option>
          <option value="day">按天</option>
        </select>
      </div>
    </div>
    <div class="filter-actions">
      <span class="subtitle">默认查询最近 24 小时，最大范围 31 天</span>
      <button class="button button-primary" type="submit" :disabled="loading">
        {{ loading ? '查询中…' : '应用筛选' }}
      </button>
    </div>
  </form>
</template>
