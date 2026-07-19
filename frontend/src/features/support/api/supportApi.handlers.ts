import { http, HttpResponse } from 'msw';
import { mockRagAnswer, mockRagNoResult } from '../mocks/support.mock';
import type { RagAnswerData, RagChatRequest } from '../types';

// POST /api/ai/rag-chat — aiClient baseURL(/api/ai) 기준 전체 경로. AIResponse({success,data,usage,error}) envelope.
// 개발 편의: query 내용으로 3상태를 유도(정상 / 0건 / 에러) — 실 계약은 설계 §7/§9 확정 후 정리.
export const supportHandlers = [
  http.post('/api/ai/rag-chat', async ({ request }) => {
    const reqBody = (await request.json()) as Partial<RagChatRequest>;
    const query = reqBody?.query ?? '';

    // 에러 상태 — aiClient 인터셉터가 success:false를 ApiError로 reject
    if (query.includes('에러')) {
      return HttpResponse.json({
        success: false,
        error: {
          code: 'LLM_INVALID_OUTPUT',
          message: 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.',
        },
      });
    }

    // 검색 0건 상태
    if (query.includes('없음') || query.trim() === '') {
      const body = { success: true, data: mockRagNoResult, usage: { tokens: 0 } };
      return HttpResponse.json(body);
    }

    // 정상 상태
    const body: { success: boolean; data: RagAnswerData; usage: { tokens: number } } = {
      success: true,
      data: mockRagAnswer,
      usage: { tokens: 0 },
    };
    return HttpResponse.json(body);
  }),
];
