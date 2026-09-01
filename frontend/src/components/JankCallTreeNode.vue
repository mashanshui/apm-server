<script setup lang="ts">
import { computed, ref } from 'vue'
import type { JankCallTreeNode as Node } from '../types/jank'
import { callTreeLabel, formatNanoseconds } from '../utils/jankEvidence'

defineOptions({ name: 'JankCallTreeNode' })
const props = defineProps<{ node: Node; depth: number }>()
const expanded = ref(props.depth < 2)
const hasChildren = computed(() => props.node.children.length > 0)
</script>

<template>
  <li class="call-tree-item">
    <div class="call-tree-row" :style="{ paddingLeft: `${depth * 18 + 8}px` }">
      <button v-if="hasChildren" class="tree-toggle" type="button" :aria-expanded="expanded" :aria-label="`${expanded ? '折叠' : '展开'} ${callTreeLabel(node)}`" @click="expanded = !expanded">{{ expanded ? '−' : '+' }}</button>
      <span v-else class="tree-leaf" aria-hidden="true">·</span>
      <span class="tree-method" :title="callTreeLabel(node)">{{ callTreeLabel(node) }}</span>
      <span class="tree-duration">采样估算 {{ formatNanoseconds(node.estimatedDurationNs) }}</span>
      <span class="tree-unattributed">未归属 {{ formatNanoseconds(node.estimatedUnattributedDurationNs) }}</span>
    </div>
    <ul v-if="hasChildren && expanded" class="call-tree-list">
      <JankCallTreeNode v-for="(child, index) in node.children" :key="`${child.className}-${child.methodName}-${index}`" :node="child" :depth="depth + 1" />
    </ul>
  </li>
</template>
