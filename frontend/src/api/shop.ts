import { request } from './http'
import type { Shop, ShopType } from '@/types/api'

export function fetchShopTypes() {
  return request<ShopType[]>({ method: 'get', url: '/shop-type/list' })
}

export function fetchShopsByType(typeId: number, current = 1, x?: number, y?: number) {
  return request<Shop[]>({
    method: 'get',
    url: '/shop/of/type',
    params: { typeId, current, x, y },
  })
}

export function searchShops(name: string, current = 1) {
  return request<Shop[]>({
    method: 'get',
    url: '/shop/of/name',
    params: { name, current },
  })
}

export function fetchShop(id: number) {
  return request<Shop>({ method: 'get', url: `/shop/${id}` })
}
