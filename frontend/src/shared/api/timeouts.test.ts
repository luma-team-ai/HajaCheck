// @vitest-environment jsdom
// #1598 — axios 인스턴스에 timeout이 없어 응답이 삼켜지면 모든 호출이 무한 대기하던 결함의 회귀 방지.
//
// 이 파일이 고정하는 계약은 세 가지다.
//   1. 두 axios 인스턴스(api·aiClient)에 실제로 상한이 걸려 있다.
//   2. 응답이 오지 않는 요청이 상한 후 reject 된다(= 호출부의 catch/finally가 실행된다).
//   3. 정상적으로 오래 걸리는 경로가 전역 상한(30초)에 끊기지 않는다(override가 실제로 먹는다).
//
// 3번이 중요한 이유: 전역 timeout만 넣고 override를 빠뜨리면 OCR·보고서 생성·대용량 업로드처럼
// 정상적으로 30초를 넘기는 요청이 매번 끊겨 지금보다 나빠진다. 그래서 "상한이 걸렸는가"뿐 아니라
// "장시간 경로에 더 큰 상한이 걸렸는가"까지 같이 고정한다.
// ⚠️ 검증 방식에 대한 메모(다음 사람이 같은 시간을 쓰지 않도록):
// msw는 응답을 붙잡고 있는 동안 XHR의 `timeout` 속성을 존중하지 않으므로, `delay('infinite')`
// 핸들러로는 실제 타임아웃을 구동할 수 없다(이 레포에서 실측 확인 — axios가 상한을 넘겨도 reject
// 되지 않고 테스트 자체의 제한시간까지 매달린다). 그건 테스트 인프라의 한계일 뿐 프로덕션 동작이
// 아니다 — 실서비스엔 msw가 없어 브라우저 XHR 타임아웃이 정상 발화한다. 그래서 여기서는
// `delay('infinite')`로 진짜 타임아웃을 재현하려 하지 않고(그건 우리 코드가 아니라 msw를 테스트하는
// 것이 된다) 검증을 두 축으로 나눈다:
//   ① config-capture — 전역 상한과 경로별 override가 실제 요청 config에 실리는지(배선)
//   ② 타임아웃 에러를 던지는 axios 어댑터 — 그 에러가 인터셉터를 타고 ApiError로 정규화되는지(처리)
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { AxiosError, type AxiosAdapter } from 'axios';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { aiClient } from './aiClient';
import { api } from './axios';
import { API_TIMEOUT_MS, AI_TIMEOUT_MS, totalBytesOf, uploadTimeoutMs } from './timeouts';
import type { ApiError } from './types';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/**
 * 실제 axios 타임아웃이 만들어내는 에러를 그대로 흉내내는 어댑터.
 * 브라우저에서 XHR timeout이 발화하면 axios는 code='ECONNABORTED'이고 `response`가 **없는**
 * AxiosError를 던진다 — 인터셉터의 에러 경로가 실제로 밟는 입력이 이것이다.
 */
const timeoutAdapter =
  (timeoutMs: number): AxiosAdapter =>
  (config) =>
    Promise.reject(
      new AxiosError(
        `timeout of ${timeoutMs}ms exceeded`,
        AxiosError.ECONNABORTED,
        config,
        {},
        undefined, // response 없음 — 타임아웃의 핵심 특징
      ),
    );

/** 이 요청이 실제로 들고 나간 timeout 값을 잡아낸다(override가 먹었는지 확인용). */
function captureTimeout(instance: typeof api): { get: () => number | undefined; eject: () => void } {
  let captured: number | undefined;
  const id = instance.interceptors.request.use((config) => {
    captured = config.timeout;
    return config;
  });
  return {
    get: () => captured,
    eject: () => instance.interceptors.request.eject(id),
  };
}

