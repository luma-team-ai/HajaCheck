import { api } from '../../../shared/api/axios';
import type { LoginRequest, UserResponse } from '../types';

export const authApi = {
  login: (body: LoginRequest) => api.post<UserResponse>('/auth/login', body),
  logout: () => api.post('/auth/logout'),
  // 로그인 화면 마운트 시 CSRF 쿠키 프리밍 겸 세션 확인 — 401은 미로그인으로 간주(호출부에서 무시)
  getMe: () => api.get<UserResponse>('/users/me'),
};
