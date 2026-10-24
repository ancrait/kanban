import axios from 'axios'

// Same-origin API; Vite proxies /api/v1 to the backend (localhost:8080) in dev.
// Credentials are included so HttpOnly refresh/CSRF cookies travel with requests.
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api/v1',
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
})
