import { api } from './axios'

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: {
    id: string
    email: string
    displayName: string
    createdAt: string
  }
}

export interface RegisterRequest {
  email: string
  password: string
  displayName: string
}

export interface LoginRequest {
  email: string
  password: string
}

/** Fetch a CSRF token before any state-changing auth request. */
export async function getCsrfToken(): Promise<string> {
  const { data } = await api.get<{ token: string }>('/auth/csrf')
  return data.token
}

export async function register(body: RegisterRequest): Promise<AuthResponse> {
  const token = await getCsrfToken()
  const { data } = await api.post<AuthResponse>('/auth/register', body, {
    headers: { 'X-CSRF-TOKEN': token },
  })
  return data
}

export async function login(body: LoginRequest): Promise<AuthResponse> {
  const token = await getCsrfToken()
  const { data } = await api.post<AuthResponse>('/auth/login', body, {
    headers: { 'X-CSRF-TOKEN': token },
  })
  return data
}

export async function logout(): Promise<void> {
  const token = await getCsrfToken()
  await api.post('/auth/logout', undefined, { headers: { 'X-CSRF-TOKEN': token } })
}
