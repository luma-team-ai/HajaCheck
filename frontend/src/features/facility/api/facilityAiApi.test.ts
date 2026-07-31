// @vitest-environment jsdom
// aiClient는 baseURL='/api/ai'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityAiApi } from './facilityAiApi';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

// #1350 code-reviewer P1 — 스프링 프록시 DTO(DefectExplainRequest.java)가 location을 @NotBlank로
// 강제한다. 빈 location을 그대로 보내면 400이 나 "위치 미입력 하자도 AI 설명 표시"라는 목표를
// 달성하지 못하므로, 빈 문자열은 자리표시자로 치환해 요청을 보내야 한다.
describe('facilityAiApi.getDefectExplanation', () => {
  it('location이 빈 문자열이면 자리표시자로 치환해 요청한다', async () => {
    let capturedBody: Record<string, string> | null = null;
    server.use(
      http.post('/api/ai/defect-explain', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, string>;
        return HttpResponse.json({
          success: true,
          data: { cause: 'c', risk: 'r', action: 'a' },
        });
      }),
    );

    await facilityAiApi.getDefectExplanation({
      defectId: 1,
      defectType: '균열',
      grade: 'D',
      location: '',
      facilityType: '건물',
    });

    expect(capturedBody).not.toBeNull();
    expect(capturedBody!.location).not.toBe('');
    expect(capturedBody!.location.length).toBeGreaterThan(0);
  });

  it('location이 있으면 그대로 요청한다', async () => {
    let capturedBody: Record<string, string> | null = null;
    server.use(
      http.post('/api/ai/defect-explain', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, string>;
        return HttpResponse.json({
          success: true,
          data: { cause: 'c', risk: 'r', action: 'a' },
        });
      }),
    );

    await facilityAiApi.getDefectExplanation({
      defectId: 1,
      defectType: '균열',
      grade: 'D',
      location: '외벽 동측 12층 부근',
      facilityType: '건물',
    });

    expect(capturedBody!.location).toBe('외벽 동측 12층 부근');
  });

  // PR머신 P1 — 실 엔드포인트 POST /api/ai/defect-explain의 응답은 {cause,risk,action}이다
  // (ai-server tests/test_defect_explain.py:60,76, defect 기능의 DefectExplain과 동일 계약).
  // 응답 매핑이 실 계약과 어긋나면 목이 잘못된 형태를 반환해도 이 테스트가 그 불일치를 드러낸다.
  it('실 응답 형태({cause,risk,action})를 받으면 해당 필드가 그대로 채워진다', async () => {
    server.use(
      http.post('/api/ai/defect-explain', () =>
        HttpResponse.json({
          success: true,
          data: { cause: '진행성 균열', risk: '구조 강도 저하', action: '에폭시 주입 보수' },
        }),
      ),
    );

    const res = await facilityAiApi.getDefectExplanation({
      defectId: 1,
      defectType: '균열',
      grade: 'D',
      location: '외벽 동측 12층 부근',
      facilityType: '건물',
    });

    expect(res.data.cause).toBe('진행성 균열');
    expect(res.data.risk).toBe('구조 강도 저하');
    expect(res.data.action).toBe('에폭시 주입 보수');
  });
});