describe('전역 상한이 두 인스턴스에 걸려 있다', () => {
  it('api 인스턴스에 기본 timeout이 있다 — 없으면 axios 기본값 0(무한 대기)로 되돌아간다', () => {
    expect(api.defaults.timeout).toBe(API_TIMEOUT_MS);
    // 0/undefined는 "상한 없음"이라 이 이슈의 결함 그 자체다.
    expect(api.defaults.timeout).toBeGreaterThan(0);
  });

  it('aiClient 인스턴스에도 기본 timeout이 있다 — api에만 걸면 LLM 호출 4곳이 그대로 무한 대기로 남는다', () => {
    expect(aiClient.defaults.timeout).toBe(AI_TIMEOUT_MS);
    expect(aiClient.defaults.timeout).toBeGreaterThan(0);
  });

  it('AI 상한은 스프링 ai.server 천장(connect 3s + read 150s = 153초)보다 크다', () => {
    // 153초보다 작으면 백엔드가 아직 응답을 줄 수 있는 요청을 브라우저가 먼저 끊는다.
    expect(AI_TIMEOUT_MS).toBeGreaterThan(153_000);
  });

  it('전역 상한은 가장 느린 정상 CRUD 경로(사업자 진위확인 재시도 포함 22.3초)보다 크다', () => {
    // (connect 3000 + realtime-read 8000) × 2회 + backoff 300 = 22.3초
    expect(API_TIMEOUT_MS).toBeGreaterThan(22_300);
  });
});

describe('상한에 걸린 요청은 catch로 떨어진다 (타임아웃 에러 처리 경로)', () => {
  it('타임아웃이 나면 요청이 reject 되고 finally가 실행된다 — 로딩 고착이 풀린다', async () => {
    // 이 이슈의 증상은 "catch/finally가 영원히 실행되지 않는 것"이었다. 그래서 reject 여부보다
    // finally가 실제로 도는지가 핵심 검증이다.
    let finallyRan = false;
    const request = api
      .get('/anything', { adapter: timeoutAdapter(API_TIMEOUT_MS) })
      .finally(() => {
        finallyRan = true;
      });

    await expect(request).rejects.toBeDefined();
    expect(finallyRan).toBe(true);
  });

  it('timeout은 인터셉터를 거쳐 ApiError(NETWORK_ERROR) 형태로 나온다 — 호출부가 다루는 계약 유지', async () => {
    await api.get('/anything', { adapter: timeoutAdapter(API_TIMEOUT_MS) }).then(
      () => expect.unreachable('timeout 인데 resolve 되면 안 된다'),
      (error: ApiError) => {
        // 응답 자체가 없으므로 status는 undefined이고 code는 NETWORK_ERROR로 떨어진다.
        expect(error.code).toBe('NETWORK_ERROR');
        expect(error.message).toBeTruthy();
        expect(error.status).toBeUndefined();
      },
    );
  });

  it('timeout이어도 401 하드 리다이렉트로 오인되지 않는다 — status가 없으므로 로그인 화면으로 튕기면 안 된다', async () => {
    const hrefBefore = window.location.href;

    await api.get('/anything', { adapter: timeoutAdapter(API_TIMEOUT_MS) }).catch(() => {});

    expect(window.location.href).toBe(hrefBefore);
  });

  it('aiClient의 timeout도 ApiError로 정규화된다', async () => {
    await aiClient.post('/anything', {}, { adapter: timeoutAdapter(AI_TIMEOUT_MS) }).then(
      () => expect.unreachable('timeout 인데 resolve 되면 안 된다'),
      (error: ApiError) => {
        expect(error.code).toBe('NETWORK_ERROR');
        expect(error.message).toBeTruthy();
      },
    );
  });

  it('상한 안에 응답이 오면 정상 resolve 된다 — 상한이 정상 요청을 깨지 않는다', async () => {
    server.use(
      http.get('/api/responds-fast', () => HttpResponse.json({ success: true, data: { ok: true } })),
    );

    const res = await api.get<{ ok: boolean }>('/responds-fast', { timeout: 2000 });
    expect(res.data.ok).toBe(true);
  });
});

