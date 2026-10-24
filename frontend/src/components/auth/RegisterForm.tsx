import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate, Link } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { Eye, EyeOff, Mail, Lock, User, AlertTriangle, Loader2 } from 'lucide-react'
import { useState } from 'react'
const schema = z.object({
  email: z.string().trim().email('Enter a valid email'),
  password: z.string().min(12, 'Password must be at least 12 characters').max(72),
  displayName: z.string().trim().min(2, 'Display name must be at least 2 characters').max(50),
})
type FormData = z.infer<typeof schema>

export default function RegisterForm() {
  const navigate = useNavigate()
  const { register: registerUser, isLoading, error, clearError } = useAuthStore()
  const { register, handleSubmit, formState: { errors } } = useForm<FormData>({ resolver: zodResolver(schema) })
  const [showPassword, setShowPassword] = useState(false)

  const onSubmit = (data: FormData) => {
    registerUser(data.email, data.password, data.displayName).then(() => navigate('/boards')).catch(() => {})
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="auth-form">
      <div className="auth-header">
        <div className="brand">Kanban</div>
        <h2>Register</h2>
      </div>

      {error && (
        <div className="alert alert--error">
          <AlertTriangle size={18} className="alert-icon" />
          <span>{error}</span>
          <button className="alert-close" onClick={() => clearError()}>×</button>
        </div>
      )}

      <div className="field">
        <label className="label">Display name</label>
        <div className="input-wrapper">
          <User size={18} className="input-icon" />
          <input
            id="displayName"
            {...register('displayName')}
            className={`input ${errors.displayName ? 'input--error' : ''}`}
            placeholder="Oleksii"
            autoComplete="name"
          />
          {errors.displayName && <span className="error-msg">{errors.displayName.message}</span>}
        </div>
      </div>

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
        <label className="label">Password (12–72 characters)</label>
        <div className="input-wrapper">
          <Lock size={18} className="input-icon" />
          <input
            id="password"
            {...register('password')}
            className={`input ${errors.password ? 'input--error' : ''}`}
            type={showPassword ? 'text' : 'password'}
            placeholder="••••••••••••"
            autoComplete="new-password"
          />
          <button type="button" className="toggle-password" onClick={() => setShowPassword(!showPassword)} aria-label="Show password">
            {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
          </button>
          {errors.password && <span className="error-msg">{errors.password.message}</span>}
        </div>
      </div>

      <button className="btn btn--primary btn--full" type="submit" disabled={isLoading}>
        {isLoading ? <Loader2 size={18} className="loader" /> : 'Register'}
      </button>

      <div className="auth-footer">
        Already have an account?{' '}
        <Link to="/login" className="link">Sign in</Link>
      </div>
    </form>
  )
}
