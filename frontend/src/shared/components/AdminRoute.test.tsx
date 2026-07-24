// @vitest-environment jsdom
// 관리자 가드(#378) — 인증만으로는 통과되면 안 된다.
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../features/auth/store/authStore';
import type { User } from '../../features/auth/types';
import { AdminRoute } from './AdminRoute';

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

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null });
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/dashboard" element={<div>대시보드 콘텐츠</div>} />
        <Route
          path="/admin/users"
          element={
            <AdminRoute>
              <div>관리자 콘텐츠</div>
            </AdminRoute>
          }
        />
        <Route path="/login" element={<div>로그인 페이지</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AdminRoute', () => {
  it('ADMIN이면 자식을 렌더한다', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'ADMIN' } });

    renderAt('/admin/users');

    expect(screen.getByText('관리자 콘텐츠')).not.toBeNull();
  });

  it('일반 사용자(USER)면 렌더하지 않고 대시보드로 돌려보낸다', () => {
    useAuthStore.setState({ user: mockUser });

    renderAt('/admin/users');

    expect(screen.queryByText('관리자 콘텐츠')).toBeNull();
    // 권한 부족은 /login이 아니라 대시보드로 — 로그인한 사용자를 로그인 화면으로 보내지 않는다
    expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    expect(screen.queryByText('로그인 페이지')).toBeNull();
  });

  it('미인증이면 role 검사보다 먼저 /login으로 보낸다', () => {
    renderAt('/admin/users');

    expect(screen.getByText('로그인 페이지')).not.toBeNull();
    expect(screen.queryByText('관리자 콘텐츠')).toBeNull();
  });
});