describe('장시간 경로는 전역 상한에 끊기지 않는다 (override가 실제로 먹는가)', () => {
  it('override를 주지 않은 일반 요청은 전역 30초를 그대로 물려받는다', async () => {
    server.use(http.get('/api/plain', () => HttpResponse.json({ success: true, data: null })));
    const captured = captureTimeout(api);

    await api.get('/plain');

    expect(captured.get()).toBe(API_TIMEOUT_MS);
    captured.eject();
  });

  it('AI 경유 요청은 전역 30초가 아니라 AI 상한을 쓴다', async () => {
    server.use(http.post('/api/defects/nl-search', () => HttpResponse.json({ success: true, data: {} })));
    const captured = captureTimeout(api);

    const { defectApi } = await import('../../features/defect/api/defectApi');
    await defectApi.nlSearch('균열');

    expect(captured.get()).toBe(AI_TIMEOUT_MS);
    expect(captured.get()).toBeGreaterThan(API_TIMEOUT_MS);
    captured.eject();
  });

  it('보고서 초안 생성(LLM)도 AI 상한을 쓴다 — 이름만 봐선 AI인 줄 모르는 경로', async () => {
    server.use(
      http.post('/api/inspections/1/reports', () => HttpResponse.json({ success: true, data: {} })),
    );
    const captured = captureTimeout(api);

    const { reportApi } = await import('../../features/report/api/reportApi');
    await reportApi.generateReportDraft(1);

    expect(captured.get()).toBe(AI_TIMEOUT_MS);
    captured.eject();
  });

  it('업로드 상한은 파일 크기에 비례해 커진다 — 큰 업로드가 고정 상한에 끊기지 않는다', async () => {
    server.use(
      http.post('/api/inspections/1/media', () => HttpResponse.json({ success: true, data: [] })),
    );
    const { mediaApi } = await import('../../features/inspection/api/mediaApi');

    // 20MB 한 장
    const small = captureTimeout(api);
    await mediaApi.upload(1, [new File([new Uint8Array(20 * 1024 * 1024)], 'a.jpg')]);
    const smallTimeout = small.get()!;
    small.eject();

    // 20MB 세 장 — 전송량이 3배면 상한도 그만큼 커져야 한다
    const large = captureTimeout(api);
    await mediaApi.upload(1, [
      new File([new Uint8Array(20 * 1024 * 1024)], 'a.jpg'),
      new File([new Uint8Array(20 * 1024 * 1024)], 'b.jpg'),
      new File([new Uint8Array(20 * 1024 * 1024)], 'c.jpg'),
    ]);
    const largeTimeout = large.get()!;
    large.eject();

    expect(smallTimeout).toBeGreaterThan(API_TIMEOUT_MS);
    expect(largeTimeout).toBeGreaterThan(smallTimeout);
  });

  it('계약상 최대 업로드(50장×20MB ≈ 1000MB)도 상한 안에 들어온다', () => {
    // 고정 120초 같은 값을 쓰면 이 계약이 깨진다 — 5Mbps 기준 전송에만 28분이 필요하다.
    const contractMaxBytes = 50 * 20 * 1024 * 1024;
    const allowed = uploadTimeoutMs(contractMaxBytes);
    const secondsNeededAt5Mbps = contractMaxBytes / 625_000;

    expect(allowed / 1000).toBeGreaterThanOrEqual(secondsNeededAt5Mbps);
  });
});

describe('uploadTimeoutMs 계산', () => {
  it('바이트가 0이면 처리 천장만 남는다', () => {
    expect(uploadTimeoutMs(0)).toBe(API_TIMEOUT_MS);
    expect(uploadTimeoutMs(0, AI_TIMEOUT_MS)).toBe(AI_TIMEOUT_MS);
  });

  it('전송 시간은 5Mbps(625 bytes/ms) 기준으로 더해진다', () => {
    // 625,000 bytes = 1초
    expect(uploadTimeoutMs(625_000)).toBe(API_TIMEOUT_MS + 1000);
  });

  it('음수/비정상 크기에도 처리 천장 아래로 내려가지 않는다', () => {
    expect(uploadTimeoutMs(-1)).toBe(API_TIMEOUT_MS);
  });

  it('totalBytesOf는 파일 크기를 합산한다', () => {
    const files = [new File(['ab'], 'a.txt'), new File(['cde'], 'b.txt')];
    expect(totalBytesOf(files)).toBe(5);
  });
});
