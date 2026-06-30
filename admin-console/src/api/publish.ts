import { get, post } from './request'
import type {
  FilePublishBatch,
  PublishFileRequest,
  PublishFileResponse,
  ReissueFileRequest,
  ReissueFileResponse,
  RevokeFileRequest,
  RevokeFileResponse,
} from '@/types/models'

export const publishApi = {
  publish: (body: PublishFileRequest) => post<PublishFileResponse>('/files/publish', body),
  batches: (settleDate?: string) =>
    get<FilePublishBatch[]>('/files/publish-batches', settleDate ? { settleDate } : {}),
  revoke: (fileNo: string, body: RevokeFileRequest) =>
    post<RevokeFileResponse>(`/files/${fileNo}/revoke`, body),
  reissue: (fileNo: string, body: ReissueFileRequest) =>
    post<ReissueFileResponse>(`/files/${fileNo}/reissue`, body),
}
