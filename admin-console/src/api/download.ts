import { post } from './request'
import type { DownloadTokenResponse } from '@/types/models'

export const downloadApi = {
  /** mints a one-time download token; memberId travels as X-Member-Id header */
  createToken: (fileNo: string, memberId: string) =>
    post<DownloadTokenResponse>(
      '/download/token',
      { fileNo },
      { headers: { 'X-Member-Id': memberId } },
    ),
}

/** builds the streaming download URL; memberId is optional context only */
export function downloadUrl(fileNo: string, token: string): string {
  return `/api/download/files/${encodeURIComponent(fileNo)}?token=${encodeURIComponent(token)}`
}
