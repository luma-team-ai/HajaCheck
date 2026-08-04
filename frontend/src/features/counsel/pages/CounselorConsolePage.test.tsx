// @vitest-environment jsdom
// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) 통합 테스트 — 실제 useCounselorQueue 훅 + MSW
// counselHandlers. useCounselSocket은 별도 단위 테스트(useCounselSocket.test.ts)에서 이미 검증돼
// 여기서는 목으로 대체해 STOMP 프레임 시뮬레이션 없이 페이지 동작(목록→배정→채팅→종료)에 집중한다.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { counselHandlers, mockInProgressQueueTicket, mockQueueTickets } from '../api/counselApi.handlers';
import type { UseCounselSocketHandlers } from '../hooks/useCounselSocket';
import { CounselorConsolePage } from './CounselorConsolePage';

const sendMessage = vi.fn();
const sendTyping = vi.fn();
let capturedHandlers: UseCounselSocketHandlers | null = null;

vi.mock('../hooks/useCounselSocket', () => ({
  useCounselSocket: (ticketId: number | null, handlers: UseCounselSocketHandlers) => {
    capturedHandlers = handlers;
    return { connected: ticketId !== null, sendMessage, sendTyping };
  },
}));

// 대기열 실시간 갱신(#1001 후속) — 별도 단위 테스트가 없으므로 여기서는 실제 STOMP 연결을 막고
// no-op으로 대체한다(이 페이지 테스트는 REST 목록 로직에 집중).
vi.mock('../hooks/useCounselQueueSocket', () => ({
  useCounselQueueSocket: () => ({ connected: false }),
}));

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  sendMessage.mockClear();
  sendTyping.mockClear();
  capturedHandlers = null;
});
afterAll(() => server.close());

