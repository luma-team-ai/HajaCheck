// @vitest-environment jsdom
// 플랫폼 관리자 가드(#535) — 인증만으로는 통과되면 안 되고, 리다이렉트 대상도 기업회원 가드와 다르다.
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../features/auth/store/authStore';
import type { User } from '../../features/auth/types';
import { PlatformAdminRoute } from './PlatformAdminRoute';

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
          path="/platform-admin/users"
          element={
            <PlatformAdminRoute>
              <div>플랫폼 관리자 콘텐츠</div>
            </PlatformAdminRoute>
          }
        />
        <Route path="/platform-admin/login" element={<div>플랫폼 관리자 로그인 페이지</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('PlatformAdminRoute', () => {
  it('PLATFORM_ADMIN이면 자식을 렌더한다', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'PLATFORM_ADMIN' } });

    renderAt('/platform-admin/users');

    expect(screen.getByText('플랫폼 관리자 콘텐츠')).not.toBeNull();
  });

  it('일반 사용자(USER)면 렌더하지 않고 대시보드로 돌려보낸다', () => {
    useAuthStore.setState({ user: mockUser });

    renderAt('/platform-admin/users');

    expect(screen.queryByText('플랫폼 관리자 콘텐츠')).toBeNull();
    expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    expect(screen.queryByText('플랫폼 관리자 로그인 페이지')).toBeNull();
  });

  it('기업 관리자(ADMIN)도 플랫폼 관리자 콘솔은 통과하지 못한다', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'ADMIN' } });

    renderAt('/platform-admin/users');

    expect(screen.queryByText('플랫폼 관리자 콘텐츠')).toBeNull();
    expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
  });

  it('미인증이면 /platform-admin/login으로 보낸다(기업회원 /login이 아님)', () => {
    renderAt('/platform-admin/users');

    expect(screen.getByText('플랫폼 관리자 로그인 페이지')).not.toBeNull();
    expect(screen.queryByText('플랫폼 관리자 콘텐츠')).toBeNull();
  });
});
