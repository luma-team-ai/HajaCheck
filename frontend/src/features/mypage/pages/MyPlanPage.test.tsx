// @vitest-environment jsdom
// MyPlanPage(#712 Figma 리디자인) — 좌석 테이블/결제 이력 섹션 제거 회귀 방지 + 월 분석 사용량
// 80% 이상일 때만 뜨는 하단 플로팅 경고 배너 조건을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { mypageHandlers } from '../api/mypageApi.handlers';
import { mockMyPlan } from '../mocks/mypage.mock';
import type { MyPlan } from '../types';
import { MyPlanPage } from './MyPlanPage';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MyPlanPage />
    </QueryClientProvider>,
  );
}

describe('MyPlanPage', () => {
  it('좌석 테이블·결제 이력 섹션을 더 이상 렌더링하지 않는다(#712 — 결제 내역은 모달로 이동)', async () => {
    renderPage();

    await screen.findByText('사용량');
    expect(screen.queryByText('점검자 좌석 (2/3)')).toBeNull(); // 기존 SeatsSection 제목(#212)
    expect(screen.queryByText('결제 이력은 준비 중입니다.')).toBeNull(); // 기존 BillingHistoryPlaceholder
  });

  it('월 분석 사용량이 80% 미만이면 하단 경고 배너를 표시하지 않는다(기본 mock 79%)', async () => {
    renderPage();

    await screen.findByText('사용량');
    expect(screen.queryByText(/이번 달 분석 한도에 근접했습니다/)).toBeNull();
  });

  it('월 분석 사용량이 80% 이상이면 하단 경고 배너를 표시한다', async () => {
    const overLimitPlan: MyPlan = {
      ...mockMyPlan,
      usage: { ...mockMyPlan.usage, analyzedImageCount: 850 }, // 850/1000 = 85%
    };
    server.use(
      http.get('/api/me/plan', () => {
        const body: ApiResponse<MyPlan> = { success: true, data: overLimitPlan };
        return HttpResponse.json(body);
      }),
    );

    renderPage();

    expect(await screen.findByText(/이번 달 분석 한도에 근접했습니다/)).toBeTruthy();
  });
});
