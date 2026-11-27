<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { NativeCurrent } from '../types/crash'
import type { CrashDiagnostics } from '../types/crashDiagnostics'
import { frameText, partitionText, reasonText, threadStatus } from '../utils/diagnostics'
import NativeFrameList from './NativeFrameList.vue'

/** 当前事件已经授权返回的诊断现场；null 表示整份现场未采集。 */
const props = defineProps<{ diagnostics: CrashDiagnostics | null; nativeCrash?: NativeCurrent | null;
  appId?: string; appVersion?: string; buildId?: string; canManage?: boolean }>()
/** 当前展开的 Java 线程身份。 */
const selectedThreadId = ref<string | null>(null)
/** Native 线程按系统 TID 选择，不能与 Java ID 或线程名混同。 */
const selectedNativeTid = ref<number | null>(null)
/** 按采集顺序保留同名线程。 */
const threads = computed(() => props.diagnostics?.otherThreads?.threads ?? [])
/** 当前选中的线程。 */
const selectedThread = computed(() => threads.value.find(thread => thread.javaId === selectedThreadId.value) ?? null)
/** 原生线程保留采集顺序。 */
const nativeThreads = computed(() => props.diagnostics?.nativeThreads?.threads ?? [])
/** 当前选择的原生线程。 */
const selectedNativeThread = computed(() => nativeThreads.value.find(thread => thread.systemTid === selectedNativeTid.value) ?? null)

// 事件变化后优先显示主线程，并清除旧的线程选择。
watch(() => props.diagnostics, () => {
  selectedThreadId.value = threads.value.find(thread => thread.mainThread)?.javaId ?? threads.value[0]?.javaId ?? null
  selectedNativeTid.value = nativeThreads.value.find(thread => thread.mainThread)?.systemTid ?? nativeThreads.value[0]?.systemTid ?? null
}, { immediate: true })
</script>

