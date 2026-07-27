import { http, HttpResponse } from 'msw';
import type {
  BotScenarioButtonResponse,
  ChatMessageResponse,
  CounselTicketSummaryResponse,
} from '../types';

export const mockTickets: CounselTicketSummaryResponse[] = [
  {
    id: 1,
    ticketNumber: 'CS-20260621-014',
    category: '분석 결과 문의',
    title: '분석 결과 등급 문의',
    userId: 100,
    counselorId: 9,
    counselorName: '김상담',
    status: 'IN_PROGRESS',
    queuePosition: null,
    createdAt: new Date().toISOString(),
  },
  {
    id: 2,
    ticketNumber: 'CS-20260618-009',
    category: '요금제 변경',
    title: '엔터프라이즈 요금제 혜택 문의',
    userId: 100,
    counselorId: 11,
    counselorName: '이지영',
    status: 'RESOLVED',
    queuePosition: null,
    createdAt: '2026-06-18T10:00:00',
  },
];

export const mockMessages: ChatMessageResponse[] = [
  {
    id: 1,
    sessionId: 700,
    sender: 'COUNSELOR',
    content: '안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.',
    attachmentUrl: null,
    counselorName: '김상담',
    createdAt: '2026-06-21T14:20:00',
  },
  {
    id: 2,
    sessionId: 700,
    sender: 'USER',
    content: '지난번보다 등급이 왜 달라졌는지 궁금합니다.',
    attachmentUrl: null,
    createdAt: '2026-06-21T14:21:00',
  },
];

// 상담원 콘솔 대기열(#1001, HAJA-495) — mockTickets와 별개 픽스처. WAITING 상태 티켓만 대기열에 뜬다.
export const mockQueueTickets: CounselTicketSummaryResponse[] = [
  {
    id: 3,
    ticketNumber: 'CS-20260727-001',
    category: '점검 결과서 관련',
    title: 'AI 분석 결과 등급 문의',
    userId: 200,
    counselorId: null,
    counselorName: null,
    status: 'WAITING',
    queuePosition: 1,
    createdAt: '2026-07-27T09:00:00',
  },
];

// 담당 상담(IN_PROGRESS, #1001 후속) — 종료되지 않은 내 상담이 콘솔 목록에 계속 보이는지 검증용.
// counselorId=9는 assign 목 핸들러가 배정하는 값과 동일(상담원 콘솔 통합테스트의 로그인 상담원).
export const mockInProgressQueueTicket: CounselTicketSummaryResponse = {
  id: 4,
  ticketNumber: 'CS-20260726-020',
  category: '요금제 변경',
  title: '진행 중인 상담',
  userId: 201,
  counselorId: 9,
  counselorName: '김상담',
  status: 'IN_PROGRESS',
  queuePosition: null,
  createdAt: '2026-07-26T09:00:00',
};

export const mockScenarioRoots: BotScenarioButtonResponse[] = [
  { id: 10, category: 'INSPECTION_REPORT', buttonLabel: '점검 결과서 관련', leadsToCounselor: false },
  { id: 20, category: 'ACCOUNT_BILLING', buttonLabel: '계정 및 결제', leadsToCounselor: false },
];

export const mockCounselorLeaf: BotScenarioButtonResponse = {
  id: 11,
  category: 'INSPECTION_REPORT',
  buttonLabel: '상담원 연결',
  leadsToCounselor: true,
};

