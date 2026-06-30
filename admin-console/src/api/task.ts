import { get, post } from './request'
import type {
  BatchGenerateRequest,
  BatchGenerateResponse,
  BatchSendRequest,
  BatchSendResponse,
  CreateTaskRequest,
  CreateTaskResponse,
  TaskGenerateResponse,
  TaskResendResponse,
  TaskVO,
} from '@/types/models'

export interface TaskListQuery {
  settleDate?: string
  status?: string
}

export const taskApi = {
  list: (query: TaskListQuery = {}) => get<TaskVO[]>('/tasks', query),
  create: (body: CreateTaskRequest) => post<CreateTaskResponse>('/tasks/create', body),
  generate: (taskNo: string) => post<TaskGenerateResponse>(`/tasks/${taskNo}/generate`),
  generateSync: (taskNo: string) => post<TaskGenerateResponse>(`/tasks/${taskNo}/generate-sync`),
  generateBatch: (body: BatchGenerateRequest) =>
    post<BatchGenerateResponse>('/tasks/generate-batch', body),
  sendBatch: (body: BatchSendRequest) => post<BatchSendResponse>('/tasks/send-batch', body),
  resend: (taskNo: string) => post<TaskResendResponse>(`/tasks/${taskNo}/resend`),
}
