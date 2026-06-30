import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { genRequestId } from '@/utils/format'

/**
 * Unified HTTP client.
 * - baseURL /api, dev-proxied to the gateway.
 * - injects X-Request-Id (matching the gateway convention) for audit tracing.
 * - unwraps the Result<T> envelope; non-zero code surfaces as an error toast.
 */
const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

instance.interceptors.request.use((config) => {
  config.headers.set('X-Request-Id', genRequestId())
  return config
})

instance.interceptors.response.use(
  (response) => {
    const body = response.data
    // file stream / blob responses pass through untouched
    if (body instanceof Blob || response.config.responseType === 'blob') {
      return response
    }
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      ElMessage.error(body.message || `请求失败 (code ${body.code})`)
      return Promise.reject(new Error(body.message || `code ${body.code}`))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const msg =
      error.response?.data?.message ||
      error.response?.data?.error ||
      error.message ||
      '网络异常'
    if (status === 429) {
      ElMessage.error('请求过于频繁，已被限流')
    } else if (status != null) {
      ElMessage.error(`[${status}] ${typeof msg === 'string' ? msg : '请求失败'}`)
    } else {
      ElMessage.error('网络异常，请检查网关与服务状态')
    }
    return Promise.reject(error)
  },
)

export function get<T>(url: string, params?: object, config?: AxiosRequestConfig) {
  return instance.get<unknown, T>(url, { params, ...config })
}

export function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig) {
  return instance.post<unknown, T>(url, body, config)
}

export default instance
