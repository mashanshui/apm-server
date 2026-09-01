import type { CrashStatus } from '../types/crash'

const numberFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const percentFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'percent',
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : numberFormatter.format(value)
}

export function formatPerThousand(value: number | null | undefined): string {
  return value === null || value === undefined ? '不可计算' : `${numberFormatter.format(value)}‰`
}

export function formatPercent(value: number | null | undefined): string {
  return value === null || value === undefined ? '不可计算' : percentFormatter.format(value)
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function statusLabel(status: CrashStatus | null | undefined): string {
  switch (status) {
    case 'ok':
      return '数据正常'
    case 'no_data':
      return '无数据'
    case 'denominator_insufficient':
      return '分母不足'
    default:
      return status || '未知状态'
  }
}

export function statusTone(status: CrashStatus | null | undefined): 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'ok':
      return 'success'
    case 'no_data':
    case 'denominator_insufficient':
      return 'warning'
    default:
      return 'danger'
  }
}

export function metricValue(
  value: number | null | undefined,
  status: CrashStatus | null | undefined,
  kind: 'number' | 'perThousand' | 'percent',
): string {
  if ((kind === 'perThousand' || kind === 'percent') && (value === null || value === undefined)) {
    return status === 'no_data' ? '无数据' : '不可计算'
  }
  if (kind === 'perThousand') {
    return formatPerThousand(value)
  }
  if (kind === 'percent') {
    return formatPercent(value)
  }
  return formatNumber(value)
}

export function toDateTimeLocal(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

export function fromDateTimeLocal(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toISOString()
}
