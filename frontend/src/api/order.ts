import { request } from './http'
import type { Order, PageResult } from '@/types/api'

export function fetchMyOrders(current = 1, size = 10) {
  return request<PageResult<Order>>({
    method: 'get',
    url: '/voucher-order/mine',
    params: { current, size },
  })
}

export function fetchOrder(orderId: number) {
  return request<Order>({ method: 'get', url: `/voucher-order/${orderId}` })
}

export function cancelOrder(orderId: number) {
  return request<number>({ method: 'post', url: `/voucher-order/${orderId}/cancel` })
}
