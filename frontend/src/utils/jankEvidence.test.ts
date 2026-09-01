import { describe, expect, it } from 'vitest'
import type { JankCallTreeNode } from '../types/jank'
import { buildFlameLayout, MAX_FLAME_NODES } from './jankEvidence'

function node(methodName: string, duration: number, children: JankCallTreeNode[] = []): JankCallTreeNode {
  return { className: 'com.example.Work', methodName, estimatedDurationNs: duration, estimatedUnattributedDurationNs: 0, children }
}

describe('buildFlameLayout', () => {
  it('按采样估算时长布局父子节点并保留深度', () => {
    const layout = buildFlameLayout([node('root', 100, [node('left', 60), node('right', 20)])])
    expect(layout.map((item) => [item.label, item.depth])).toEqual([
      ['com.example.Work.root', 0], ['com.example.Work.left', 1], ['com.example.Work.right', 1],
    ])
    expect(layout[0].width).toBe(1000)
    expect(layout[1].width).toBe(600)
    expect(layout[2].x).toBe(600)
    expect(layout[2].width).toBe(200)
  })

  it('忽略无效时长并限制超大构造响应的绘制节点数', () => {
    const roots = Array.from({ length: MAX_FLAME_NODES + 200 }, (_, index) => node(`method-${index}`, 1))
    expect(buildFlameLayout([node('invalid', Number.NaN)])).toEqual([])
    expect(buildFlameLayout(roots)).toHaveLength(MAX_FLAME_NODES)
  })
})
