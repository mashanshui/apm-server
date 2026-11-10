<script setup lang="ts">
import type { NativeFrame, NativeModule } from '../types/crash'

/** 原始 Native 帧及事件内模块字典。 */
const props = defineProps<{ frames: NativeFrame[]; modules: NativeModule[] }>()

/** 只按事件内模块 ID 查找，不按名称推断模块身份。 */
function moduleName(id: string | null | undefined): string {
  return props.modules.find(module => module.id === id)?.name ?? '未知模块'
}
</script>

<template>
  <p v-if="!frames.length" class="native-empty">没有可展示的 Native 回溯帧。</p>
  <ol v-else class="native-frames">
    <li v-for="frame in frames" :key="frame.index">
      <span>#{{ frame.index }}</span>
      <code>{{ moduleName(frame.moduleId) }} · {{ frame.relativePc ?? '相对地址未知' }}<template v-if="frame.functionName"> · {{ frame.functionName }}<template v-if="frame.functionOffset"> + {{ frame.functionOffset }}</template></template></code>
    </li>
  </ol>
</template>

<style scoped>
.native-empty { color: var(--text-secondary, #667085); }
.native-frames { list-style: none; padding: 0; margin: 0; }
.native-frames li { display: flex; gap: 12px; padding: 6px 0; overflow-wrap: anywhere; }
.native-frames li > span { flex: 0 0 30px; color: var(--text-secondary, #667085); }
</style>
