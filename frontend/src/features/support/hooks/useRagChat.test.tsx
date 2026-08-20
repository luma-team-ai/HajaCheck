// @vitest-environment jsdom
// useRagChat 통합 테스트(#435) — 실제 supportHandlers(정상/0건/에러 3분기) + 훅을 함께 검증한다.
// useDefectExplain.test.tsx 관례를 따르되, useRagChat은 순수 useState/useRef 훅이라
// (React Query 미사용) @testing-library/react의 renderHook으로 직접 구동한다.
import { act, renderHook, waitFor } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { SUPPORT_DEV_TRIGGER, supportHandlers } from '../api/supportApi.handlers';
import { mockRagAnswer } from '../mocks/support.mock';
import type { ChatSessionMessageResponse } from '../types';
import { getRagSessionId, setRagSessionId } from '../utils/ragSessionId';
import { RAG_NO_RESULT_TEXT, RAG_RESTORE_WAIT_TIMEOUT_MS, useRagChat } from './useRagChat';

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
              // 실제 Jackson 직렬화(Long→JSON number)를 재현 — 문자열로 목킹하면 doc_id 문자열
              // 변환 누락(PR #1563 P2)을 테스트가 못 잡는다. title/collection은 #1698로 세션
              // 이력 응답에 추가된 필드(#1700 확정 계약).
              {
                documentId: 12,
                title: '시설물의 안전 및 유지관리에 관한 특별법',
                collection: 'regulations',
                chunkRef: '12_1',
                locator: '제1조',
                snippet: '법령 스니펫',
              },
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
    // 회귀 방지(#1700): 칩 라벨은 title이어야 하고, snippet(청크 원문)이 title 자리에 들어가면 안 된다.
    expect(assistant?.sources?.[0]).toMatchObject({
      doc_id: '12',
      title: '시설물의 안전 및 유지관리에 관한 특별법',
      collection: 'regulations',
      locator: '제1조',
      chunk_ref: '12_1',
      snippet: '법령 스니펫',
    });
    expect(assistant?.sources?.[0].title).not.toBe('법령 스니펫');
    // 복원 경로는 이미 세션이 있으므로 새로 생성하지 않는다.
    expect(sessionCreateCount).toBe(0);
  });

  // #1700 확정 계약: title이 빈 값이면 `문서 #{documentId}`로 폴백한다(백엔드 title 필드가
  // 비어 있는 예외 상황 방어).
  it('세션 복원 citation의 title이 빈 값이면 문서 #{documentId}로 폴백한다', async () => {
    setRagSessionId(8);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', () => {
        const data: ChatSessionMessageResponse[] = [
          {
            id: 1,
            sessionId: 8,
            sender: 'BOT',
            content: '답변',
            citations: [
              { documentId: 34, title: '', collection: 'regulations', chunkRef: '34_2', locator: '제2조', snippet: '스니펫' },
            ],
            createdAt: new Date().toISOString(),
          },
        ];
        return HttpResponse.json({ success: true, data });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    await waitFor(() => expect(result.current.messages).toHaveLength(1));

    const assistant = assistantOf(result.current.messages);
    expect(assistant?.sources?.[0].title).toBe('문서 #34');
  });

  // #1597 확정 계약: 인용된 문서가 삭제되면 FK ON DELETE SET NULL로 documentId/title/collection이
  // 전부 null로 응답된다 — "삭제된 문서"로 표시하되 locator/snippet(citation 행 자체 보관)은 그대로
  // 노출한다. title==='' 폴백(#1700, `문서 #{documentId}`)과는 다른 갈래임을 함께 고정한다.
  it('세션 복원 citation의 title이 null이면 삭제된 문서로 표시하고 locator·snippet은 유지한다', async () => {
    setRagSessionId(9);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', () => {
        const data: ChatSessionMessageResponse[] = [
          {
            id: 1,
            sessionId: 9,
            sender: 'BOT',
            content: '답변',
            citations: [
              {
                documentId: null,
                title: null,
                collection: null,
                chunkRef: '55_1',
                locator: '제3조',
                snippet: '삭제된 문서의 인용 발췌',
              },
            ],
            createdAt: new Date().toISOString(),
          },
        ];
        return HttpResponse.json({ success: true, data });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    await waitFor(() => expect(result.current.messages).toHaveLength(1));

    const assistant = assistantOf(result.current.messages);
    expect(assistant?.sources?.[0]).toMatchObject({
      doc_id: '',
      title: '삭제된 문서',
      collection: null,
      locator: '제3조',
      chunk_ref: '55_1',
      snippet: '삭제된 문서의 인용 발췌',
    });
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

  // PR #1563 P2 픽스 검증 — 경쟁 조건 2건.

  it('진행 중인 질의가 있을 때 startNewChat을 호출하면, 뒤늦게 도착한 이전 응답이 새 대화에 섞이지 않는다', async () => {
    server.use(
      http.post('/api/ai/rag-chat', async () => {
        await delay(50);
        return HttpResponse.json({ success: true, data: mockRagAnswer, usage: { tokens: 0 } });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('첫 번째 대화 질문');
    });
    expect(result.current.loading).toBe(true);

    // 응답이 도착하기 전에 새 대화 시작 — in-flight 상태에서도 클릭 가능(AiAssistantPage는
    // messages.length만 보고 loading은 안 봄).
    act(() => {
      result.current.startNewChat();
    });
    expect(result.current.messages).toHaveLength(0);

    // 지연됐던 첫 번째 질의의 응답이 이제 도착 — 폐기되어야 하고, 새 대화는 계속 빈 상태.
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(result.current.messages).toHaveLength(0);

    // startNewChat이 inFlightRef를 풀어놨으므로 새 대화에서 바로 다음 질의가 가능해야 한다.
    act(() => {
      result.current.send('새 대화의 질문');
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());
    expect(usersOf(result.current.messages)).toHaveLength(1);
    expect(usersOf(result.current.messages)[0].text).toBe('새 대화의 질문');
  });

  it('마운트 시 세션 복원이 늦게 도착해도, 그 사이 사용자가 보낸 대화를 덮어쓰지 않는다', async () => {
    setRagSessionId(7);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', async () => {
        await delay(80);
        const data: ChatSessionMessageResponse[] = [
          {
            id: 1,
            sessionId: 7,
            sender: 'USER',
            content: '이전 질문',
            citations: [],
            createdAt: new Date().toISOString(),
          },
        ];
        return HttpResponse.json({ success: true, data });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    // 복원 GET이 아직 진행 중인 사이 사용자가 새 질의를 보낸다.
    act(() => {
      result.current.send('새로 보낸 질문');
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());
    expect(usersOf(result.current.messages)[0].text).toBe('새로 보낸 질문');

    // 늦게 도착한 복원 응답이 방금 주고받은 대화를 덮어쓰지 않아야 한다.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(usersOf(result.current.messages)).toHaveLength(1);
    expect(usersOf(result.current.messages)[0].text).toBe('새로 보낸 질문');
    // 세션이 이미 있었으므로(복원 대상 세션 7) 새로 생성하지 않는다 — #1590 이후에도 유지되는
    // 계약이다. runQuery가 복원 GET을 기다렸다가 200(=내 세션 확인)이면 그 세션을 그대로 쓴다.
    expect(sessionCreateCount).toBe(0);
    expect(getRagSessionId()).toBe(7);
  });

  // #1590 — 복원 GET이 이례적으로 지연되면 질의가 막히면 안 된다(상한). 이때는 새 세션으로
  // 진행하고, 뒤늦게 도착한 복원 200이 그 세션을 옛 세션으로 되돌리지 않아야 한다.
  it('복원 GET이 상한(RAG_RESTORE_WAIT_TIMEOUT_MS)을 넘기면 기다리지 않고 새 세션으로 질의한다', async () => {
    setRagSessionId(7);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', async () => {
        await delay(RAG_RESTORE_WAIT_TIMEOUT_MS + 1500);
        const data: ChatSessionMessageResponse[] = [];
        return HttpResponse.json({ success: true, data });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('상한 초과 시에도 답을 받아야 하는 질문');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined(), {
      timeout: RAG_RESTORE_WAIT_TIMEOUT_MS + 2000,
    });
    expect(result.current.error).toBeNull();
    expect(sessionCreateCount).toBe(1);
    const committedId = getRagSessionId();
    expect(committedId).not.toBe(7);

    // 뒤늦게 도착한 복원 200이 이미 쓰고 있는 세션을 덮어쓰지 않아야 한다.
    await new Promise((resolve) => setTimeout(resolve, 2000));
    expect(getRagSessionId()).toBe(committedId);
    expect(usersOf(result.current.messages)).toHaveLength(1);
    // 상한(3초) + 늦은 복원 도착까지 기다리므로 기본 testTimeout(5초)보다 길게 잡는다.
  }, 15000);

  // #1590 리뷰 P3-A — 세션 발급이 실패하면 "확정"이 아니라 "시도"였을 뿐이다. 되돌리지 않으면
  // 이후 도착한 복원 200이 저장된 세션을 채택하지 못해 대화가 갈라진다(P2-1의 좁은 재현 경로).
  it('createSession 실패 뒤 도착한 복원 200은 저장된 세션을 정상 채택한다', async () => {
    setRagSessionId(7);
    const sentSessionIds: Array<number | undefined> = [];
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', async () => {
        await delay(RAG_RESTORE_WAIT_TIMEOUT_MS + 800);
        const data: ChatSessionMessageResponse[] = [];
        return HttpResponse.json({ success: true, data });
      }),
      http.post('/api/chat-sessions', () =>
        HttpResponse.json(
          { success: false, error: { code: 'SESSION_CREATE_FAILED', message: '세션 생성 실패' } },
          { status: 500 },
        ),
      ),
      http.post('/api/ai/rag-chat', async ({ request }) => {
        const body = (await request.json()) as { sessionId?: number };
        sentSessionIds.push(body?.sessionId);
        return HttpResponse.json({ success: true, data: mockRagAnswer, usage: { tokens: 0 } });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('세션 발급이 실패하는 첫 질문');
    });

    // 상한 초과 → 세션 발급 시도 → 실패로 에러 표시(이 시점 sessionCommittedRef는 되돌아가야 한다)
    await waitFor(() => expect(result.current.error).not.toBeNull(), {
      timeout: RAG_RESTORE_WAIT_TIMEOUT_MS + 2000,
    });
    expect(sessionCreateCount).toBe(1);

    // 뒤늦게 복원 200 도착 — 저장된 세션(7)이 채택돼야 한다.
    await new Promise((resolve) => setTimeout(resolve, 1500));

    act(() => {
      result.current.retry();
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    expect(sentSessionIds).toEqual([7]); // 새 세션을 또 만들지 않고 복원된 세션을 쓴다
    expect(sessionCreateCount).toBe(1);
    expect(getRagSessionId()).toBe(7);
  }, 20000);

  // #1590 리뷰 P3-B — 복원 GET이 영원히 응답하지 않아도(api 인스턴스에 자체 timeout 없음) 대기는
  // 첫 질의 1회로 끝나야 한다. 매 질의마다 상한만큼 지연되면 사용자는 계속 3초씩 손해를 본다.
  it('응답 없는 복원 GET이어도 두 번째 질의부터는 대기 없이 즉시 진행한다', async () => {
    setRagSessionId(7);
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', async () => {
        await delay('infinite');
        return HttpResponse.json({ success: true, data: [] });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('첫 질문');
    });
    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined(), {
      timeout: RAG_RESTORE_WAIT_TIMEOUT_MS + 2000,
    });

    const startedAt = Date.now();
    act(() => {
      result.current.send('두 번째 질문');
    });
    await waitFor(() => expect(usersOf(result.current.messages)).toHaveLength(2));
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(Date.now() - startedAt).toBeLessThan(RAG_RESTORE_WAIT_TIMEOUT_MS);
  }, 20000);

  // #1590 P2 — 계정 전환 후 이전 사용자의 session_id가 남아 있고, 복원 GET(403)이 도착하기 전에
  // 새 사용자가 첫 질문을 보내는 레이스. 예전에는 마운트 즉시 sessionIdRef에 옛 id를 넣어 그
  // 질의가 403으로 실패했다(화면에 에러 배너). 이제는 새 세션으로 정상 처리돼야 한다.
  it('복원 GET(403)이 도착하기 전에 보낸 첫 질문도 이전 사용자 세션이 아닌 새 세션으로 나간다', async () => {
    setRagSessionId(7);
    const sentSessionIds: Array<number | undefined> = [];
    server.use(
      http.get('/api/chat-sessions/:sessionId/messages', async () => {
        await delay(80);
        return HttpResponse.json(
          { success: false, error: { code: 'FORBIDDEN', message: '세션 접근 불가' } },
          { status: 403 },
        );
      }),
      http.post('/api/ai/rag-chat', async ({ request }) => {
        const body = (await request.json()) as { sessionId?: number };
        sentSessionIds.push(body?.sessionId);
        return HttpResponse.json({ success: true, data: mockRagAnswer, usage: { tokens: 0 } });
      }),
    );

    const { result } = renderHook(() => useRagChat());

    act(() => {
      result.current.send('계정 전환 후 첫 질문');
    });

    await waitFor(() => expect(assistantOf(result.current.messages)).toBeDefined());

    expect(result.current.error).toBeNull();
    expect(sessionCreateCount).toBe(1);
    expect(sentSessionIds).toHaveLength(1);
    expect(sentSessionIds[0]).not.toBe(7);

    // 늦게 도착한 403 복원 실패가 방금 발급받은 세션을 지우면 안 된다.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(getRagSessionId()).toBe(sentSessionIds[0]);
  });
});
