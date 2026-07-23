// @vitest-environment jsdom
// MyCounselsPage(마이페이지 내 상담 내역, HAJA-371 #678) 통합 테스트 — 실제 useMyCounsels 훅 +
// MSW mypageHandlers를 통해 헤더·유형/상태 배지·대기 순번·담당자·보기 노출·페이지네이션 렌더를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { mypageHandlers } from '../api/mypageApi.handlers';
import { MyCounselsPage } from './MyCounselsPage';

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
      <MyCounselsPage />
    </QueryClientProvider>,
  );
}

describe('MyCounselsPage', () => {
  it('헤더(제목·총 건수)와 필터 2개를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '내 상담 내역' })).toBeTruthy();
    expect(await screen.findByText('총 18건')).toBeTruthy();
    expect(screen.getByLabelText('상담 카테고리')).toBeTruthy();
    expect(screen.getByLabelText('조회 기간')).toBeTruthy();
  });

  it('유형 배지·주제·시작 일시·마지막 메시지 열을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('간단한 이용 안내 문의')).toBeTruthy();

    const row1 = screen.getByText('간단한 이용 안내 문의').closest('tr') as HTMLElement;
    expect(within(row1).getByText('시나리오 챗봇')).toBeTruthy();
    expect(within(row1).getByText('2026-07-10 14:20')).toBeTruthy();
    expect(within(row1).getByText('도움이 되셨나요? 언제든 다시 질문...')).toBeTruthy();
  });

  it('담당자 없음은 "-"로, 상담원 배정은 이름으로, 텍스트 전용 라벨은 그대로 렌더링한다', async () => {
    renderPage();
    await screen.findByText('간단한 이용 안내 문의');

    const row1 = screen.getByText('간단한 이용 안내 문의').closest('tr') as HTMLElement;
    expect(within(row1).getByText('-')).toBeTruthy();

    const row2 = screen.getByText('분석 결과 오류 확인 요청').closest('tr') as HTMLElement;
    expect(within(row2).getByText('이점검')).toBeTruthy();

    const row3 = screen.getByText('C등급 조치 관련 긴급 문의').closest('tr') as HTMLElement;
    expect(within(row3).getByText('배정 대기중')).toBeTruthy();

    const row4 = screen.getByText('계정 권한 추가 요청').closest('tr') as HTMLElement;
    expect(within(row4).getByText('관리자 그룹')).toBeTruthy();
  });

  it('상태 배지와 대기중 서브텍스트("대기 순번 N")를 렌더링한다', async () => {
    renderPage();
    await screen.findByText('간단한 이용 안내 문의');

    const closedRow = screen.getByText('간단한 이용 안내 문의').closest('tr') as HTMLElement;
    expect(within(closedRow).getByText('종료')).toBeTruthy();

    const waitingRow = screen.getByText('C등급 조치 관련 긴급 문의').closest('tr') as HTMLElement;
    expect(within(waitingRow).getByText('대기중')).toBeTruthy();
    expect(within(waitingRow).getByText('대기 순번 2')).toBeTruthy();

    const answeredRow = screen.getByText('계정 권한 추가 요청').closest('tr') as HTMLElement;
    expect(within(answeredRow).getByText('답변 완료')).toBeTruthy();
  });

  it("'보기' 액션은 canView 행만 보이고 나머지는 자리만 차지한 채 숨겨진다", async () => {
    renderPage();
    await screen.findByText('간단한 이용 안내 문의');

    const visibleRow = screen.getByText('분석 결과 오류 확인 요청').closest('tr') as HTMLElement;
    const visibleButton = within(visibleRow).getByRole('button', { hidden: true });
    expect(visibleButton.textContent).toBe('보기');
    expect(visibleButton.className).toContain('opacity-60');
    expect(visibleButton.getAttribute('aria-hidden')).toBe('false');

    const hiddenRow = screen.getByText('간단한 이용 안내 문의').closest('tr') as HTMLElement;
    const hiddenButton = within(hiddenRow).getByRole('button', { hidden: true });
    expect(hiddenButton.textContent).toBe('보기');
    expect(hiddenButton.className).toContain('opacity-0');
    expect(hiddenButton.getAttribute('aria-hidden')).toBe('true');
  });

  it('페이지네이션을 렌더링한다 — mock totalElements=18, pageSize=8 → "1-8 / 18"', async () => {
    renderPage();
    await screen.findByText('간단한 이용 안내 문의');

    expect(screen.getByText('1-8 / 18')).toBeTruthy();
  });
});
