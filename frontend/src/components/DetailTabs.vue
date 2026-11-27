<script setup lang="ts">
import { ref } from 'vue'

/** 详情页分区切换按钮条；仅负责选中态展示与切换事件，面板显隐由父页面控制。 */
const props = defineProps<{
  /** 可切换分区；key 与父页面面板一一对应，label 为按钮文案。 */
  tabs: ReadonlyArray<{ key: string; label: string }>
  /** 当前选中的分区 key。 */
  modelValue: string
  /** 当前详情页的标识前缀，用于关联标签和面板。 */
  idPrefix: string
}>()
/** 将新的分区 key 回传给父页面。 */
const emit = defineEmits<{ (event: 'update:modelValue', key: string): void }>()
/** 当前标签列表，用于将键盘焦点限制在本组件内。 */
const tabList = ref<HTMLDivElement | null>(null)

/** 选择分区并记录切换操作。 */
function selectTab(key: string): void {
  emit('update:modelValue', key)
  console.debug('[DetailTabs] 详情分区已切换', { key })
}

/** 使用方向键及首尾键切换分区，并将焦点移到新的活动标签。 */
function moveTab(event: KeyboardEvent, index: number): void {
  /** 标签总数用于首尾循环。 */
  const count = props.tabs.length
  if (!count) return
  /** 根据键盘操作计算下一个标签的位置。 */
  let nextIndex: number
  switch (event.key) {
    case 'ArrowLeft': nextIndex = (index - 1 + count) % count; break
    case 'ArrowRight': nextIndex = (index + 1) % count; break
    case 'Home': nextIndex = 0; break
    case 'End': nextIndex = count - 1; break
    default: return
  }
  event.preventDefault()
  selectTab(props.tabs[nextIndex].key)
  tabList.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[nextIndex]?.focus()
}
</script>

<template>
  <div ref="tabList" class="detail-tabs" role="tablist" aria-label="详情分区切换">
    <button v-for="(tab, index) in tabs" :id="`${idPrefix}-tab-${tab.key}`" :key="tab.key" type="button" role="tab"
      :aria-controls="`${idPrefix}-panel-${tab.key}`" :aria-selected="tab.key === modelValue"
      :tabindex="tab.key === modelValue ? 0 : -1" :class="{ active: tab.key === modelValue }"
      @click="selectTab(tab.key)" @keydown="moveTab($event, index)">{{ tab.label }}</button>
  </div>
</template>
