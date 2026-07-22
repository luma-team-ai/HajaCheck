// @vitest-environment jsdom
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';
import { AI_WEEKLY_BRIEFING_ANCHOR_ID, AI_WEEKLY_BRIEFING_PATH } from '../constants';

// DashboardPage는 KPI/등급분포/최근점검/처리대기/AI브리핑 등 여러 하위 위젯을 렌더링하며 각자 자체
// 데이터훅(TanStack Query)을 쓴다. 이 테스트는 그 위젯들의 내용이 아니라 #478 앵커 스크롤 라우팅
// 로직만 검증하는 것이 목적이라, 실제 네트워크·QueryClientProvider 없이 가볍게 스텁으로 대체한다.
vi.mock('../components/KpiSection', () => ({ KpiSection: () => <div /> }));
vi.mock('../components/GradeDistributionCard', () => ({ GradeDistributionCard: () => <div /> }));
vi.mock('../components/RecentInspectionsTable', () => ({ RecentInspectionsTable: () => <div /> }));
vi.mock('../components/PendingPriorityCard', () => ({ PendingPriorityCard: () => <div /> }));
vi.mock('../components/AiBriefingCard', () => ({ AiBriefingCard: () => <div>AI 주간 브리핑</div> }));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path={AI_WEEKLY_BRIEFING_PATH} element={<DashboardPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

// 사이드바 "AI 주간 브리핑 카드"(#478, #472와 동일한 라우트-메뉴 불일치 유형)는 별도 화면이 아니라
// 이 페이지의 AiBriefingCard 위젯을 가리킨다. 전용 경로로 진입했을 때만 위젯 위치로 스크롤해야 한다.
describe('DashboardPage — AI 주간 브리핑 앵커 스크롤(#478)', () => {
  it('/dashboard/ai-weekly-briefing로 진입하면 AI 브리핑 위젯 위치로 스크롤한다', async () => {
    const scrollIntoViewMock = vi.fn();
    const anchorElement = document.createElement('div');
    anchorElement.id = AI_WEEKLY_BRIEFING_ANCHOR_ID;
    anchorElement.scrollIntoView = scrollIntoViewMock;
    vi.spyOn(document, 'getElementById').mockImplementation((id) =>
      id === AI_WEEKLY_BRIEFING_ANCHOR_ID ? anchorElement : null,
    );

    renderAt(AI_WEEKLY_BRIEFING_PATH);

    await waitFor(() => {
      expect(scrollIntoViewMock).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    });
  });

  it('일반 /dashboard 경로로 진입하면 스크롤하지 않는다', async () => {
    const scrollIntoViewMock = vi.fn();
    const anchorElement = document.createElement('div');
    anchorElement.id = AI_WEEKLY_BRIEFING_ANCHOR_ID;
    anchorElement.scrollIntoView = scrollIntoViewMock;
    vi.spyOn(document, 'getElementById').mockImplementation((id) =>
      id === AI_WEEKLY_BRIEFING_ANCHOR_ID ? anchorElement : null,
    );

    renderAt('/dashboard');

    // requestAnimationFrame이 스케줄되지 않았음을 짧은 유예 뒤에도 재확인해, "아직 호출 안 됨"과
    // "영원히 호출 안 됨"을 구분한다(AppShellRoute.test.tsx의 동일 패턴 참고).
    await new Promise((resolve) => requestAnimationFrame(resolve));
    expect(scrollIntoViewMock).not.toHaveBeenCalled();
  });
});
