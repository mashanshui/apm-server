<script setup lang="ts">
import type { CrashPayload } from '../types/crash'

defineProps<{ crash: CrashPayload | null }>()

function frameLocation(fileName: string, lineNumber: number | null): string {
  return `${fileName || '未知文件'}:${lineNumber ?? '?'}`
}
</script>

<template>
  <section class="panel detail-card">
    <div class="panel-header" style="padding: 0 0 16px">
      <div>
        <h2>异常链与堆栈</h2>
        <p>展示服务端脱敏后的原始 Crash 内容</p>
      </div>
    </div>

    <div v-if="!crash || crash.throwableChain.length === 0" class="empty-state" style="min-height: 120px; padding: 0">
      <p>没有可展示的异常链</p>
    </div>
    <div v-else>
      <div v-for="(throwable, throwableIndex) in crash.throwableChain" :key="`${throwable.type}-${throwableIndex}`" class="stack-section">
        <div class="stack-title">
          <h3>{{ throwable.type || '未知异常' }}</h3>
          <span>异常 {{ throwableIndex + 1 }}</span>
        </div>
        <div class="exception-card">
          <div class="exception-head">
            <div class="exception-type">{{ throwable.type || 'UnknownThrowable' }}</div>
            <p class="exception-message">{{ throwable.message || '无异常消息' }}</p>
          </div>
          <ol class="frame-list">
            <li v-for="(frame, frameIndex) in throwable.frames" :key="`${frame.className}-${frame.methodName}-${frameIndex}`" class="frame-item">
              <span class="frame-index">{{ frameIndex }}</span>
              <span class="frame-main">
                {{ frame.className }}.{{ frame.methodName }}
                <span class="frame-location">{{ frameLocation(frame.fileName, frame.lineNumber) }}</span>
              </span>
              <span v-if="frame.applicationFrame" class="frame-app">APP</span>
            </li>
          </ol>
        </div>
      </div>
    </div>
  </section>
</template>
