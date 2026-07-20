// @vitest-environment jsdom
// useRagChat 통합 테스트(#435) — 실제 supportHandlers(정상/0건/에러 3분기) + 훅을 함께 검증한다.
// useDefectExplain.test.tsx 관례를 따르되, useRagChat은 순수 useState/useRef 훅이라
// (React Query 미사용) @testing-library/react의 renderHook으로 직접 구동한다.
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { SUPPORT_DEV_TRIGGER, supportHandlers } from '../api/supportApi.handlers';
import { mockRagAnswer } from '../mocks/support.mock';
import { RAG_NO_RESULT_TEXT, useRagChat } from './useRagChat';

const server = setupServer(...supportHandlers);

// /api/ai/rag-chat 로 실제 나간 요청 수 — 인플라이트 가드·retry 재요청 검증용.
let ragRequestCount = 0;
server.events.on('request:start', ({ request }) => {
  if (new URL(request.url).pathname === '/api/ai/rag-chat') ragRequestCount += 1;
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  ragRequestCount = 0;
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const usersOf = (messages: ReturnType<typeof useRagChat>['messages']) =>
  messages.filter((m) => m.role === 'user');
const assistantOf = (messages: ReturnType<typeof useRagChat>['messages']) =>
  messages.find((m) => m.role === 'assistant');

describe('useRagChat (통합 테스트)', () => {
  it('정상 질의: assistant 메시지에 answer와 sources(2건)가 렌더된다', async () => {
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('시설물 안전점검 주기 알려줘');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    const assistant = assistantOf(result.current.messages);
    expect(assistant?.text).toBe(mockRagAnswer.answer);
    expect(assistant?.sources).toHaveLength(2);
    expect(result.current.error).toBeNull();
    expect(ragRequestCount).toBe(1);
  });

  it('회귀 방지(#433/#444): "없음"/"에러"를 문장 중간에 포함해도 전용 트리거가 아니면 정상 응답을 받는다', async () => {
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('안전점검 사각지대가 없음을 어떻게 증명하나요?');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    const assistant = assistantOf(result.current.messages);
    expect(assistant?.text).toBe(mockRagAnswer.answer);
    expect(assistant?.text).not.toBe(RAG_NO_RESULT_TEXT);
    expect(result.current.error).toBeNull();
  });

  it('인플라이트 가드: 빠른 연속 send는 서버 요청·사용자 말풍선을 1개로 제한한다', async () => {
    const { result } = renderHook(() => useRagChat());

    // 동기 연속 호출 — inFlightRef가 두 번째 send를 즉시 차단해야 한다.
    act(() => {
      result.current.send('첫 번째 질문');
      result.current.send('두 번째 질문');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    expect(ragRequestCount).toBe(1);
    expect(usersOf(result.current.messages)).toHaveLength(1);
    expect(usersOf(result.current.messages)[0].text).toBe('첫 번째 질문');
  });

  it('검색 0건: RAG_NO_RESULT_TEXT 안내가 빈 sources로 렌더되고 에러가 아니다', async () => {
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send(SUPPORT_DEV_TRIGGER.noResult);
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    const assistant = assistantOf(result.current.messages);
    expect(assistant?.text).toBe(RAG_NO_RESULT_TEXT);
    expect(assistant?.sources).toEqual([]);
    expect(result.current.error).toBeNull();
  });

  it('방어 분기: 백엔드가 0건을 success:false(RAG_NO_RESULT)로 줘도 에러가 아닌 안내로 표시한다', async () => {
    // 설계 §9 미확정 대비 — envelope가 (a) success:false+RAG_NO_RESULT 로 와도 "근거 없음"으로 흡수.
    server.use(
      http.post('/api/ai/rag-chat', () =>
        HttpResponse.json({
          success: false,
          error: { code: 'RAG_NO_RESULT', message: '관련 근거 없음' },
        }),
      ),
    );

    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('임의 질의');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    const assistant = assistantOf(result.current.messages);
    expect(assistant?.text).toBe(RAG_NO_RESULT_TEXT);
    expect(assistant?.sources).toEqual([]);
    expect(result.current.error).toBeNull();
  });

  it('에러 후 retry: 사용자 말풍선 중복 없이 마지막 질의로만 재요청한다', async () => {
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send(SUPPORT_DEV_TRIGGER.error);
    });

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(usersOf(result.current.messages)).toHaveLength(1);
    expect(ragRequestCount).toBe(1);

    act(() => {
      result.current.retry();
    });

    // retry는 새 요청을 보내되(마지막 질의), 사용자 말풍선을 추가하지 않는다.
    await waitFor(() => expect(ragRequestCount).toBe(2));
    expect(usersOf(result.current.messages)).toHaveLength(1);
  });
});
