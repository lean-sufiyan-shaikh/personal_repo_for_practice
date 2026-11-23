export type UserRole = 'SUPER_ADMIN' | 'OWNER' | 'ADMIN' | 'MANAGER' | 'OPERATOR' | 'ACCOUNTANT'

export interface AuthUser {
  userId: number
  fullName: string
  role: UserRole
  assignedPumpId: number | null
  /** Set when someone else chose the password; the user may only change it until they do. */
  mustChangePassword?: boolean
}

export interface LoginRequest {
  phoneNumber: string
  password: string
}

export interface LoginResponse {
  userId: number
  fullName: string
  phoneNumber: string
  role: UserRole
  assignedPumpId: number | null
  mustChangePassword: boolean
}
