// @vitest-environment jsdom
// 플랫폼 관리자 상담 관리(#1168) — ReadOnlyConversationPanel 회귀 방지 테스트. 이 패널은 읽기 전용
// 이라 입력창/전송 버튼/상담 종료 버튼이 렌더되면 안 된다(ConversationPanel과의 핵심 차이).
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';
import { ReadOnlyConversationPanel } from './ReadOnlyConversationPanel';

afterEach(() => cleanup());

function buildTicket(overrides: Partial<CounselTicketSummaryResponse> = {}): CounselTicketSummaryResponse {
  return {
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
    ...overrides,
  };
}

const messages: ChatMessageResponse[] = [
  {
    id: 1,
    sessionId: 700,
    sender: 'COUNSELOR',
    content: '안녕하세요, 안내드리겠습니다.',
    attachmentUrl: null,
    counselorName: '김상담',
    createdAt: '2026-07-28T09:01:00',
  },
];

describe('ReadOnlyConversationPanel', () => {
  it('입력창·전송 버튼·상담 종료 버튼을 렌더하지 않는다', () => {
    render(<ReadOnlyConversationPanel ticket={buildTicket()} messages={messages} loading={false} error={null} />);

    expect(screen.queryByLabelText('메시지 입력')).toBeNull();
    expect(screen.queryByRole('button', { name: '전송' })).toBeNull();
    expect(screen.queryByRole('button', { name: /상담 종료/ })).toBeNull();
  });

  it('메시지를 그대로 보여준다', () => {
    render(<ReadOnlyConversationPanel ticket={buildTicket()} messages={messages} loading={false} error={null} />);

    expect(screen.getByText('안녕하세요, 안내드리겠습니다.')).toBeTruthy();
  });

  it('상태별 과거형 라벨을 보여준다(RESOLVED → 종료됨)', () => {
    render(<ReadOnlyConversationPanel ticket={buildTicket({ status: 'RESOLVED' })} messages={[]} loading={false} error={null} />);

    expect(screen.getByText('종료됨')).toBeTruthy();
  });

  it('티켓이 없으면 선택 안내를 보여준다', () => {
    render(<ReadOnlyConversationPanel ticket={null} messages={[]} loading={false} error={null} />);

    expect(screen.getByText('좌측에서 상담 세션을 선택하세요.')).toBeTruthy();
  });
});
