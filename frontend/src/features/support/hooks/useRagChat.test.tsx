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
import type { ChatSessionMessageResponse } from '../types';
import { getRagSessionId, setRagSessionId } from '../utils/ragSessionId';
import { RAG_NO_RESULT_TEXT, useRagChat } from './useRagChat';

const server = setupServer(...supportHandlers);

// /api/ai/rag-chat, /api/chat-sessions 로 실제 나간 요청 수 — 인플라이트 가드·retry·세션
// 생성/복원 검증용(HAJA-668, #1548).
let ragRequestCount = 0;
let sessionCreateCount = 0;
server.events.on('request:start', ({ request }) => {
  const pathname = new URL(request.url).pathname;
  if (pathname === '/api/ai/rag-chat') ragRequestCount += 1;
  if (pathname === '/api/chat-sessions' && request.method === 'POST') sessionCreateCount += 1;
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  ragRequestCount = 0;
  sessionCreateCount = 0;
  // 세션 localStorage는 훅의 SoT라 테스트 간 오염을 막기 위해 매번 비운다.
  localStorage.clear();
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

  it('확정 계약(설계 §4.3/§9): 백엔드가 0건을 success:false(RAG_NO_RESULT)로 줘도 에러가 아닌 안내로 표시한다', async () => {
    // 기본 핸들러도 이제 동일 형태(success:false+RAG_NO_RESULT)를 반환하지만, 계약을 명시적으로 고정해두기 위해 오버라이드로 재확인.
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

  // --- 세션 라이프사이클(HAJA-668 / #1548, 설계 §2) ---

  it('최초 질의 시 세션을 생성하고 session_id를 localStorage에 저장한다', async () => {
    expect(getRagSessionId()).toBeNull();
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('시설물 안전점검 주기 알려줘');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    expect(sessionCreateCount).toBe(1);
    expect(getRagSessionId()).not.toBeNull();
  });

  it('같은 세션 내 후속 질의는 세션을 다시 생성하지 않는다', async () => {
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('첫 질문');
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());
    expect(sessionCreateCount).toBe(1);

    act(() => {
      result.current.send('두 번째 질문');
    });
    await waitFor(() => expect(usersOf(result.current.messages)).toHaveLength(2));
    await waitFor(() => expect(ragRequestCount).toBe(2));

    expect(sessionCreateCount).toBe(1);
  });

  it('localStorage에 저장된 세션이 있으면 마운트 시 이력을 복원한다', async () => {
    setRagSessionId(7);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', () => {
        const data: ChatSessionMessageResponse[] = [
          {
            id: 1,
            sessionId: 7,
            sender: 'USER',
            content: '이전 질문',
            citations: [],
            createdAt: new Date().toISOString(),
          },
          {
            id: 2,
            sessionId: 7,
            sender: 'BOT',
            content: '이전 답변',
            citations: [
              { documentId: '12', chunkRef: '12_1', locator: '제1조', snippet: '법령 스니펫' },
            ],
            createdAt: new Date().toISOString(),
          },
        ];
        return HttpResponse.json({ success: true, data });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    await waitFor(() => expect(result.current.messages).toHaveLength(2));

    expect(usersOf(result.current.messages)[0].text).toBe('이전 질문');
    const assistant = assistantOf(result.current.messages);
    expect(assistant?.text).toBe('이전 답변');
    expect(assistant?.sources?.[0]).toMatchObject({
      doc_id: '12',
      locator: '제1조',
      chunk_ref: '12_1',
    });
    // 복원 경로는 이미 세션이 있으므로 새로 생성하지 않는다.
    expect(sessionCreateCount).toBe(0);
  });

  it('세션 복원 실패(403 등)면 조용히 로컬 세션을 지우고 빈 상태로 시작한다', async () => {
    setRagSessionId(99);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', () =>
        HttpResponse.json(
          { success: false, error: { code: 'FORBIDDEN', message: '세션 접근 불가' } },
          { status: 403 },
        ),
      ),
    );

    const { result } = renderHook(() => useRagChat());

    await waitFor(() => expect(getRagSessionId()).toBeNull());
    expect(result.current.messages).toHaveLength(0);
    expect(result.current.error).toBeNull();
  });

  it('startNewChat: 로컬 세션과 메시지를 초기화해 다음 질의에서 새 세션을 발급받는다', async () => {
    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('첫 질문');
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());
    expect(sessionCreateCount).toBe(1);
    const firstSessionId = getRagSessionId();

    act(() => {
      result.current.startNewChat();
    });

    expect(result.current.messages).toHaveLength(0);
    expect(getRagSessionId()).toBeNull();

    act(() => {
      result.current.send('새 대화의 첫 질문');
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    expect(sessionCreateCount).toBe(2);
    expect(getRagSessionId()).not.toBe(firstSessionId);
  });
});
