// status dictionaries + display metadata for the three state machines

export type StatusTone = 'idle' | 'busy' | 'ok' | 'warn' | 'danger'

export interface StatusMeta {
  code: string
  desc: string
  tone: StatusTone
}

const TONE_COLOR: Record<StatusTone, { fg: string; bg: string; bd: string }> = {
  idle: { fg: '#8f8a7a', bg: 'rgba(143,138,122,0.10)', bd: 'rgba(143,138,122,0.32)' },
  busy: { fg: '#c89b3a', bg: 'rgba(232,179,57,0.12)', bd: 'rgba(232,179,57,0.36)' },
  ok: { fg: '#7fb069', bg: 'rgba(127,176,105,0.12)', bd: 'rgba(127,176,105,0.36)' },
  warn: { fg: '#d9603f', bg: 'rgba(217,96,63,0.12)', bd: 'rgba(217,96,63,0.36)' },
  danger: { fg: '#d9603f', bg: 'rgba(217,96,63,0.16)', bd: 'rgba(217,96,63,0.48)' },
}

export function toneColor(tone: StatusTone) {
  return TONE_COLOR[tone]
}

// ---------- task status ----------
export const TASK_STATUS: Record<string, StatusMeta> = {
  INIT: { code: 'INIT', desc: '待投递', tone: 'idle' },
  SENT: { code: 'SENT', desc: '已投递', tone: 'busy' },
  GENERATING: { code: 'GENERATING', desc: '生成中', tone: 'busy' },
  GENERATED: { code: 'GENERATED', desc: '已生成', tone: 'ok' },
  FAILED: { code: 'FAILED', desc: '生成失败', tone: 'danger' },
  SEND_FAILED: { code: 'SEND_FAILED', desc: '发送失败', tone: 'warn' },
}

export const TASK_STATUS_OPTIONS: StatusMeta[] = Object.values(TASK_STATUS)

export function taskStatus(code: string | null | undefined): StatusMeta {
  if (!code) return { code: '-', desc: '-', tone: 'idle' }
  return TASK_STATUS[code] ?? { code, desc: code, tone: 'idle' }
}

// ---------- file status ----------
export const FILE_STATUS: Record<string, StatusMeta> = {
  GENERATED: { code: 'GENERATED', desc: '已生成', tone: 'idle' },
  PUBLISHED: { code: 'PUBLISHED', desc: '已发布', tone: 'ok' },
  REVOKED: { code: 'REVOKED', desc: '已撤销', tone: 'danger' },
  REISSUED: { code: 'REISSUED', desc: '已被替代', tone: 'warn' },
  FAILED: { code: 'FAILED', desc: '生成失败', tone: 'danger' },
}

export function fileStatus(code: string | null | undefined): StatusMeta {
  if (!code) return { code: '-', desc: '-', tone: 'idle' }
  return FILE_STATUS[code] ?? { code, desc: code, tone: 'idle' }
}

// ---------- download status ----------
export const DOWNLOAD_STATUS: Record<string, StatusMeta> = {
  SUCCESS: { code: 'SUCCESS', desc: '下载成功', tone: 'ok' },
  FAILED: { code: 'FAILED', desc: '下载失败', tone: 'warn' },
  DENIED: { code: 'DENIED', desc: '权限拒绝', tone: 'danger' },
  TOKEN_INVALID: { code: 'TOKEN_INVALID', desc: 'Token无效', tone: 'danger' },
  TOKEN_EXPIRED: { code: 'TOKEN_EXPIRED', desc: 'Token过期', tone: 'danger' },
  LIMITED: { code: 'LIMITED', desc: '已限流', tone: 'warn' },
  FILE_NOT_FOUND: { code: 'FILE_NOT_FOUND', desc: '文件不存在', tone: 'warn' },
  FILE_NOT_PUBLISHED: { code: 'FILE_NOT_PUBLISHED', desc: '文件未发布', tone: 'warn' },
  FILE_REVOKED: { code: 'FILE_REVOKED', desc: '文件已撤销', tone: 'danger' },
  FILE_REISSUED: { code: 'FILE_REISSUED', desc: '文件已重发', tone: 'warn' },
}

export const DOWNLOAD_STATUS_OPTIONS: StatusMeta[] = Object.values(DOWNLOAD_STATUS)

export function downloadStatus(code: string | null | undefined): StatusMeta {
  if (!code) return { code: '-', desc: '-', tone: 'idle' }
  return DOWNLOAD_STATUS[code] ?? { code, desc: code, tone: 'idle' }
}

// ---------- file type ----------
export const FILE_TYPES = ['TRADE', 'POSITION', 'FUND', 'FEE']
