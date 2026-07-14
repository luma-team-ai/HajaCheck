import { create } from 'zustand';
import type { User } from '../types';

// 전역 클라이언트 상태(로그인 사용자) — React_코드_컨벤션.md §4
// persist 불필요: 세션 쿠키가 진실 소스, 새로고침 시 getMe()로 복원
interface AuthState {
  user: User | null;
  setUser: (user: User) => void;
  clearUser: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  setUser: (user) => set({ user }),
  clearUser: () => set({ user: null }),
}));
