<script setup lang="ts">
import { computed } from 'vue'
import type { NativeCurrentFrame, NativeFrame, NativeModule } from '../types/crash'

/** 原始 Native 帧及事件内模块字典。 */
const props = defineProps<{ frames: NativeCurrentFrame[]; modules: NativeModule[] }>()

/** 当前列表是否包含已固定的还原结果。 */
const hasSymbolicatedFrames = computed(() => props.frames.some(frame => frame.resolution.status === 'symbolicated'))

/** 按原帧生成可复制的 Native 堆栈行，不补猜地址、函数或模块身份。 */
function frameText(frame: NativeFrame): string {
  const module = props.modules.find(item => item.id === frame.moduleId)
  const index = String(frame.index).padStart(2, '0')
  const address = frame.relativePc ?? '相对地址未知'
  const location = module?.path || module?.name || '未知模块'
  const captured = frame.functionName ? ` (${frame.functionName}${frame.functionOffset ? `+${frame.functionOffset}` : ''})` : ''
  const identity = module ? ` [${module.abi}::${module.elfBuildId ?? 'Build ID 未知'}]` : ''
  return `#${index} pc ${address} ${location}${captured}${identity}`
}

/** 将逐帧处理状态解释为用户可读文本。 */
function stateText(status: string): string {
  return ({ pending: '等待还原', failed: '还原失败', symbolicated: '已还原', not_applicable: '无法还原' } as Record<string, string>)[status] ?? '状态未知'
}

/** 解释已知的安全原因；未知原因保留原始标识，便于排查。 */
function reasonText(reason: string): string {
  const labels: Record<string, string> = {
    native_symbolication_deferred: '等待后台处理',
    missing_so: '缺少匹配的 SO 符号文件',
    missing_module_identity: '缺少模块身份或相对地址',
    native_no_function: '符号文件未提供该地址的函数名',
    native_store_unavailable: '符号文件暂时不可读取',
    native_tool_timeout: '符号还原超时，等待重试',
    native_tool_busy: '符号还原资源繁忙，等待重试',
    native_tool_unavailable: '符号还原工具不可用',
    native_tool_failed: '符号还原工具执行失败',
    native_tool_interrupted: '符号还原被中断，等待重试',
    native_tool_output_limit: '符号还原工具输出超限',
    native_output_limit: '本轮还原结果超限',
    native_input_limit: '还原输入超限',
    native_invalid_output_or_request: '还原输入或输出无效',
    native_sandbox_unavailable: '符号还原隔离环境不可用',
    native_tool_version_unsupported: '符号还原工具版本不支持',
  }
  return labels[reason] ?? `处理原因：${reason}`
}
</script>

<template>
  <p v-if="!frames.length" class="native-empty">没有可展示的 Native 回溯帧。</p>
  <div v-else class="native-stack">
    <p v-if="hasSymbolicatedFrames" class="native-fixed-note">已还原的帧会保留首次成功结果，后续替换符号文件不会升级这些结果。</p>
    <ol class="native-frames">
      <li v-for="frame in frames" :key="frame.index" class="native-frame" :class="{ 'native-frame--resolved': frame.resolution.status === 'symbolicated' }">
        <span class="frame-index" aria-hidden="true">{{ frame.index + 1 }}</span>
        <div class="frame-content">
          <div class="frame-heading">
            <code class="frame-raw">{{ frameText(frame) }}</code>
            <span class="frame-status" :class="`frame-status--${frame.resolution.status}`">{{ stateText(frame.resolution.status) }}</span>
          </div>
          <p v-if="frame.resolution.reason" class="frame-reason">{{ reasonText(frame.resolution.reason) }}</p>
            <ol v-if="frame.resolution.inline?.length" class="frame-inline" aria-label="还原的函数调用">
              <li v-for="(item, index) in frame.resolution.inline" :key="index" class="frame-inline-item">
                <code class="frame-function">{{ item.function ?? '函数未知' }}</code>
                <code class="frame-file">{{ item.file ?? '源码未知' }}<template v-if="item.line != null">:{{ item.line }}</template><template v-else> · 行号未知</template><template v-if="item.column != null">:{{ item.column }}</template></code>
              </li>
            </ol>
            <details v-if="frame.resolution.source || frame.resolution.attemptedSource" class="frame-source">
              <summary>{{ frame.resolution.source ? '查看符号来源' : '查看尝试的符号来源' }}</summary>
              <dl><dt>SO 文件 ID</dt><dd><code>{{ (frame.resolution.source ?? frame.resolution.attemptedSource)?.symbolFileId }}</code></dd>
                <dt>修订号</dt><dd>{{ (frame.resolution.source ?? frame.resolution.attemptedSource)?.revision }}</dd>
                <dt>SHA-256</dt><dd><code>{{ (frame.resolution.source ?? frame.resolution.attemptedSource)?.sha256 }}</code></dd></dl>
            </details>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.native-empty, .native-fixed-note { color: var(--text-secondary, #667085); }
.native-fixed-note { margin: 0 0 12px; font-size: 12px; line-height: 1.5; }
.native-frames { list-style: none; padding: 0; margin: 0; border: 1px solid #e5eaf1; border-radius: 6px; overflow: hidden; }
.native-frame { display: grid; grid-template-columns: 48px minmax(0, 1fr); background: #fff; }
.native-frame + .native-frame { border-top: 1px solid #e5eaf1; }
.native-frame:hover { background: #fffbeb; }
.frame-index { padding: 12px 0; text-align: center; color: #8993a1; background: #f7f8fa; border-right: 1px solid #e5eaf1; font: 12px/1.8 ui-monospace, SFMono-Regular, Consolas, monospace; }
.frame-content { min-width: 0; padding: 12px 16px; }
.frame-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px 12px; }
.frame-raw { min-width: 0; flex: 1 1 400px; color: #273449; font: 13px/1.8 ui-monospace, SFMono-Regular, Consolas, monospace; overflow-wrap: anywhere; white-space: pre-wrap; }
.native-frame--resolved .frame-raw { color: #2471c8; }
.frame-status { flex: none; border-radius: 999px; padding: 3px 9px; color: #667085; background: #f2f4f7; font-size: 12px; line-height: 1.4; }
.frame-status--symbolicated { color: #067647; background: #ecfdf3; }
.frame-status--pending { color: #946200; background: #fffaeb; }
.frame-status--failed { color: #b42318; background: #fef3f2; }
.frame-reason { margin: 7px 0 0; color: #667085; font-size: 12px; overflow-wrap: anywhere; }
.frame-inline { list-style: none; margin: 9px 0 0; padding: 0; }
.frame-inline-item { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; padding: 3px 0; overflow-wrap: anywhere; }
.frame-function { color: #1f4e93; font-weight: 600; }
.frame-file { color: #667085; font-size: 12px; }
.frame-source { margin-top: 8px; color: #667085; font-size: 12px; }
.frame-source summary { width: fit-content; cursor: pointer; color: #4f6f9e; }
.frame-source dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 12px; margin: 8px 0 0; }
.frame-source dt { color: #8290a3; }
.frame-source dd { margin: 0; overflow-wrap: anywhere; }
</style>
