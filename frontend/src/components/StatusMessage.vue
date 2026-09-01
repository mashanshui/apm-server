<script setup lang="ts">
withDefaults(defineProps<{
  kind: 'loading' | 'empty' | 'error'
  message: string
  actionLabel?: string
}>(), {
  actionLabel: '重试',
})

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div :class="`${kind}-state`" role="status">
    <div>
      <p>{{ message }}</p>
      <button
        v-if="kind === 'error'"
        class="button"
        type="button"
        @click="emit('retry')"
      >
        {{ actionLabel }}
      </button>
    </div>
  </div>
</template>