export const counselHandlers = [
  http.get('/api/counsel/scenarios/roots', () =>
    HttpResponse.json({ success: true, data: mockScenarioRoots }),
  ),
  http.get('/api/counsel/scenarios/:id', ({ params }) => {
    const id = Number(params.id);
    if (id === 10) {
      return HttpResponse.json({
        success: true,
        data: {
          id: 10,
          parentId: null,
          category: 'INSPECTION_REPORT',
          buttonLabel: '점검 결과서 관련',
          responseText: null,
          leadsToCounselor: false,
          children: [mockCounselorLeaf],
        },
      });
    }
    return HttpResponse.json(
      { success: false, error: { code: 'COUNSEL_SCENARIO_NOT_FOUND', message: '상담 시나리오를 찾을 수 없습니다.' } },
      { status: 404 },
    );
  }),
  http.post('/api/counsel/tickets', () =>
    HttpResponse.json({ success: true, data: { ...mockTickets[0], queuePosition: 2 } }),
  ),
  http.get('/api/counsel/tickets/mine', ({ request }) => {
    const url = new URL(request.url);
    const status = url.searchParams.get('status');
    const content =
      !status || status === 'ALL' ? mockTickets : mockTickets.filter((t) => t.status === status);
    return HttpResponse.json({
      success: true,
      data: { content, page: 0, totalElements: content.length },
    });
  }),
  http.get('/api/counsel/tickets/:id/messages', ({ params }) => {
    const id = Number(params.id);
    if (!mockTickets.some((t) => t.id === id) && !mockQueueTickets.some((t) => t.id === id)) {
      return HttpResponse.json(
        { success: false, error: { code: 'COUNSEL_TICKET_NOT_FOUND', message: '상담 티켓을 찾을 수 없습니다.' } },
        { status: 404 },
      );
    }
    return HttpResponse.json({ success: true, data: mockMessages });
  }),
  // 상담원 콘솔(#1001, HAJA-495) — GET /api/counsel/tickets(대기열). status 쿼리로 WAITING/IN_PROGRESS를
  // 구분한다(#1001 후속: useCounselorQueue가 두 상태를 병렬 조회해 합치므로 실제 백엔드처럼 분기 필요).
  http.get('/api/counsel/tickets', ({ request }) => {
    const url = new URL(request.url);
    const status = url.searchParams.get('status') ?? 'WAITING';
    const content =
      status === 'IN_PROGRESS' ? [mockInProgressQueueTicket] : mockQueueTickets.filter((t) => t.status === status);
    return HttpResponse.json({
      success: true,
      data: { content, page: 0, totalElements: content.length },
    });
  }),
  // POST .../assign — 기본은 성공(첫 호출). 409 경합 시나리오는 개별 테스트가 server.use()로 override.
  http.post('/api/counsel/tickets/:id/assign', ({ params }) => {
    const id = Number(params.id);
    const ticket = mockQueueTickets.find((t) => t.id === id);
    if (!ticket) {
      return HttpResponse.json(
        { success: false, error: { code: 'COUNSEL_TICKET_NOT_FOUND', message: '상담 티켓을 찾을 수 없습니다.' } },
        { status: 404 },
      );
    }
    return HttpResponse.json({
      success: true,
      data: { ...ticket, counselorId: 9, counselorName: '김상담', status: 'IN_PROGRESS', sessionId: 700, endedAt: null },
    });
  }),
  // GET /api/counsel/tickets/:id/customer-history(#1001 후속) — 고정 픽스처 1건 반환.
  http.get('/api/counsel/tickets/:id/customer-history', () =>
    HttpResponse.json({
      success: true,
      data: [
        {
          id: 99,
          ticketNumber: 'CS-20260701-002',
          category: '요금제 변경',
          title: '지난 요금제 변경 문의',
          userId: 201,
          counselorId: 9,
          counselorName: '김상담',
          status: 'RESOLVED',
          queuePosition: null,
          createdAt: '2026-07-01T09:00:00',
        },
      ],
    }),
  ),
  // GET .../customer-history/:historyId/messages(#1001 후속) — 드릴다운 상세 대화.
  http.get('/api/counsel/tickets/:id/customer-history/:historyId/messages', () =>
    HttpResponse.json({
      success: true,
      data: [
        {
          id: 201,
          sessionId: 500,
          sender: 'USER',
          content: '요금제를 낮추고 싶어요.',
          attachmentUrl: null,
          createdAt: '2026-07-01T09:01:00',
        },
        {
          id: 202,
          sessionId: 500,
          sender: 'COUNSELOR',
          counselorName: '이상담',
          content: '어떤 요금제로 변경을 원하시나요?',
          attachmentUrl: null,
          createdAt: '2026-07-01T09:02:00',
        },
      ],
    }),
  ),
  http.post('/api/counsel/tickets/:id/resolve', ({ params }) => {
    const id = Number(params.id);
    return HttpResponse.json({
      success: true,
      data: {
        id,
        ticketNumber: 'CS-20260727-001',
        category: '점검 결과서 관련',
        title: 'AI 분석 결과 등급 문의',
        userId: 200,
        counselorId: 9,
        counselorName: '김상담',
        sessionId: 700,
        status: 'RESOLVED',
        queuePosition: null,
        createdAt: '2026-07-27T09:00:00',
        endedAt: '2026-07-27T09:30:00',
      },
    });
  }),
];
