import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as userApi from '@/api/user'
import { clearToken, getToken, setToken } from '@/api/http'
import type { User } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const currentUser = ref<User | null>(null)

  const isAuthenticated = computed(() => Boolean(token.value))

  async function loadCurrentUser() {
    if (!token.value) return null
    try {
      currentUser.value = await userApi.fetchMe()
      return currentUser.value
    } catch {
      currentUser.value = null
      return null
    }
  }

  async function loginWithCode(phone: string, code: string) {
    const rawToken = await userApi.login(phone, code)
    setToken(rawToken)
    token.value = rawToken
    currentUser.value = await userApi.fetchMe()
  }

  async function logout() {
    try {
      if (token.value) await userApi.logout()
    } finally {
      clearToken()
      token.value = null
      currentUser.value = null
    }
  }

  return {
    token,
    currentUser,
    isAuthenticated,
    loadCurrentUser,
    loginWithCode,
    logout,
  }
})
