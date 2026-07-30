// @vitest-environment jsdom
// 플랫폼 관리자 상담 관리(#1168) — AdminCounselInfoPanel 통합 테스트(실제 counselApi + MSW).
// 정보 탭의 고객 프로필/담당 상담원 노출과, 이력 탭 드릴다운(목록 → 대화 → 목록으로) 플로우를 검증한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { counselHandlers } from '../api/counselApi.handlers';
import type { CounselTicketSummaryResponse } from '../types';
import { AdminCounselInfoPanel } from './AdminCounselInfoPanel';

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

const ticket: CounselTicketSummaryResponse = {
  id: 5,
  ticketNumber: 'CS-20260728-003',
  category: '점검 결과서 관련',
  title: 'AI 분석 결과 등급 문의',
  userId: 300,
  counselorId: 9,
  counselorName: '김상담',
  status: 'RESOLVED',
  queuePosition: null,
  createdAt: '2026-07-28T09:00:00',
  customerName: '박고객',
  customerEmail: 'customer300@example.com',
  customerPlan: '프로',
  customerJoinedAt: '2026-01-10T00:00:00',
};

describe('AdminCounselInfoPanel', () => {
  it('정보 탭에서 고객 프로필과 담당 상담원을 보여준다', () => {
    render(
      <AdminCounselInfoPanel ticket={ticket} selectedHistoryTicketId={null} onSelectHistory={() => {}} />,
    );

    expect(screen.getByText('박고객')).toBeTruthy();
    expect(screen.getByText('customer300@example.com')).toBeTruthy();
    expect(screen.getByText('프로')).toBeTruthy();
    expect(screen.getByText('김상담')).toBeTruthy();
  });

  it('이력 탭 클릭 시 과거 상담 목록을 불러오고, 항목 클릭 시 onSelectHistory로 위임한다', async () => {
    const handleSelectHistory = vi.fn();
    render(
      <AdminCounselInfoPanel
        ticket={ticket}
        selectedHistoryTicketId={null}
        onSelectHistory={handleSelectHistory}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '이력' }));

    expect(await screen.findByText('지난 요금제 변경 문의')).toBeTruthy();

    fireEvent.click(screen.getByText('지난 요금제 변경 문의'));

    expect(handleSelectHistory).toHaveBeenCalledWith(
      expect.objectContaining({ title: '지난 요금제 변경 문의' }),
    );
  });

  it('티켓이 없으면 안내 문구를 보여준다', () => {
    render(<AdminCounselInfoPanel ticket={null} selectedHistoryTicketId={null} onSelectHistory={() => {}} />);

    expect(screen.getByText('티켓을 선택하면 정보가 표시됩니다.')).toBeTruthy();
  });
});
