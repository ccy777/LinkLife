export function formatPrice(cents: number | null | undefined): string {
  if (cents == null) return '-'
  const yuan = cents / 100
  return `¥${yuan.toFixed(2)}`
}

/** Shop.avgPrice is an integer number of yuan (e.g. 80 -> ¥80). */
export function formatYuanAmount(yuan: number | null | undefined): string {
  if (yuan == null) return '-'
  return `¥${yuan}`
}

export function formatDistance(meters: number | null | undefined): string {
  if (meters == null) return ''
  if (meters < 1000) return `${Math.round(meters)} m`
  return `${(meters / 1000).toFixed(1)} km`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatScore(score: number | null | undefined): string {
  if (score == null) return '-'
  return (score / 10).toFixed(1)
}

export function orderStatusText(status: number): string {
  const map: Record<number, string> = {
    1: '待支付',
    2: '已支付',
    3: '已使用',
    4: '已取消',
    5: '退款中',
    6: '已退款',
  }
  return map[status] ?? `状态 ${status}`
}
