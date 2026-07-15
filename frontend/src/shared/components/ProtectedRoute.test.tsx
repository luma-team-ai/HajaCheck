// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../features/auth/store/authStore';
import type { User } from '../../features/auth/types';
import { ProtectedRoute } from './ProtectedRoute';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
};

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null });
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute>
              <div>대시보드 콘텐츠</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>로그인 페이지</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProtectedRoute', () => {
  it('미인증(user=null) 상태로 보호 라우트 접근 시 /login으로 리다이렉트한다', () => {
    renderAt('/dashboard');

    expect(screen.getByText('로그인 페이지')).not.toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
  });

  it('인증 상태(user 존재)면 children을 그대로 렌더링한다', () => {
    useAuthStore.setState({ user: mockUser });

    renderAt('/dashboard');

    expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    expect(screen.queryByText('로그인 페이지')).toBeNull();
  });
});
