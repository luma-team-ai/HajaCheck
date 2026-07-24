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
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute>
              <div>대시보드 콘텐츠</div>
            </ProtectedRoute>
          }
        />
        {/* allowedRoles 자체의 동작은 여기서, 관리자 가드(AdminRoute)는 AdminRoute.test.tsx에서 검증 */}
        <Route
          path="/inspector-only"
          element={
            <ProtectedRoute allowedRoles={['INSPECTOR']}>
              <div>점검자 콘텐츠</div>
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

  // allowedRoles (#378) — 지정 시 인증만으로는 통과되지 않는다. ADMIN 전용 래퍼는 AdminRoute.test.tsx 참조
  it('allowedRoles 불충족이면 렌더하지 않고 대시보드로 돌려보낸다', () => {
    useAuthStore.setState({ user: mockUser }); // role: USER

    renderAt('/inspector-only');

    expect(screen.queryByText('점검자 콘텐츠')).toBeNull();
    // 권한 부족은 /login이 아니라 대시보드로 — 로그인한 사용자를 로그인 화면으로 보내지 않는다
    expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    expect(screen.queryByText('로그인 페이지')).toBeNull();
  });

  it('allowedRoles 충족이면 children을 렌더한다', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'INSPECTOR' } });

    renderAt('/inspector-only');

    expect(screen.getByText('점검자 콘텐츠')).not.toBeNull();
  });

  it('미인증이면 role 검사보다 먼저 /login으로 보낸다', () => {
    renderAt('/inspector-only');

    expect(screen.getByText('로그인 페이지')).not.toBeNull();
    expect(screen.queryByText('점검자 콘텐츠')).toBeNull();
  });
});
