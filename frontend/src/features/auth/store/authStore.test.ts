import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from './authStore';
import type { User } from '../types';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '하자체크',
};

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
  });

  it('초기 user는 null이다', () => {
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('setUser로 로그인 사용자를 저장한다', () => {
    useAuthStore.getState().setUser(mockUser);
    expect(useAuthStore.getState().user).toEqual(mockUser);
  });

  it('clearUser로 사용자 정보를 초기화한다', () => {
    useAuthStore.getState().setUser(mockUser);
    useAuthStore.getState().clearUser();
    expect(useAuthStore.getState().user).toBeNull();
  });
});
