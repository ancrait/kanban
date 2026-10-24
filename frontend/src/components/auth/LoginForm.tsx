import React from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate, Link } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { Eye, EyeOff, Mail, Lock, AlertTriangle, Loader2 } from 'lucide-react'

const schema = z.object({
  email: z.string().trim().email('Enter a valid email'),
  password: z.string().min(1, 'Enter your password'),
})
type FormData = z.infer<typeof schema>

export default function LoginForm() {
  const navigate = useNavigate()
  const { login, isLoading, error, clearError } = useAuthStore()
  const { register, handleSubmit, formState: { errors } } = useForm<FormData>({ resolver: zodResolver(schema) })
  const [showPassword, setShowPassword] = React.useState(false)

  const onSubmit = (data: FormData) => {
    login(data.email, data.password).then(() => navigate('/boards')).catch(() => {})
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="auth-form">
      <div className="auth-header">
        <div className="brand">Kanban</div>
        <h2>Sign in</h2>
      </div>

      {error && (
        <div className="alert alert--error">
          <AlertTriangle size={18} className="alert-icon" />
          <span>{error}</span>
          <button className="alert-close" onClick={clearError}>&times;</button>
        </div>
      )}

      <div className="field">
        <label className="label">Email address</label>
        <div className="input-wrapper">
          <Mail size={18} className="input-icon" />
          <input
            id="email"
            {...register('email')}
            className={`input ${errors.email ? 'input--error' : ''}`}
            type="email"
            placeholder="developer@example.com"
            autoComplete="email"
          />
          {errors.email && <span className="error-msg">{errors.email.message}</span>}
        </div>
      </div>

      <div className="field">
        <label className="label">Password</label>
        <div className="input-wrapper">
          <Lock size={18} className="input-icon" />
          <input
            id="password"
            {...register('password')}
            className={`input ${errors.password ? 'input--error' : ''}`}
            type={showPassword ? 'text' : 'password'}
            placeholder="••••••••••••"
            autoComplete="current-password"
          />
          <button type="button" className="toggle-password" onClick={() => setShowPassword(!showPassword)} aria-label="Show password">
            {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
          </button>
          {errors.password && <span className="error-msg">{errors.password.message}</span>}
        </div>
      </div>

      <button className="btn btn--primary btn--full" type="submit" disabled={isLoading}>
        {isLoading ? <Loader2 size={18} className="loader" /> : 'Sign in'}
      </button>

      <div className="auth-footer">
        Don't have an account?{' '}
        <Link to="/register" className="link">Register</Link>
      </div>
    </form>
  )
}

