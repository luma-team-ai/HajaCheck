import { http, HttpResponse } from 'msw';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';

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

export const counselHandlers = [
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
    if (!mockTickets.some((t) => t.id === id)) {
      return HttpResponse.json(
        { success: false, error: { code: 'COUNSEL_TICKET_NOT_FOUND', message: '상담 티켓을 찾을 수 없습니다.' } },
        { status: 404 },
      );
    }
    return HttpResponse.json({ success: true, data: mockMessages });
  }),
];
