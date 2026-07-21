import { http, HttpResponse } from 'msw';
import { mockRagAnswer } from '../mocks/support.mock';
import type { RagAnswerData, RagChatRequest } from '../types';

// POST /api/ai/rag-chat — aiClient baseURL(/api/ai) 기준 전체 경로. AIResponse({success,data,usage,error}) envelope.
// 개발 편의: query가 아래 전용 트리거로 "시작"할 때만 0건/에러 상태를 유도한다.
// 기존엔 '없음'/'에러' 부분문자열로 분기 → "누수가 없음을 어떻게 증명?" 같은 정당한 질문이 오분기됨(#433).
// 확정 계약(설계 §4.3/§9, #431): 검색 0건은 success:false + error.code=RAG_NO_RESULT.
export const SUPPORT_DEV_TRIGGER = {
  noResult: '__no_result__',
  error: '__error__',
} as const;

export const supportHandlers = [
  http.post('/api/ai/rag-chat', async ({ request }) => {
    const reqBody = (await request.json()) as Partial<RagChatRequest>;
    const query = (reqBody?.query ?? '').trim();

    // 에러 상태 — aiClient 인터셉터가 success:false를 ApiError로 reject
    if (query.startsWith(SUPPORT_DEV_TRIGGER.error)) {
      return HttpResponse.json({
        success: false,
        error: {
          code: 'LLM_INVALID_OUTPUT',
          message: 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.',
        },
      });
    }

    // 검색 0건 상태 — 전용 트리거 또는 빈 질의. 확정 계약(설계 §4.3): success:false + RAG_NO_RESULT.
    if (query.startsWith(SUPPORT_DEV_TRIGGER.noResult) || query === '') {
      return HttpResponse.json({
        success: false,
        error: {
          code: 'RAG_NO_RESULT',
          message: '관련 근거를 찾지 못했습니다',
        },
      });
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
