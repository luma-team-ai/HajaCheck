// @vitest-environment jsdom
// 고객측 실시간 채팅 전환(#1000, HAJA-494) — ConversationPanel 메시지 입력창 단위 테스트.
// 소켓 연결(useCounselSocket)은 상위 훅(useCounselHistory)이 캡슐화하므로 여기서는
// onSendMessage 콜백 호출 여부와 ticket.status에 따른 입력창 노출/비노출만 검증한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CounselTicketSummaryResponse } from '../types';
import { ConversationPanel } from './ConversationPanel';

afterEach(() => cleanup());

function buildTicket(overrides: Partial<CounselTicketSummaryResponse> = {}): CounselTicketSummaryResponse {
  return {
    id: 1,
    ticketNumber: 'CS-20260727-001',
    category: '분석 결과 문의',
    title: '분석 결과 등급 문의',
    userId: 100,
    counselorId: 9,
    counselorName: '김상담',
    status: 'IN_PROGRESS',
    queuePosition: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

describe('ConversationPanel', () => {
  it('IN_PROGRESS 티켓이면 메시지 입력창을 보여주고 전송 시 onSendMessage를 호출한다', () => {
    const onSendMessage = vi.fn();
    render(
      <ConversationPanel
        ticket={buildTicket({ status: 'IN_PROGRESS' })}
        messages={[]}
        loading={false}
        error={null}
        onStartNewCounsel={vi.fn()}
        onSendMessage={onSendMessage}
        onTyping={vi.fn()}
        counselorTyping={false}
        onEndCounsel={vi.fn()}
        ending={false}
        endError={null}
      />,
    );

    const input = screen.getByLabelText('메시지 입력');
    fireEvent.change(input, { target: { value: '안녕하세요' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    expect(onSendMessage).toHaveBeenCalledWith('안녕하세요');
    expect((input as HTMLInputElement).value).toBe('');
  });

  it('빈 입력은 전송하지 않는다', () => {
    const onSendMessage = vi.fn();
    render(
      <ConversationPanel
        ticket={buildTicket({ status: 'IN_PROGRESS' })}
        messages={[]}
        loading={false}
        error={null}
        onStartNewCounsel={vi.fn()}
        onSendMessage={onSendMessage}
        onTyping={vi.fn()}
        counselorTyping={false}
        onEndCounsel={vi.fn()}
        ending={false}
        endError={null}
      />,
    );

    expect((screen.getByRole('button', { name: '전송' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('메시지 입력'), { target: { value: '   ' } });
    expect((screen.getByRole('button', { name: '전송' }) as HTMLButtonElement).disabled).toBe(true);
    expect(onSendMessage).not.toHaveBeenCalled();
  });

  it('counselorTyping이 true면 타이핑 말풍선을 보여준다', () => {
    render(
      <ConversationPanel
        ticket={buildTicket({ status: 'IN_PROGRESS' })}
        messages={[]}
        loading={false}
        error={null}
        onStartNewCounsel={vi.fn()}
        onSendMessage={vi.fn()}
        onTyping={vi.fn()}
        counselorTyping
        onEndCounsel={vi.fn()}
        ending={false}
        endError={null}
      />,
    );

    expect(screen.getByText('입력 중입니다...')).toBeTruthy();
  });

  it('입력 중 onTyping을 호출하고, 상담 종료 버튼 클릭 시 onEndCounsel을 호출한다', () => {
    const onTyping = vi.fn();
    const onEndCounsel = vi.fn();
    render(
      <ConversationPanel
        ticket={buildTicket({ status: 'IN_PROGRESS' })}
        messages={[]}
        loading={false}
        error={null}
        onStartNewCounsel={vi.fn()}
        onSendMessage={vi.fn()}
        onTyping={onTyping}
        counselorTyping={false}
        onEndCounsel={onEndCounsel}
        ending={false}
        endError={null}
      />,
    );

    fireEvent.change(screen.getByLabelText('메시지 입력'), { target: { value: '안녕' } });
    expect(onTyping).toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '상담 종료' }));
    expect(onEndCounsel).toHaveBeenCalled();
  });

  it.each(['WAITING', 'RESOLVED', 'OFFLINE_LEFT'] as const)(
    '%s 티켓이면 메시지 입력창을 보여주지 않는다',
    (status) => {
      render(
        <ConversationPanel
          ticket={buildTicket({ status })}
          messages={[]}
          loading={false}
          error={null}
          onStartNewCounsel={vi.fn()}
          onSendMessage={vi.fn()}
          onTyping={vi.fn()}
          counselorTyping={false}
          onEndCounsel={vi.fn()}
          ending={false}
          endError={null}
        />,
      );

      expect(screen.queryByLabelText('메시지 입력')).toBeNull();
    },
  );

  it.each(['RESOLVED', 'OFFLINE_LEFT'] as const)(
    '%s 티켓이면 종료 안내 문구를 보여준다(#1022 후속: 종료 표시 없어 헷갈리던 문제 수정)',
    (status) => {
      render(
        <ConversationPanel
          ticket={buildTicket({ status })}
          messages={[]}
          loading={false}
          error={null}
          onStartNewCounsel={vi.fn()}
          onSendMessage={vi.fn()}
          onTyping={vi.fn()}
          counselorTyping={false}
          onEndCounsel={vi.fn()}
          ending={false}
          endError={null}
        />,
      );

      expect(screen.getByText(/상담이 종료되었습니다/)).not.toBeNull();
    },
  );

  it('WAITING 티켓이면 종료 안내 문구를 보여주지 않는다', () => {
    render(
      <ConversationPanel
        ticket={buildTicket({ status: 'WAITING' })}
        messages={[]}
        loading={false}
        error={null}
        onStartNewCounsel={vi.fn()}
        onSendMessage={vi.fn()}
        onTyping={vi.fn()}
        counselorTyping={false}
        onEndCounsel={vi.fn()}
        ending={false}
        endError={null}
      />,
    );

    expect(screen.queryByText(/상담이 종료되었습니다/)).toBeNull();
  });
});
