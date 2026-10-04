/** 单事件任务摘要，完整证据只由已分配 Worker 读取。 */
export interface AnalysisTask {
  /** 分析任务。 */ taskId: string
  /** 当前应用。 */ appId: string
  /** 只解释的事件。 */ eventId: string
  /** 原 Issue 指纹。 */ fingerprint: string
  /** 用于创建者取消权限。 */ createdBy: string
  /** 数据库创建时间。 */ createdAt: string
  /** 生命周期状态。 */ state: 'BLOCKED' | 'READY' | 'RUNNING' | 'CANCELLING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  /** 前提或准备失败原因。 */ blockReason: string | null
  /** 旧证据仅供查看，不开放旧执行入口。 */ evidenceSchemaVersion: number | null
  /** 固定证据 ID。 */ evidenceId: string | null
  /** 完整字节摘要。 */ evidenceSha256: string | null
  /** 终态内容已过期。 */ contentExpired: boolean
}

/** 可信 Worker 回传的结构化分析，所有文本按普通文本展示。 */
export interface AnalysisResult {
  /** 固定版本。 */ schemaVersion: number
  /** 当前证据。 */ evidenceId: string
  /** 版本 4 的当前服务端分配。 */ runId?: string
  /** 版本 3 历史源码快照。 */ snapshotId?: string
  /** 旧报告原始提交，仅供历史展示。 */ commitSha?: string
  /** 证据不足仍可正常完成。 */ conclusion: 'ROOT_CAUSE_CANDIDATE' | 'INSUFFICIENT_EVIDENCE'
  /** 人可读摘要。 */ summary: string
  /** 根因候选。 */ candidates: { title: string; reason: string; evidenceRefs: string[] }[]
  /** 相对源码引用；版本 4 片段自报及提交时检查分别标记。 */ sourceRefs: { path: string; startLine: number; endLine: number; snippet: string; snippetSha256: string; metadataSource?: 'HOST_REPORTED'; currentCheck?: 'CURRENT_MATCH' | 'CURRENT_DIFFERENT' | 'UNAVAILABLE' }[]
  /** 缺失事实。 */ unknowns: string[]
  /** 推断风险。 */ risks: string[]
  /** 未执行修复建议。 */ fixSuggestions: string[]
  /** 未执行验证建议。 */ validationSuggestions: string[]
  /** 宿主自报来源或旧历史执行器，无法核验的值为空。 */ execution: { providerId?: string | null; modelId?: string | null; opencodeVersion?: string | null; executionMode?: string; mode?: string; host?: string | null; hostVersion?: string | null; toolVersion?: string | null; metadataSource?: string }
  /** 版本 4 修改由宿主报告，版本 3 保留历史工具日志事实。 */ repair?: { status: 'NOT_REQUESTED' | 'NOT_APPLICABLE' | 'APPLIED' | 'PARTIAL' | 'CONFLICT' | 'FAILED'; files: { path: string; beforeSha256?: string | null; afterSha256?: string; summary?: string }[]; reason: string; metadataSource?: 'HOST_REPORTED' }
  /** 宿主自报命令；workspaceUnchanged 仅为版本 3 历史字段。 */ verification?: { status: 'PASSED' | 'FAILED' | 'NOT_RUN'; metadataSource: 'HOST_REPORTED'; commands: { command: string; exitCode: number; summary: string }[]; reason: string; workspaceUnchanged?: boolean }
  /** 缺失计数和费用保持未知。 */ usage: { inputTokens: number | null; outputTokens: number | null; cacheReadTokens: number | null; cacheWriteTokens: number | null; cost: null }
}

/** 公开尝试历史，无 Worker 或租约秘密。 */
export interface AnalysisRunDetail {
  /** 执行摘要。 */ run: { runId: string; taskId: string; attempt: number; state: string; taskState: string; stopConfirmed: boolean; errorCode: string | null; localToolsStopped?: boolean; hostStopState?: string }
  /** 失败或过期时可能为空。 */ result: AnalysisResult | null
  /** 明确内容到期。 */ contentExpired: boolean
}

/** 列表仅显示前缀，完整执行凭据不能从历史恢复。 */
export interface WorkerMetadata {
  /** 凭据对象 ID。 */ credentialId: string
  /** 固定应用。 */ appId: string
  /** 管理名称。 */ name: string
  /** 显示前缀。 */ displayPrefix: string
  /** 数据库创建时间。 */ createdAt: string
  /** 到期时间。 */ expiresAt: string
  /** 撤销时间。 */ revokedAt: string | null
}
