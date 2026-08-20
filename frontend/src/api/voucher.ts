import { request } from './http'
import type { OrderSubmission, Voucher } from '@/types/api'

export function fetchVouchers(shopId: number) {
  return request<Voucher[]>({ method: 'get', url: `/voucher/list/${shopId}` })
}

export function seckill(voucherId: number) {
  return request<string>({ method: 'post', url: `/voucher-order/seckill/${voucherId}` })
}

export function fetchSubmission(orderId: string) {
  return request<OrderSubmission>({ method: 'get', url: `/voucher-order/submissions/${orderId}` })
}
