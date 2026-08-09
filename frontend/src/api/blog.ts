import { request } from './http'
import type { Blog, BlogLikeUser } from '@/types/api'

export function fetchHotBlogs(current = 1) {
  return request<Blog[]>({ method: 'get', url: '/blog/hot', params: { current } })
}

export function fetchBlog(id: number) {
  return request<Blog>({ method: 'get', url: `/blog/${id}` })
}

export function fetchBlogLikes(id: number) {
  return request<BlogLikeUser[]>({ method: 'get', url: `/blog/likes/${id}` })
}

export function likeBlog(id: number) {
  return request<void>({ method: 'put', url: `/blog/like/${id}` })
}

export function fetchFollowState(userId: number) {
  return request<boolean>({ method: 'get', url: `/follow/or/not/${userId}` })
}

export function followUser(userId: number, isFollow: boolean) {
  return request<void>({ method: 'put', url: `/follow/${userId}/${isFollow}` })
}
