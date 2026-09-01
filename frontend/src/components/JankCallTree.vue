<script setup lang="ts">
import type { JankCallTreeNode } from '../types/jank'
import JankCallTreeNodeView from './JankCallTreeNode.vue'
defineProps<{ nodes: JankCallTreeNode[] }>()
</script>

<template>
  <div v-if="nodes.length === 0" class="empty-state"><p>没有可展示的采样估算调用树</p></div>
  <div v-else class="call-tree" role="tree" aria-label="采样估算调用树">
    <ul class="call-tree-list"><JankCallTreeNodeView v-for="(node, index) in nodes" :key="`${node.className}-${node.methodName}-${index}`" :node="node" :depth="0" /></ul>
  </div>
</template>
