export interface ApiResult<T> {
  success: boolean
  errorMsg: string | null
  data: T
  total: number | null
}

export interface User {
  id: number
  nickName: string
  icon: string
}

export interface ShopType {
  id: number
  name: string
  icon: string
  sort: number
}

export interface Shop {
  id: number
  name: string
  typeId: number
  images: string
  area: string
  address: string
  x: number
  y: number
  avgPrice: number
  sold: number
  comments: number
  score: number
  openHours: string
  createTime: string
  updateTime: string
  distance?: number
}

export interface Voucher {
  id: number
  shopId: number
  title: string
  subTitle: string | null
  rules: string | null
  payValue: number
  actualValue: number
  type: number
  status: number
  stock?: number | null
  beginTime?: string | null
  endTime?: string | null
}

export interface Blog {
  id: number
  shopId: number
  userId: number
  title: string
  images: string
  content: string
  liked: number
  comments: number
  createTime: string
  updateTime: string
  name?: string | null
  icon?: string | null
}

export interface BlogLikeUser {
  id: number
  nickName: string
  icon: string
}

export interface Order {
  /** 18-digit snowflake id; backend serializes it as a JSON string. */
  id: string
  userId: number
  voucherId: number
  payType: number
  status: number
  createTime: string
  payTime: string | null
  useTime: string | null
  refundTime: string | null
  updateTime: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export type SubmissionState = 'ACCEPTED' | 'PROCESSING' | 'PERSISTED' | 'FAILED' | 'UNKNOWN'

export interface OrderSubmission {
  /** 18-digit snowflake id; backend serializes it as a JSON string. */
  orderId: string
  state: SubmissionState
  message?: string
  updatedAt?: number
}
