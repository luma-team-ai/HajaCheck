// @vitest-environment jsdom
// 상담원 콘솔 가드(#1001, HAJA-495) — 인증만으로는 통과되면 안 되고, COUNSELOR가 아니면 대시보드로 되돌린다.
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../features/auth/store/authStore';
import type { User } from '../../features/auth/types';
import { CounselorRoute } from './CounselorRoute';

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
        <Route path="/dashboard" element={<div>대시보드 콘텐츠</div>} />
        {/* 거부 이동지가 role 홈으로 바뀌었으므로(#1513) 플랫폼 관리자 착지점도 필요하다 */}
        <Route path="/platform-admin" element={<div>플랫폼 관리자 콘솔</div>} />
        <Route
          path="/counsel-console/queue"
          element={
            <CounselorRoute>
              <div>상담원 콘솔 콘텐츠</div>
            </CounselorRoute>
          }
        />
        <Route path="/login" element={<div>기업회원 로그인 페이지</div>} />
        <Route path="/counsel-console/login" element={<div>상담원 로그인 페이지</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CounselorRoute', () => {
  it('COUNSELOR면 자식을 렌더한다', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'COUNSELOR' } });

    renderAt('/counsel-console/queue');

    expect(screen.getByText('상담원 콘솔 콘텐츠')).not.toBeNull();
  });

  it('일반 사용자(USER)면 렌더하지 않고 대시보드로 돌려보낸다', () => {
    useAuthStore.setState({ user: mockUser });

    renderAt('/counsel-console/queue');

    expect(screen.queryByText('상담원 콘솔 콘텐츠')).toBeNull();
    expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
  });

  // #1513 — 거부 이동지를 대시보드로 고정하면, 대시보드(AppShell)에 걸린 allowedRoles가 다시
  // PLATFORM_ADMIN을 튕겨내 2홉을 돈다. role 홈(플랫폼 관리자 콘솔)으로 한 번에 정착해야 한다.
  it('플랫폼 관리자(PLATFORM_ADMIN)는 통과하지 못하고 플랫폼 관리자 콘솔로 되돌아간다', () => {
    useAuthStore.setState({ user: { ...mockUser, role: 'PLATFORM_ADMIN' } });

    renderAt('/counsel-console/queue');

    expect(screen.queryByText('상담원 콘솔 콘텐츠')).toBeNull();
    expect(screen.getByText('플랫폼 관리자 콘솔')).not.toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
  });

  it('미인증이면 상담원 전용 로그인(/counsel-console/login)으로 보낸다(기업회원 /login 아님)', () => {
    renderAt('/counsel-console/queue');

    expect(screen.getByText('상담원 로그인 페이지')).not.toBeNull();
    expect(screen.queryByText('기업회원 로그인 페이지')).toBeNull();
    expect(screen.queryByText('상담원 콘솔 콘텐츠')).toBeNull();
  });
});