function renderPage(initialPath = '/counsel-console/queue') {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/counsel-console/queue" element={<CounselorConsolePage />} />
        <Route path="/counsel-console/tickets/:id" element={<CounselorConsolePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CounselorConsolePage', () => {
  it('대기열 목록을 불러와 보여준다', async () => {
    renderPage();

    expect(await screen.findByText(mockQueueTickets[0].title)).not.toBeNull();
    // 종료되지 않은 담당 상담(IN_PROGRESS)도 대기열(WAITING)과 함께 목록에 보인다(#1001 후속 버그 수정).
    expect(screen.getByText(mockInProgressQueueTicket.title)).not.toBeNull();
    expect(screen.getByText('활성 채팅 (2)')).not.toBeNull();
    // 상담 중(IN_PROGRESS)과 배정 가능(WAITING)이 구분된 섹션으로 보인다(사용자 피드백).
    expect(screen.getByText('상담 중 (1)')).not.toBeNull();
    expect(screen.getByText('배정 가능 (1)')).not.toBeNull();
    expect(screen.getByText('왼쪽 목록에서 상담을 선택하세요.')).not.toBeNull();
  });

  it('목록에서 티켓 선택 → 배정받기 → 채팅창에서 메시지 송수신 → 상담 종료까지 이어진다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));

    // 아직 배정 전(WAITING)이라 대화창 대신 배정 CTA만 보인다.
    const claimButton = await screen.findByRole('button', { name: '상담 배정받기' });
    fireEvent.click(claimButton);

    // 배정 성공 후 대화가 로드된다.
    expect(await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.')).not.toBeNull();

    const input = screen.getByPlaceholderText('메시지를 입력하세요') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '안내드리겠습니다' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    expect(sendMessage).toHaveBeenCalledWith('안내드리겠습니다');
    expect(input.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: '상담 종료' }));

    await waitFor(() =>
      expect(screen.getByText('왼쪽 목록에서 상담을 선택하세요.')).not.toBeNull(),
    );
  });

  it('정보 패널 "이력" 탭에서 고객의 과거 상담 이력을 보여준다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    fireEvent.click(screen.getByRole('button', { name: '이력' }));

    expect(await screen.findByText('지난 요금제 변경 문의')).not.toBeNull();
  });

  it('이력 항목 클릭 시 대화 내용을 보여주고, 목록으로 버튼으로 되돌아간다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    fireEvent.click(screen.getByRole('button', { name: '이력' }));
    fireEvent.click(await screen.findByText('지난 요금제 변경 문의'));

    expect(await screen.findByText('어떤 요금제로 변경을 원하시나요?')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '목록으로' }));

    expect(await screen.findByText('지난 요금제 변경 문의')).not.toBeNull();
  });

  it('클레임 409 경합 시 안내 메시지를 보여주고 큐를 새로고침한다', async () => {
    server.use(
      http.post('/api/counsel/tickets/:id/assign', () =>
        HttpResponse.json(
          {
            success: false,
            error: { code: 'COUNSEL_SESSION_ASSIGNMENT_CONFLICT', message: '이미 상담 세션이 배정된 티켓입니다.' },
          },
          { status: 409 },
        ),
      ),
    );

    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));

    expect(await screen.findByRole('alert')).not.toBeNull();
    expect(screen.getByText('이미 상담 세션이 배정된 티켓입니다.')).not.toBeNull();
  });

  it('소켓 onEnded 콜백(고객 등 다른 경로로 상담 종료)을 받으면 화면 이동 없이 종료 UI로 전환한다(#1506)', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    capturedHandlers?.onEnded?.({
      id: 3,
      ticketNumber: 'CS-20260727-001',
      category: '점검 결과서 관련',
      title: 'AI 분석 결과 등급 문의',
      userId: 200,
      counselorId: 9,
      status: 'RESOLVED',
      queuePosition: null,
      createdAt: '2026-07-27T09:00:00',
    });

    // 화면 이동 없이 같은 채팅창에서 종료 상태로 전환된다 — 대기열 화면(선택 안내 문구)으로 돌아가지 않는다.
    // 문구는 종료 주체를 단정하지 않는 중립형이다(#1590 P3 — 관리자 강제 종료·오프라인 이탈 포함).
    await waitFor(() => expect(screen.getByText('상담이 종료되었습니다.')).not.toBeNull());
    expect(screen.getByText('상담종료')).not.toBeNull();
    expect(screen.queryByText('왼쪽 목록에서 상담을 선택하세요.')).toBeNull();
    expect(screen.queryByRole('button', { name: '상담 종료' })).toBeNull();
  });

  // #1590 P2 — selectedTicket은 목록/navigate state 스냅샷이라 원격 종료 후에도 IN_PROGRESS로 남는다.
  // 예전에는 티켓을 바꿨다 돌아오면 remoteEndedTicket이 리셋되면서 종료 표시가 사라지고 입력창이
  // 다시 열렸다(전송하면 서버가 거부). 이제 훅이 서버에서 최신 상태를 다시 확인해 종료를 유지한다.
  it('원격 종료 후 다른 티켓을 거쳐 돌아와도 종료 상태가 유지된다(#1590)', async () => {
    renderPage();

    // 이미 배정된(IN_PROGRESS) 티켓은 클릭 즉시 대화창이 열린다.
    fireEvent.click(await screen.findByText(mockInProgressQueueTicket.title));
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    capturedHandlers?.onEnded?.({
      ...mockInProgressQueueTicket,
      status: 'OFFLINE_LEFT',
    });
    await waitFor(() =>
      expect(screen.getByText('고객이 연결을 종료해 상담이 종료되었습니다.')).not.toBeNull(),
    );

    // 서버는 이제 이 티켓을 종료 상태로 응답한다(대기열 조회에서는 빠지므로 목록으로는 알 수 없다).
    server.use(
      http.get('/api/counsel/tickets/:id', ({ params }) => {
        if (Number(params.id) !== mockInProgressQueueTicket.id) {
          return HttpResponse.json({ success: true, data: mockQueueTickets[0] });
        }
        return HttpResponse.json({
          success: true,
          data: { ...mockInProgressQueueTicket, status: 'OFFLINE_LEFT' },
        });
      }),
    );

    // 다른 티켓(대기 중)으로 갔다가 되돌아온다.
    fireEvent.click(screen.getByText(mockQueueTickets[0].title));
    await screen.findByRole('button', { name: '상담 배정받기' });
    fireEvent.click(screen.getByText(mockInProgressQueueTicket.title));

    await waitFor(() =>
      expect(screen.getByText('고객이 연결을 종료해 상담이 종료되었습니다.')).not.toBeNull(),
    );
    expect(screen.getByText('상담종료')).not.toBeNull();
    expect(screen.queryByRole('button', { name: '상담 종료' })).toBeNull();
    // 입력창도 비활성 상태여야 한다(전송해도 서버가 거부하는 상태).
    expect(
      (screen.getByPlaceholderText('메시지를 입력하세요') as HTMLInputElement).disabled,
    ).toBe(true);
  });

  it('정보 패널 "정보/메모" 탭에서 기존 비공개 메모를 불러와 보여준다(#1022)', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockInProgressQueueTicket.title));

    expect(await screen.findByLabelText('비공개 메모')).toHaveProperty(
      'value',
      '고객이 등급 산정 기준 재설명 요청함.',
    );
    expect(screen.getByText('고객 프로필')).not.toBeNull();
  });

  it('비공개 메모를 수정하고 저장하면 저장 시각이 갱신된다(#1022)', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    const textarea = await screen.findByLabelText('비공개 메모');
    expect((textarea as HTMLTextAreaElement).value).toBe('');

    fireEvent.change(textarea, { target: { value: '새 메모 내용' } });
    fireEvent.click(screen.getByRole('button', { name: '메모 저장' }));

    expect(await screen.findByText(/마지막 저장:/)).not.toBeNull();
  });
});
