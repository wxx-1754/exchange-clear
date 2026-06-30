import { get } from './request'
import type { FileChecksumVO, FileListQuery, FileStatusLogVO, FileVO } from '@/types/models'

export const fileApi = {
  list: (query: FileListQuery = {}) => get<FileVO[]>('/files', query),
  detail: (fileNo: string) => get<FileVO>(`/files/${fileNo}`),
  checksum: (fileNo: string) => get<FileChecksumVO>(`/files/${fileNo}/checksum`),
  statusLogs: (fileNo: string) => get<FileStatusLogVO[]>(`/files/${fileNo}/status-logs`),
}
