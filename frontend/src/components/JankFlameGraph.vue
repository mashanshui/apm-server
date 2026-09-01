<script setup lang="ts">
import { computed, ref } from 'vue'
import type { JankCallTreeNode } from '../types/jank'
import { buildFlameLayout, formatNanoseconds, MAX_FLAME_NODES, type FlameRect } from '../utils/jankEvidence'
import JankCallTree from './JankCallTree.vue'

const props = defineProps<{ nodes: JankCallTreeNode[] }>()
const rowHeight = 28
const rectangles = computed(() => buildFlameLayout(props.nodes))
const maxDepth = computed(() => rectangles.value.reduce((max, item) => Math.max(max, item.depth), 0))
const height = computed(() => (maxDepth.value + 1) * rowHeight + 8)
const selected = ref<FlameRect | null>(null)
function color(depth: number): string { return ['#2768c7', '#2e7ee0', '#3897c6', '#24a476', '#d28a20'][depth % 5] }
</script>

<template>
  <div v-if="rectangles.length === 0" class="empty-state"><p>没有可展示的采样估算火焰图</p></div>
  <div v-else class="flame-panel">
    <p class="evidence-note">矩形宽度只表示调用树中的采样估算时长；未覆盖空洞不会填入火焰图。</p>
    <div class="flame-scroll" tabindex="0">
      <svg class="flame-svg" :viewBox="`0 0 1000 ${height}`" role="img" aria-label="采样估算火焰图">
        <g v-for="rect in rectangles" :key="rect.id" class="flame-node" tabindex="0" role="button" @click="selected = rect" @keydown.enter.prevent="selected = rect" @keydown.space.prevent="selected = rect">
          <rect :x="rect.x" :y="height - (rect.depth + 1) * rowHeight" :width="Math.max(rect.width - 1, 0.5)" :height="rowHeight - 2" :fill="color(rect.depth)" rx="2"><title>{{ rect.label }} · 采样估算 {{ formatNanoseconds(rect.estimatedDurationNs) }}</title></rect>
          <text v-if="rect.width >= 55" :x="rect.x + 5" :y="height - (rect.depth + 1) * rowHeight + 17">{{ rect.label }}</text>
        </g>
      </svg>
    </div>
    <div v-if="selected" class="flame-selection"><strong>{{ selected.label }}</strong><span>采样估算 {{ formatNanoseconds(selected.estimatedDurationNs) }}</span><span>未归属采样估算 {{ formatNanoseconds(selected.estimatedUnattributedDurationNs) }}</span></div>
    <p v-if="rectangles.length >= MAX_FLAME_NODES" class="notice">火焰图为保证交互性能最多绘制 {{ MAX_FLAME_NODES }} 个节点，可使用下方树形替代内容继续浏览。</p>
    <details class="accessible-tree"><summary>查看可访问的树形替代内容</summary><JankCallTree :nodes="nodes" /></details>
  </div>
</template>
