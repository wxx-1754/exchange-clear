// formatting helpers — figures render in mono via .font-mono on the element

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = bytes / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 100 ? 0 : v >= 10 ? 1 : 2)} ${units[i]}`
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ')
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  return value.length > 10 ? value.slice(0, 10) : value
}

export function truncate(text: string | null | undefined, max = 40): string {
  if (!text) return '—'
  return text.length > max ? text.slice(0, max) + '…' : text
}

/** local today as YYYY-MM-DD */
export function todayIso(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

/** generate a request id matching gateway's REQ<uuid> convention */
export function genRequestId(): string {
  return 'REQ' + crypto.randomUUID().replace(/-/g, '')
}
