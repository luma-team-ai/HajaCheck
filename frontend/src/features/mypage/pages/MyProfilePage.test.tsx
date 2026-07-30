// @vitest-environment jsdom
// MyProfilePage(마이페이지 내 정보, HAJA-361 #659) 통합 테스트 — 실제 useMyPlan/useSeats 훅 +
// MSW mypageHandlers를 통해 플랜 요약·사용량·좌석(조회 전용) 렌더를 검증한다.
// MyPlanPage(#212)와 같은 데이터 소스를 공유하므로 mypageApi.handlers.ts를 그대로 재사용한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../auth/store/authStore';
import type { User } from '../../auth/types';
import { mypageHandlers } from '../api/mypageApi.handlers';
import { MyProfilePage } from './MyProfilePage';

const server = setupServer(...mypageHandlers);

// 내 프로필 섹션(HAJA-403, #744)은 authStore.user를 그대로 쓴다 — AuthGate 부트스트랩과 동일하게
// 각 테스트 전에 로그인 사용자를 채워둔다.
const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-07-24T14:30:00',
  companyName: '하자체크',
  status: 'ACTIVE',
};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  useAuthStore.setState({ user: mockUser });
});
afterEach(() => {
  server.resetHandlers();
  useAuthStore.setState({ user: null });
  cleanup();
});
afterAll(() => server.close());

// 비밀번호 변경 섹션(#1316, HAJA-602) 추가로 MyProfilePage가 useLogout(내부에서 useNavigate 사용)에
// 의존하는 PasswordChangeSection을 렌더하게 됐다 — MemoryRouter로 감싼다(회귀: Router 컨텍스트 없이
// useNavigate를 호출하면 렌더 자체가 에러로 실패한다).
function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/mypage/profile']}>
        <MyProfilePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MyProfilePage', () => {
  it('내 프로필 섹션(이름·이메일·가입일·소속 기업)을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('내 프로필')).toBeTruthy();
    expect(screen.getByText('하자체크 담당자')).toBeTruthy();
    expect(screen.getByText('hajacheck@example.com')).toBeTruthy();
    expect(screen.getByText('2026.07.24')).toBeTruthy();
    expect(screen.getByText('하자체크')).toBeTruthy();
  });

  it('소속 기업이 없으면(companyName: null) "개인 회원"으로 표기한다', async () => {
    useAuthStore.setState({ user: { ...mockUser, companyId: null, companyName: null } });
    renderPage();

    expect(await screen.findByText('내 프로필')).toBeTruthy();
    expect(screen.getByText('개인 회원')).toBeTruthy();
  });

  it('로그인 사용자 정보가 없어도(방어적) 다른 섹션(플랜)은 그대로 렌더링한다', async () => {
    useAuthStore.setState({ user: null });
    renderPage();

    expect(
      await screen.findByText('프로필 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeTruthy();
    expect(await screen.findByText('Standard')).toBeTruthy();
  });

  it('플랜 요약(플랜명·PLAN 배지·가격·다음 결제일·사업자 인증 칩)을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('Standard')).toBeTruthy();
    expect(screen.getByText('PLAN')).toBeTruthy(); // 고정 배지(#712 Figma 리디자인, PLAN_STATUS_BADGE_CLASS 대체)
    expect(screen.getByText('₩29,000/월 · 다음 결제일 2026-08-01')).toBeTruthy(); // mockMyPlan(#712 가격 정정)
    expect(screen.getByText('사업자 인증 완료')).toBeTruthy(); // businessVerified: true(mockMyPlan)
  });

  it('사용량 3종(시설물/월 분석/점검자 좌석)을 렌더링한다', async () => {
    renderPage();

    await screen.findByText('사용량');
    // UsageBar는 used+unit과 /limit+unit을 별도 엘리먼트로 렌더링한다(UsageBar.tsx)
    expect(screen.getByText('4개')).toBeTruthy();
    expect(screen.getByText('/10개')).toBeTruthy();
    expect(screen.getByText('786장')).toBeTruthy();
    expect(screen.getByText('/1,000장')).toBeTruthy();
    expect(screen.getByText('2명')).toBeTruthy();
    expect(screen.getByText('/3명')).toBeTruthy();
  });

  it('좌석 테이블에 실 멤버(ACTIVE)를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('홍길동')).toBeTruthy();
    expect(screen.getByText('김철수')).toBeTruthy();
  });

  // 초대는 초대코드로 일원화됐다(관리자 AdminUsersPage → InviteCodeModal, 가입측 InviteCodePage)
  // — 좌석 섹션에 초대 UI/행별 액션이 다시 붙으면 이 테스트가 걸린다(#1045, HAJA-508 회귀 방지).
  it('좌석 섹션에는 작업 열이 없다(회귀 방지)', async () => {
    // MyProfilePage 렌더 결과로 직접 검증한다(SeatsSection에 더 이상 작업 열 자체가 없다).
    renderPage();

    await screen.findByText('홍길동');
    expect(screen.queryByText('작업')).toBeNull();
    expect(screen.queryByText('점검자 초대')).toBeNull();
    expect(screen.queryByText('초대 취소')).toBeNull();
    expect(screen.queryByText('초대됨')).toBeNull();
    expect(screen.queryByRole('button', { name: /관리 메뉴/ })).toBeNull();
  });
});
