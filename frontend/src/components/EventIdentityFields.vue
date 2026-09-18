<script setup lang="ts">
import { ref } from 'vue'

interface Props {
  /** 脱敏后的安装级设备身份。 */
  anonymousDeviceId: string | null
  /** 客户端启动级会话身份。 */
  sessionId: string | null
  /** 客户端生成的进程实例 UUID。 */
  processId: string | null
}

const props = defineProps<Props>()
/** 当前已经完成复制的身份字段，用于给按钮提供短暂的成功状态。 */
const copiedField = ref<string | null>(null)
/** 最近一次复制失败时展示的用户反馈。 */
const copyError = ref<string | null>(null)

/** 判断身份值是否可以复制，同时保留原始字符串的大小写和内容。 */
function hasValue(value: string | null): value is string {
  return value !== null && value.trim().length > 0
}

/** 将缺失身份转换为统一的详情页占位符。 */
function displayValue(value: string | null): string {
  return hasValue(value) ? value : '—'
}

/** 调用浏览器剪贴板复制完整身份，并反馈成功或权限失败。 */
async function copyValue(label: string, value: string | null) {
  copiedField.value = null
  copyError.value = null
  if (!hasValue(value)) {
    return
  }
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('clipboard unavailable')
    }
    await navigator.clipboard.writeText(value)
    copiedField.value = label
  } catch {
    copyError.value = '复制' + label + ' 失败，请检查浏览器剪贴板权限。'
  }
}
</script>

<template>
  <dl class="meta-grid identity-fields">
    <div class="meta-item">
      <dt>设备 ID</dt>
      <dd class="identity-value">
        <code>{{ displayValue(props.anonymousDeviceId) }}</code>
        <button
          class="identity-copy-button"
          type="button"
          :disabled="!hasValue(props.anonymousDeviceId)"
          aria-label="复制设备 ID"
          @click="copyValue('设备 ID', props.anonymousDeviceId)"
        >复制</button>
      </dd>
    </div>
    <div class="meta-item">
      <dt>启动 ID</dt>
      <dd class="identity-value">
        <code>{{ displayValue(props.sessionId) }}</code>
        <button
          class="identity-copy-button"
          type="button"
          :disabled="!hasValue(props.sessionId)"
          aria-label="复制启动 ID"
          @click="copyValue('启动 ID', props.sessionId)"
        >复制</button>
      </dd>
    </div>
    <div class="meta-item">
      <dt>进程 ID</dt>
      <dd class="identity-value">
        <code>{{ displayValue(props.processId) }}</code>
        <button
          class="identity-copy-button"
          type="button"
          :disabled="!hasValue(props.processId)"
          aria-label="复制进程 ID"
          @click="copyValue('进程 ID', props.processId)"
        >复制</button>
      </dd>
    </div>
  </dl>
  <p v-if="copiedField" class="identity-feedback identity-feedback-success" role="status">已复制{{ copiedField }}。</p>
  <p v-if="copyError" class="identity-feedback identity-feedback-error" role="alert">{{ copyError }}</p>
</template>
