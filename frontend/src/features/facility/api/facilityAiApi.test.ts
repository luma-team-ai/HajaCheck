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
          data: { diagnosis: 'd', recommendedAction: 'a' },
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
          data: { diagnosis: 'd', recommendedAction: 'a' },
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
});