<template>
  <section class="panel detail-card diagnostic-card" aria-label="线程快照">
    <div class="panel-header">
      <div><h2>线程快照</h2><p>线程为崩溃处理期间的近似快照；每个线程保留独立还原结果。</p></div>
    </div>
    <p v-if="!diagnostics" class="diagnostic-empty">此事件未采集诊断上下文。</p>
    <template v-else>
      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>崩溃线程</h3><span>{{ partitionText(diagnostics.crashedThread) }}</span></div>
        <dl v-if="diagnostics.crashedThread" class="diagnostic-grid">
          <div><dt>线程名</dt><dd>{{ diagnostics.crashedThread.name ?? '—' }}</dd></div>
          <div><dt>Java 线程 ID</dt><dd>{{ diagnostics.crashedThread.javaId ?? '—' }}</dd></div>
          <div><dt>系统 TID</dt><dd>{{ diagnostics.crashedThread.systemTid ?? '—' }}</dd></div>
          <div><dt>状态 / 优先级</dt><dd>{{ diagnostics.crashedThread.state ?? '—' }} / {{ diagnostics.crashedThread.priority ?? '—' }}</dd></div>
          <div><dt>主线程</dt><dd>{{ diagnostics.crashedThread.mainThread == null ? '未知' : diagnostics.crashedThread.mainThread ? '是' : '否' }}</dd></div>
          <div><dt>守护线程</dt><dd>{{ diagnostics.crashedThread.daemon == null ? '未知' : diagnostics.crashedThread.daemon ? '是' : '否' }}</dd></div>
        </dl>
      </div>

      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>{{ nativeCrash ? 'Java 线程快照' : '其他线程' }}</h3><span>{{ partitionText(diagnostics.otherThreads) }}</span></div>
        <p v-if="diagnostics.otherThreads" class="diagnostic-hint">观测总数 {{ diagnostics.otherThreads.observedCount ?? '未知' }} · 已保存 {{ threads.length }} · 遗漏 {{ diagnostics.otherThreads.omittedCount ?? '未知' }}</p>
        <p v-if="!threads.length" class="diagnostic-empty">{{ diagnostics.otherThreads?.status === 'ok' ? '采集成功，没有其他线程快照。' : '没有可展示的其他线程快照。' }}</p>
        <div v-else class="thread-layout">
          <div class="thread-list" role="tablist" aria-label="其他线程">
            <button v-for="thread in threads" :key="thread.javaId" type="button" role="tab"
              :aria-selected="thread.javaId === selectedThreadId" :class="{ active: thread.javaId === selectedThreadId }"
              @click="selectedThreadId = thread.javaId">
              <strong>{{ thread.name || '未命名线程' }}</strong><span>Java ID {{ thread.javaId }}<template v-if="thread.mainThread"> · 主线程</template></span>
              <small>{{ threadStatus(thread.symbolicationStatus) }}</small>
            </button>
          </div>
          <div v-if="selectedThread" class="thread-detail" role="tabpanel">
            <div class="thread-title"><strong>{{ selectedThread.name || '未命名线程' }}</strong><span>Java ID {{ selectedThread.javaId }}<template v-if="selectedThread.mainThread"> · 主线程</template></span></div>
            <p v-if="nativeCrash" class="diagnostic-hint">系统 TID {{ selectedThread.systemTid ?? '未知' }} · {{ selectedThread.relationToCrash === 'other' ? '已确认不是崩溃线程' : '与崩溃线程的关系未知' }}</p>
            <p class="diagnostic-hint">{{ selectedThread.state }} · 优先级 {{ selectedThread.priority }} · {{ selectedThread.daemon ? '守护线程' : '非守护线程' }}<template v-if="selectedThread.truncated"> · 堆栈已截断</template></p>
            <p class="diagnostic-hint">{{ threadStatus(selectedThread.symbolicationStatus) }}<template v-if="selectedThread.symbolicationReason"> · {{ reasonText(selectedThread.symbolicationReason) }}</template></p>
            <p v-if="selectedThread.symbolicationStatus === 'symbolicated'" class="diagnostic-hint">mapping {{ selectedThread.symbolFileId }} · revision {{ selectedThread.symbolFileRevision }} · SHA-256 {{ selectedThread.symbolFileSha256 }}</p>
            <RouterLink v-if="nativeCrash && canManage && appId && appVersion && selectedThread.symbolicationStatus !== 'symbolicated' && selectedThread.symbolicationStatus !== 'not_applicable'" class="button" :to="{ name: 'app-symbols', params: { appId }, query: { type: 'MAPPING', appVersion, ...(buildId ? { buildId } : {}) } }">上传此版本 mapping</RouterLink>
            <pre v-if="selectedThread.stack.format === 'retrace_text'" class="diagnostic-pre">{{ selectedThread.stack.content }}</pre>
            <p v-else-if="selectedThread.stack.content.length === 0" class="diagnostic-empty">没有可取得的 Java 堆栈帧。</p>
            <ol v-else class="diagnostic-frames"><li v-for="(frame, index) in selectedThread.stack.content" :key="index"><code>{{ frameText(frame) }}</code></li></ol>
          </div>
        </div>
      </div>

      <div v-if="nativeCrash" class="diagnostic-section">
        <div class="diagnostic-heading"><h3>其他 Native 线程</h3><span>{{ partitionText(diagnostics.nativeThreads) }}</span></div>
        <p v-if="diagnostics.nativeThreads" class="diagnostic-hint">观测总数 {{ diagnostics.nativeThreads.observedCount ?? '未知' }} · 已保存 {{ nativeThreads.length }} · 遗漏 {{ diagnostics.nativeThreads.omittedCount ?? '未知' }}</p>
        <p v-if="!nativeThreads.length" class="diagnostic-empty">没有可展示的其他 Native 线程快照。</p>
        <div v-else class="thread-layout">
          <div class="thread-list" role="tablist" aria-label="其他 Native 线程">
            <button v-for="thread in nativeThreads" :key="thread.systemTid" type="button" role="tab"
              :aria-selected="thread.systemTid === selectedNativeTid" :class="{ active: thread.systemTid === selectedNativeTid }"
              @click="selectedNativeTid = thread.systemTid">
              <strong>{{ thread.name || '未命名线程' }}</strong><span>系统 TID {{ thread.systemTid }}<template v-if="thread.mainThread"> · 主线程</template></span>
              <small>{{ partitionText(thread.backtrace) }}</small>
            </button>
          </div>
          <div v-if="selectedNativeThread" class="thread-detail" role="tabpanel">
            <div class="thread-title"><strong>{{ selectedNativeThread.name || '未命名线程' }}</strong><span>系统 TID {{ selectedNativeThread.systemTid }}</span></div>
            <p class="diagnostic-hint">{{ partitionText(selectedNativeThread.backtrace) }}</p>
            <NativeFrameList :frames="selectedNativeThread.backtrace.frames" :modules="nativeCrash.modules" />
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
