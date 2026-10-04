import { pathSegment, requestJson } from './http'
import type { AnalysisRunDetail, AnalysisTask, WorkerMetadata } from '../types/analysis'

/** 所有网页接口沿用 Session 和 CSRF，不持有模型或 Worker 执行认证。 */
export const analysisApi = {
  /** 单事件下有界历史。 */
  list(appId: string, eventId: string, page = 0, signal?: AbortSignal) {
    return requestJson<AnalysisTask[]>(`/api/v1/apps/${pathSegment(appId)}/crashes/events/${pathSegment(eventId)}/analyses?page=${page}&size=20`, { signal })
  },
  /** 同一次创建重试沿用稳定 UUID。 */
  create(appId: string, eventId: string, key: string, signal?: AbortSignal) {
    return requestJson<AnalysisTask>(`/api/v1/apps/${pathSegment(appId)}/crashes/events/${pathSegment(eventId)}/analyses`, {
      method: 'POST', body: JSON.stringify({ idempotencyKey: key }), signal,
    })
  },
  /** 只有明确动作可改变任务，后端再次核验当前角色。 */
  action(appId: string, taskId: string, action: 'recheck' | 'retry' | 'cancel', signal?: AbortSignal) {
    return requestJson<AnalysisTask>(`/api/v1/apps/${pathSegment(appId)}/analysis-tasks/${pathSegment(taskId)}/${action}`, { method: 'POST', signal })
  },
  /** 单任务每次尝试和结果在服务端分页。 */
  runs(appId: string, taskId: string, page = 0, signal?: AbortSignal) {
    return requestJson<AnalysisRunDetail[]>(`/api/v1/apps/${pathSegment(appId)}/analysis-tasks/${pathSegment(taskId)}/runs?page=${page}&size=20`, { signal })
  },
  /** 列出 Worker 元数据，禁止记录秘密。 */
  workers(appId: string, page = 0, signal?: AbortSignal) {
    return requestJson<WorkerMetadata[]>(`/api/v1/apps/${pathSegment(appId)}/analysis-workers?page=${page}&size=20`, { signal })
  },
  /** 一次展示的完整值只返回本次创建。 */
  createWorker(appId: string, name: string, signal?: AbortSignal) {
    return requestJson<{ metadata: WorkerMetadata; credential: string }>(`/api/v1/apps/${pathSegment(appId)}/analysis-workers`, { method: 'POST', body: JSON.stringify({ name }), signal })
  },
  /** 管理员撤销所属应用凭据。 */
  revokeWorker(appId: string, id: string, signal?: AbortSignal) {
    return requestJson<void>(`/api/v1/apps/${pathSegment(appId)}/analysis-workers/${pathSegment(id)}`, { method: 'DELETE', signal })
  },
  /** 人工检查明确环境停止后留下核验记录。 */
  confirmStopped(appId: string, runId: string, basis: string, signal?: AbortSignal) {
    return requestJson<void>(`/api/v1/apps/${pathSegment(appId)}/analysis-runs/${pathSegment(runId)}/confirm-stopped`, { method: 'POST', body: JSON.stringify({ basis }), signal })
  },
}
