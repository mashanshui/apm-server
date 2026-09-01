<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { JankSampleSlice, JankStackFrame } from '../types/jank'
import { formatNanoseconds } from '../utils/jankEvidence'

const props = defineProps<{
  slices: JankSampleSlice[]
  stackDictionary: Record<string, JankStackFrame[]>
  exactMessageDurationNs: number
}>()

const selectedIndex = ref<number | null>(null)
watch(() => props.slices, () => { selectedIndex.value = null })
const timelineDuration = computed(() => Math.max(
  props.exactMessageDurationNs,
  ...props.slices.map((slice) => slice.endOffsetNs),
  1,
))
const selected = computed(() => selectedIndex.value === null ? null : props.slices[selectedIndex.value] ?? null)
const selectedFrames = computed(() => {
  const stackId = selected.value?.stackId
  return stackId ? props.stackDictionary[stackId] ?? [] : []
})

function styleOf(slice: JankSampleSlice): Record<string, string> {
  return {
    left: `${Math.max(0, slice.startOffsetNs) / timelineDuration.value * 100}%`,
    width: `${Math.max(0.35, Math.max(0, slice.endOffsetNs - slice.startOffsetNs) / timelineDuration.value * 100)}%`,
  }
}
</script>

<template>
  <div v-if="slices.length === 0" class="empty-state"><p>没有可展示的采样时间片</p></div>
  <div v-else class="jank-timeline">
    <div class="timeline-legend"><span><i class="legend-covered" />采样覆盖</span><span><i class="legend-gap" />未覆盖空洞</span><span>总轴：{{ formatNanoseconds(timelineDuration) }}</span></div>
    <div class="timeline-scroll" tabindex="0" aria-label="可横向滚动的卡顿采样时间片">
      <div class="timeline-track">
        <button
          v-for="(slice, index) in slices"
          :key="`${slice.startOffsetNs}-${slice.endOffsetNs}-${index}`"
          class="timeline-slice"
          :class="slice.covered ? 'timeline-covered' : 'timeline-gap'"
          :style="styleOf(slice)"
          type="button"
          :aria-label="`${slice.covered ? '采样覆盖' : '未覆盖空洞'}，${formatNanoseconds(slice.startOffsetNs)} 至 ${formatNanoseconds(slice.endOffsetNs)}`"
          @click="selectedIndex = index"
        ><span>{{ slice.covered ? (slice.stackId || '采样') : '空洞' }}</span></button>
      </div>
    </div>
    <div v-if="selected" class="timeline-detail">
      <h3>{{ selected.covered ? '采样时间片事实' : '未覆盖空洞' }}</h3>
      <p>{{ formatNanoseconds(selected.startOffsetNs) }} — {{ formatNanoseconds(selected.endOffsetNs) }}，时长 {{ formatNanoseconds(selected.durationNs) }}</p>
      <ol v-if="selected.covered && selectedFrames.length" class="sample-frame-list">
        <li v-for="(frame, index) in selectedFrames" :key="`${frame.className}-${frame.methodName}-${index}`">
          <code>{{ frame.className }}.{{ frame.methodName }}</code>
          <span v-if="frame.fileName || frame.lineNumber !== null">{{ frame.fileName || '未知文件' }}<template v-if="frame.lineNumber !== null">:{{ frame.lineNumber }}</template></span>
        </li>
      </ol>
      <p v-else-if="selected.covered">堆栈字典中没有该采样的可用帧。</p>
      <p v-else>此区间没有成功采样，不分配给任何方法。</p>
    </div>
  </div>
</template>
