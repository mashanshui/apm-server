import type { JankCallTreeNode } from '../types/jank'
import { formatNumber } from './format'

export function formatNanoseconds(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${formatNumber(value / 1_000_000)} ms`
}

export function callTreeLabel(node: Pick<JankCallTreeNode, 'className' | 'methodName'>): string {
  return [node.className, node.methodName].filter(Boolean).join('.') || '未知方法'
}

export interface FlameRect {
  id: string
  label: string
  depth: number
  x: number
  width: number
  estimatedDurationNs: number
  estimatedUnattributedDurationNs: number
}

const FLAME_WIDTH = 1_000
export const MAX_FLAME_NODES = 1_000

export function buildFlameLayout(roots: JankCallTreeNode[]): FlameRect[] {
  const result: FlameRect[] = []
  const positiveRoots = roots.filter((node) => finiteDuration(node.estimatedDurationNs) > 0)
  const rootTotal = positiveRoots.reduce((sum, node) => sum + finiteDuration(node.estimatedDurationNs), 0)
  if (rootTotal <= 0) return result

  let rootX = 0
  positiveRoots.forEach((root, index) => {
    const width = index === positiveRoots.length - 1
      ? FLAME_WIDTH - rootX
      : FLAME_WIDTH * finiteDuration(root.estimatedDurationNs) / rootTotal
    appendNode(root, 0, rootX, width, `${index}`, result)
    rootX += width
  })
  return result
}

function appendNode(
  node: JankCallTreeNode,
  depth: number,
  x: number,
  width: number,
  path: string,
  result: FlameRect[],
): void {
  if (result.length >= MAX_FLAME_NODES || width <= 0) return
  const duration = finiteDuration(node.estimatedDurationNs)
  result.push({
    id: path,
    label: callTreeLabel(node),
    depth,
    x,
    width,
    estimatedDurationNs: duration,
    estimatedUnattributedDurationNs: finiteDuration(node.estimatedUnattributedDurationNs),
  })
  if (duration <= 0) return

  const children = node.children.filter((child) => finiteDuration(child.estimatedDurationNs) > 0)
  const childrenTotal = children.reduce((sum, child) => sum + finiteDuration(child.estimatedDurationNs), 0)
  const scaleBase = Math.max(duration, childrenTotal)
  let childX = x
  children.forEach((child, index) => {
    const childWidth = width * finiteDuration(child.estimatedDurationNs) / scaleBase
    appendNode(child, depth + 1, childX, childWidth, `${path}.${index}`, result)
    childX += childWidth
  })
}

function finiteDuration(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 0
}
