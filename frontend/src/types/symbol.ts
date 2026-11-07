export interface SymbolFileMetadata {
  symbolId: string
  appId: string
  buildId: string
  revision: number
  originalFilename: string
  sizeBytes: number
  sha256: string
  uploadedBy: string
  uploadedAt: string
  updatedAt: string
}

export interface SymbolFilePage {
  appId: string
  items: SymbolFileMetadata[]
  nextCursor: string | null
}

/** 构建本轮后台进展；idle 不证明每条事件已成功。 */
export interface SymbolicationProgress {
  state: 'queued' | 'running' | 'waiting_mapping' | 'retry_wait' | 'idle'
  succeededCount: number
  failedCount: number
  updatedAt: string | null
  errorCategory: string | null
}
