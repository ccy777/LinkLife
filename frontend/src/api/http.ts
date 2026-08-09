import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import type { ApiResult } from '@/types/api'

export const TOKEN_KEY = 'linklife.session.token'

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY)
}

export class ApplicationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ApplicationError'
  }
}

const instance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.authorization = token
  }
  return config
})

function redirectToLogin(): void {
  const current = router.currentRoute.value
  if (current.path === '/login') return
  const redirect = current.fullPath
  void router.push({ path: '/login', query: redirect ? { redirect } : {} })
}

instance.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    if (body && typeof body.success === 'boolean') {
      if (body.success) {
        return body.data as never
      }
      throw new ApplicationError(body.errorMsg || '请求失败')
    }
    return body as never
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status
    const serverMsg = error.response?.data?.errorMsg
    if (status === 401) {
      clearToken()
      redirectToLogin()
      throw new ApplicationError('登录状态已过期，请重新登录')
    }
    if (status != null && status >= 400 && status < 500) {
      throw new ApplicationError(serverMsg || '请求失败')
    }
    ElMessage.error('网络或服务暂时不可用，请稍后重试')
    throw new ApplicationError('网络或服务暂时不可用，请稍后重试')
  },
)

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const res = await instance.request<ApiResult<T>>(config)
  // The response interceptor already unwraps ApiResult<T> to T (or throws
  // ApplicationError on success=false / HTTP / network errors). Accessing
  // `.data` again here would double-unwrap and return undefined.
  return res as unknown as T
}
