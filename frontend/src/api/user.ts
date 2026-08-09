import { request } from './http'
import type { User } from '@/types/api'

export function sendCode(phone: string) {
  return request<void>({ method: 'post', url: '/user/code', params: { phone } })
}

export function login(phone: string, code: string) {
  return request<string>({ method: 'post', url: '/user/login', data: { phone, code } })
}

export function fetchMe() {
  return request<User>({ method: 'get', url: '/user/me' })
}

export function logout() {
  return request<void>({ method: 'post', url: '/user/logout' })
}
