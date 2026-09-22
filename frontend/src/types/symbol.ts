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
