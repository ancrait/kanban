import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'

function AuthLayout({ children }: { children: React.ReactNode }) {
  return <div className="auth-layout">{children}</div>
}

function AppBoard() {
  const navigate = useNavigate()
  const { isAuthenticated } = useAuthStore()
  if (!isAuthenticated) {
    navigate('/login', { replace: true })
    return null
  }
  return (
    <div className="boards-placeholder">
      <h1>Boards</h1>
      <p className="muted">Boards page (coming soon).</p>
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<AuthLayout><LoginPage /></AuthLayout>} />
      <Route path="/register" element={<AuthLayout><RegisterPage /></AuthLayout>} />
      <Route path="/boards" element={<AppBoard />} />
      <Route path="/" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}

