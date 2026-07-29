// @vitest-environment jsdom
// 플랫폼 관리자 상담 관리(#1168) — AdminCounselDatePanel 날짜 변경 시 재조회 동작 검증.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CounselTicketSummaryResponse } from '../types';
import { AdminCounselDatePanel } from './AdminCounselDatePanel';

afterEach(() => cleanup());

const tickets: CounselTicketSummaryResponse[] = [
  {
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
  },
];

describe('AdminCounselDatePanel', () => {
  it('날짜 input 변경 시 onDateChange를 호출한다', () => {
    const onDateChange = vi.fn();
    render(
      <AdminCounselDatePanel
        date="2026-07-28"
        onDateChange={onDateChange}
        tickets={tickets}
        totalElements={tickets.length}
        page={0}
        onPageChange={vi.fn()}
        loading={false}
        error={null}
        selectedTicketId={null}
        onSelect={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText('날짜 검색'), { target: { value: '2026-07-27' } });

    expect(onDateChange).toHaveBeenCalledWith('2026-07-27');
  });

  it('티켓 클릭 시 onSelect를 호출한다', () => {
    const onSelect = vi.fn();
    render(
      <AdminCounselDatePanel
        date="2026-07-28"
        onDateChange={vi.fn()}
        tickets={tickets}
        totalElements={tickets.length}
        page={0}
        onPageChange={vi.fn()}
        loading={false}
        error={null}
        selectedTicketId={null}
        onSelect={onSelect}
      />,
    );

    fireEvent.click(screen.getByText('박고객'));

    expect(onSelect).toHaveBeenCalledWith(tickets[0]);
  });

  it('WAITING 티켓 카드는 진행중이 아닌 대기 배지를 렌더한다', () => {
    const waitingTicket: CounselTicketSummaryResponse = {
      ...tickets[0],
      id: 6,
      status: 'WAITING',
      customerName: '이고객',
    };
    render(
      <AdminCounselDatePanel
        date="2026-07-28"
        onDateChange={vi.fn()}
        tickets={[waitingTicket]}
        totalElements={1}
        page={0}
        onPageChange={vi.fn()}
        loading={false}
        error={null}
        selectedTicketId={null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('대기')).toBeTruthy();
    expect(screen.queryByText('진행중')).toBeNull();
  });

  it('목록이 비어 있으면 안내 문구를 보여준다', () => {
    render(
      <AdminCounselDatePanel
        date="2026-07-28"
        onDateChange={vi.fn()}
        tickets={[]}
        totalElements={0}
        page={0}
        onPageChange={vi.fn()}
        loading={false}
        error={null}
        selectedTicketId={null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('선택한 날짜에 상담 티켓이 없습니다.')).toBeTruthy();
  });

  it('totalElements가 페이지 크기(20)를 넘으면 페이지네이션을 보여주고, 클릭 시 onPageChange를 호출한다', () => {
    const onPageChange = vi.fn();
    render(
      <AdminCounselDatePanel
        date="2026-07-28"
        onDateChange={vi.fn()}
        tickets={tickets}
        totalElements={45}
        page={0}
        onPageChange={onPageChange}
        loading={false}
        error={null}
        selectedTicketId={null}
        onSelect={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByLabelText('2페이지'));

    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('totalElements가 페이지 크기 이하면 페이지네이션을 보여주지 않는다', () => {
    render(
      <AdminCounselDatePanel
        date="2026-07-28"
        onDateChange={vi.fn()}
        tickets={tickets}
        totalElements={tickets.length}
        page={0}
        onPageChange={vi.fn()}
        loading={false}
        error={null}
        selectedTicketId={null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByLabelText('페이지 네비게이션')).toBeNull();
  });
});
