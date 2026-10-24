import { create } from 'zustand'
import { login, register, logout, type AuthResponse } from '../api/auth'

interface AuthState {
  accessToken: string | null
  user: AuthResponse['user'] | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
  clearError: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  isAuthenticated: false,
  isLoading: false,
  error: null,

  login: async (email, password) => {
    set({ isLoading: true, error: null })
    try {
      const res = await login({ email, password })
      set({ accessToken: res.accessToken, user: res.user, isAuthenticated: true, isLoading: false, error: null })
    } catch (err: any) {
      set({ isLoading: false, error: err?.response?.data?.detail ?? 'Login failed' })
      throw err
    }
  },

  register: async (email, password, displayName) => {
    set({ isLoading: true, error: null })
    try {
      const res = await register({ email, password, displayName })
      set({ accessToken: res.accessToken, user: res.user, isAuthenticated: true, isLoading: false, error: null })
    } catch (err: any) {
      set({ isLoading: false, error: err?.response?.data?.detail ?? 'Registration failed' })
      throw err
    }
  },

  logout: async () => {
    set({ isLoading: true })
    try {
      await logout()
    } catch {
      // ignore backend errors; clear local state regardless
    }
    set({ accessToken: null, user: null, isAuthenticated: false, isLoading: false, error: null })
  },

  clearError: () => set({ error: null }),
}))
