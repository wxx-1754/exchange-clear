import { get } from './request'
import type {
  AbnormalDownloadStatsDTO,
  DownloadAuditDTO,
  DownloadAuditQuery,
  FileDownloadStatsDTO,
  MemberDownloadStatsDTO,
  PageResult,
} from '@/types/models'

export const auditApi = {
  list: (query: DownloadAuditQuery) =>
    get<PageResult<DownloadAuditDTO>>('/audits/downloads', query),
  detail: (auditNo: string) => get<DownloadAuditDTO>(`/audits/downloads/${auditNo}`),
  listByFile: (fileNo: string, query: DownloadAuditQuery) =>
    get<PageResult<DownloadAuditDTO>>(`/audits/files/${fileNo}/downloads`, query),
  listByMember: (memberId: string, query: DownloadAuditQuery) =>
    get<PageResult<DownloadAuditDTO>>(`/audits/members/${memberId}/downloads`, query),
}

export interface FileDownloadStatsQuery {
  settleDate?: string
  fileType?: string
  fileNo?: string
}

export interface MemberDownloadStatsQuery {
  memberId?: string
  startTime?: string
  endTime?: string
}

export interface AbnormalStatsQuery {
  startTime?: string
  endTime?: string
}

export const statsApi = {
  fileDownload: (query: FileDownloadStatsQuery) =>
    get<FileDownloadStatsDTO[]>('/audits/stats/file-download', query),
  memberDownload: (query: MemberDownloadStatsQuery) =>
    get<MemberDownloadStatsDTO[]>('/audits/stats/member-download', query),
  abnormal: (query: AbnormalStatsQuery) =>
    get<AbnormalDownloadStatsDTO>('/audits/stats/abnormal', query),
}
