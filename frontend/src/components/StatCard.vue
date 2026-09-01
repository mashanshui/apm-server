<script setup lang="ts">
defineProps<{
  label: string
  value: string
  footnote: string
  tone?: 'default' | 'warning' | 'success' | 'danger'
}>()
</script>

<template>
  <section class="panel stat-card" :class="tone && tone !== 'default' ? `stat-${tone}` : ''">
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value">{{ value }}</div>
    <div class="stat-footnote">{{ footnote }}</div>
  </section>
</template>
