// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../features/auth/store/authStore';
import type { User } from '../../features/auth/types';
import { COMPANY_DASHBOARD_ROLES } from '../constants/roles';
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
  status: 'ACTIVE',
};

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null });
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        {/* router.tsx의 AppShell 부모와 동일하게 기업 대시보드 role만 통과시킨다(#1513) —
            거부 대상을 대시보드로 되돌리면 여기서 무한 리다이렉트가 나므로 role 홈으로 보낸다. */}
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute allowedRoles={COMPANY_DASHBOARD_ROLES}>
              <div>대시보드 콘텐츠</div>
            </ProtectedRoute>
          }
        />
        <Route path="/platform-admin" element={<div>플랫폼 관리자 콘솔</div>} />
        <Route path="/counsel-console/queue" element={<div>상담원 대기열</div>} />
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
        <Route
          path="/invite-code"
          element={
            <ProtectedRoute>
              <div>초대 코드 입력 페이지</div>
            </ProtectedRoute>
          }
        />
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

  // 초대 코드 미입력(status=WAITING, #794/#799) — company_id가 없어 대부분의 보호 리소스가
  // 백엔드에서 403(AUTH_ACCOUNT_WAITING)으로 막히므로, 프론트에서 선제적으로 리다이렉트한다.
  it('status=WAITING이면 대시보드 대신 초대 코드 입력 화면으로 보낸다', () => {
    useAuthStore.setState({ user: { ...mockUser, status: 'WAITING' } });

    renderAt('/dashboard');

    expect(screen.getByText('초대 코드 입력 페이지')).not.toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
  });

  it('status=WAITING이어도 초대 코드 입력 화면 자체는 렌더한다(리다이렉트 루프 방지)', () => {
    useAuthStore.setState({ user: { ...mockUser, status: 'WAITING' } });

    renderAt('/invite-code');

    expect(screen.getByText('초대 코드 입력 페이지')).not.toBeNull();
  });

  // #1513 — 대시보드(AppShell)에 allowedRoles가 걸리면서, 거부 이동지를 DASHBOARD_ROUTE로
  // 고정해 두면 "대시보드 → 거부 → 대시보드 …" 무한 리다이렉트가 된다. role 홈으로 보내야
  // 한 번에 정착한다. 아래 두 테스트가 그 루프 회귀를 잡는다(렌더가 끝나면 루프가 없다는 뜻).
  it('PLATFORM_ADMIN이 기업 대시보드에 직접 접근하면 플랫폼 관리자 콘솔로 튕긴다(무한 리다이렉트 없음)', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'PLATFORM_ADMIN', companyId: null } });

    renderAt('/dashboard');

    expect(screen.getByText('플랫폼 관리자 콘솔')).not.toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
    expect(screen.queryByText('로그인 페이지')).toBeNull();
  });

  it('COUNSELOR가 기업 대시보드에 직접 접근하면 상담원 대기열로 튕긴다(무한 리다이렉트 없음)', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'COUNSELOR', companyId: null } });

    renderAt('/dashboard');

    expect(screen.getByText('상담원 대기열')).not.toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
  });

  it('기업 대시보드 role(ADMIN/INSPECTOR/USER)은 그대로 통과한다', () => {
    for (const role of COMPANY_DASHBOARD_ROLES) {
      useAuthStore.setState({ user: { ...mockUser, role } });

      const { unmount } = renderAt('/dashboard');

      expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
      unmount();
    }
  });
});
