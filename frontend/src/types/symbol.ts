export interface SymbolFileMetadata {
  symbolId: string
  appId: string
  /** 文件类型及不可变登记版本。 */
  type: 'MAPPING' | 'NATIVE_SO'
  appVersion: string | null
  mappingScope: 'default' | 'build' | null
  buildId: string | null
  /** SO 的实际 ELF 身份，mapping 不适用。 */
  abi: string | null
  elfBuildId: string | null
  hasFunctionSymbols: boolean | null
  hasDebugInfo: boolean | null
  hasDebugLine: boolean | null
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

/** SO 批次冲突包含所有候选，新建项 current 为空。 */
export interface NativeSnapshot {
  abi: string
  elfBuildId: string
  incomingSha256: string
  current: SymbolFileMetadata | null
}
/** 用户确认全批的每个身份，缺失记录也必须明确确认。 */
export interface NativeExpected {
  abi: string
  elfBuildId: string
  symbolId: string | null
  revision: number | null
}
/** 服务端原子提交结果。 */
export interface NativeBatchResult {
  items: SymbolFileMetadata[]
  createdCount: number
  replacedCount: number
  unchangedCount: number
}
