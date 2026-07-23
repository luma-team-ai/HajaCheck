// @vitest-environment jsdom
// MyProfilePage(마이페이지 내 정보, HAJA-361 #659) 통합 테스트 — 실제 useMyPlan/useSeats 훅 +
// MSW mypageHandlers를 통해 플랜 요약·사용량·좌석(작업 열 포함) 렌더를 검증한다.
// MyPlanPage(#212)와 같은 데이터 소스를 공유하므로 mypageApi.handlers.ts를 그대로 재사용한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { mypageHandlers } from '../api/mypageApi.handlers';
import { MyProfilePage } from './MyProfilePage';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MyProfilePage />
    </QueryClientProvider>,
  );
}

describe('MyProfilePage', () => {
  it('플랜 요약(플랜명·상태 배지·가격)을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('Standard')).toBeTruthy();
    expect(screen.getByText('이용중')).toBeTruthy(); // PLAN_STATUS_LABEL.ACTIVE(planFormat.ts)
    expect(screen.getByText('₩99,000/월 · 다음 결제일 정보는 준비 중입니다.')).toBeTruthy();
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

  it('좌석 테이블에 실 멤버(ACTIVE)와 데모 초대 멤버(초대됨)를 함께 렌더링하고 작업 열을 노출한다', async () => {
    renderPage();

    expect(await screen.findByText('홍길동')).toBeTruthy();
    expect(screen.getByText('김철수')).toBeTruthy();

    // 데모 전용 '초대됨' 멤버(mockInvitedSeatMember) — 실 useSeats 응답(mockSeats)에는 없다
    expect(screen.getByText('박초대')).toBeTruthy();
    expect(screen.getByText('초대됨')).toBeTruthy();

    // 작업 열 헤더
    expect(screen.getByText('작업')).toBeTruthy();

    // '초대 취소' 링크는 초대됨 행에만 존재
    const invitedRow = screen.getByText('박초대').closest('tr') as HTMLElement;
    expect(within(invitedRow).getByText('초대 취소')).toBeTruthy();

    const activeRow = screen.getByText('홍길동').closest('tr') as HTMLElement;
    expect(within(activeRow).queryByText('초대 취소')).toBeNull();

    // ⋯ 관리 메뉴는 모든 행에 존재하고, 클릭해도 동작하지 않도록 disabled 상태다(BE 미구현)
    const moreButton = within(invitedRow).getByRole('button', { name: '박초대 관리 메뉴' });
    expect(moreButton).toHaveProperty('disabled', true);
  });

  it('MyPlanPage(/mypage/plan)에서 쓰는 SeatsSection은 작업 열이 없다(회귀 방지)', async () => {
    // MyProfilePage와 별개로, SeatsSection의 showActions 기본값이 false임을 직접 검증한다.
    const { SeatsSection } = await import('../components/SeatsSection');
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <SeatsSection />
      </QueryClientProvider>,
    );

    await screen.findByText('홍길동');
    expect(screen.queryByText('작업')).toBeNull();
    expect(screen.queryByText('초대됨')).toBeNull();
  });
});
