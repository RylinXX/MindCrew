import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { userApi, type LoginParams, type UserInfo } from '@/api/user'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => userInfo.value?.role === 'admin')

  async function login(params: LoginParams) {
    const result = await userApi.login(params)
    token.value = result.token
    localStorage.setItem('token', result.token)
    await fetchUserInfo()
    return result
  }

  async function fetchUserInfo() {
    const info = await userApi.getUserInfo()
    userInfo.value = info
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
  }

  return { token, userInfo, isLoggedIn, isAdmin, login, fetchUserInfo, logout }
})
