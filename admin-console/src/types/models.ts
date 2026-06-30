// ---- shared backend models (mirrors exchange-common DTOs) ----

export interface Result<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  total: number
  records: T[]
}

/** YYYY-MM-DD */
export type IsoDate = string
/** YYYY-MM-DD HH:mm:ss */
export type DateTimeStr = string

// ---------- task ----------
export interface CreateTaskRequest {
  settleDate: IsoDate
  fileType: string
  version: number
}

export interface TaskVO {
  taskNo: string
  settleDate: IsoDate
  memberId: string
  fileType: string
  version: number
  status: string
  retryCount: number
  maxRetryCount: number
  lastMessageId: string | null
  lastSendTime: DateTimeStr | null
  lastConsumeTime: DateTimeStr | null
  errorMessage: string | null
  startTime: DateTimeStr | null
  endTime: DateTimeStr | null
}

export interface CreateTaskResponse {
  // task-service returns created task info; kept loose for forward-compat
  [k: string]: unknown
}

export interface TaskGenerateResponse {
  [k: string]: unknown
}

export interface TaskResendResponse {
  [k: string]: unknown
}

export interface BatchGenerateRequest {
  settleDate: IsoDate
  fileType: string
}

export interface BatchSendRequest {
  settleDate: IsoDate
  fileType: string
  status?: string
}

export interface BatchGenerateResponse {
  totalCount?: number
  successCount?: number
  failedCount?: number
  [k: string]: unknown
}

export interface BatchSendResponse {
  totalCount?: number
  successCount?: number
  failedCount?: number
  [k: string]: unknown
}

// ---------- file ----------
export interface FileVO {
  fileNo: string
  settleDate: IsoDate
  memberId: string
  fileType: string
  fileName: string
  fileSize: number | null
  fileMd5: string | null
  status: string
  version: number
  downloadCount: number | null
}

export interface FileListQuery {
  settleDate?: string
  memberId?: string
  fileType?: string
}

export interface FileChecksumVO {
  fileNo: string
  fileName: string
  fileSize: number | null
  fileMd5: string
}

export interface FileStatusLogVO {
  fileNo: string
  settleDate: IsoDate
  memberId: string
  fileType: string
  version: number
  beforeStatus: string | null
  afterStatus: string
  operationType: string | null
  batchNo: string | null
  operator: string | null
  reason: string | null
  createdAt: DateTimeStr
}

export interface PublishFileRequest {
  settleDate: IsoDate
  fileType?: string
  version?: number
  operator?: string
}

export interface PublishFileResponse {
  batchNo: string
  settleDate: IsoDate
  publishCount: number
  status: string
}

export interface FilePublishBatch {
  id: number
  batchNo: string
  settleDate: IsoDate
  fileType: string | null
  version: number | null
  status: string
  totalCount: number | null
  successCount: number | null
  failedCount: number | null
  operator: string | null
  errorMessage: string | null
  startTime: DateTimeStr | null
  endTime: DateTimeStr | null
  createdAt: DateTimeStr | null
  updatedAt: DateTimeStr | null
}

export interface RevokeFileRequest {
  operator?: string
  reason: string
}

export interface RevokeFileResponse {
  fileNo: string
  status: string
}

export interface ReissueFileRequest {
  operator?: string
  reason: string
}

export interface ReissueFileResponse {
  reissueNo: string
  oldFileNo: string
  newVersion: number | null
  status: string
}

// ---------- download ----------
export interface DownloadTokenResponse {
  token: string
  expireSeconds: number
}

// ---------- audit ----------
export interface DownloadAuditQuery {
  memberId?: string
  fileNo?: string
  settleDate?: IsoDate
  fileType?: string
  downloadStatus?: string
  clientIp?: string
  startTime?: DateTimeStr
  endTime?: DateTimeStr
  pageNo?: number
  pageSize?: number
}

export interface DownloadAuditDTO {
  auditNo: string
  requestId: string | null
  action: string
  downloadStatus: string
  failReason: string | null
  fileNo: string | null
  fileName: string | null
  fileType: string | null
  settleDate: IsoDate | null
  memberId: string | null
  version: number | null
  clientIp: string | null
  userAgent: string | null
  tokenDigest: string | null
  fileSize: number | null
  downloadBytes: number | null
  startTime: DateTimeStr | null
  endTime: DateTimeStr | null
  costMs: number | null
  createdAt: DateTimeStr
}

export interface FileDownloadStatsDTO {
  fileNo: string
  successCount: number
  failedCount: number
  lastSuccessTime: DateTimeStr | null
  lastClientIp: string | null
}

export interface MemberDownloadStatsDTO {
  memberId: string
  totalCount: number
  successCount: number
  failedCount: number
  lastDownloadTime: DateTimeStr | null
}

export interface DownloadRankDTO {
  name: string
  count: number
}

export interface AbnormalDownloadStatsDTO {
  tokenInvalidCount: number
  tokenExpiredCount: number
  deniedCount: number
  limitedCount: number
  fileNotPublishedCount: number
  fileRevokedCount: number
  fileReissuedCount: number
  topIps: DownloadRankDTO[]
  topMembers: DownloadRankDTO[]
}
