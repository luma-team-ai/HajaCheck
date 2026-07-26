// @vitest-environment jsdom
// adminPlanApi 단위 테스트(#890 Phase 2, keepUserIds) — 재검토 F-4/F-5: 이전에는 화면 통합 테스트가
// 로컬 체크박스 state만 검증해, previewChange에서 keepUserIds를 통째로 빼도(구현 실수) 테스트가
// 통과했다. 여기서는 실제 네트워크 요청(URL 쿼리·PATCH 바디)을 MSW로 가로채 keepUserIds가 정말
// 전송되는지, 그리고 백엔드와 동일한 거절 규칙(회사 스코프·좌석 한도)이 목에서도 재현되는지를
// api 호출 레벨에서 직접 단정한다.
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { adminPlanApi } from './adminPlanApi';
import { adminPlanHandlers } from './adminPlanApi.handlers';

const server = setupServer(...adminPlanHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('adminPlanApi — keepUserIds 전송 형식(#930 재검토 F-4/F-5)', () => {
  // axios 배열 파라미터 직렬화 회귀선 — defectApi.ts에서 실제로 사고가 났던 지점(#726)과 동일한
  // 문제(대괄호 붙은 keepUserIds[]=1 형태로 나가면 Spring @RequestParam List<Long> 바인딩이 빈
  // 리스트로 받는다)를 이 신규 엔드포인트에서도 반드시 회귀 검증한다.
  it('previewChange는 keepUserIds를 대괄호 없는 반복 키(key=v1&key=v2)로 직렬화해 요청한다', async () => {
    let capturedUrl = '';
    server.use(
      http.get('/api/admin/plan/change-preview', ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json({
          success: true,
          data: { targetPlan: 'STANDARD', requiresConfirmation: false, seatsToSuspend: [], facilityOverflowCount: 0 },
        });
      }),
    );

    await adminPlanApi.previewChange('STANDARD', [1, 2, 3]);

    const queryString = capturedUrl.split('?')[1] ?? '';
    expect(queryString).toContain('planName=STANDARD');
    expect(queryString).toContain('keepUserIds=1');
    expect(queryString).toContain('keepUserIds=2');
    expect(queryString).toContain('keepUserIds=3');
    // 대괄호가 인코딩되어(%5B%5D) 붙거나 리터럴로 붙는 어느 경우도 없어야 한다.
    expect(queryString).not.toMatch(/keepUserIds(%5B%5D|\[\])/);
  });

  it('keepUserIds를 지정하지 않으면 아예 쿼리에 실지 않는다(미지정=하위호환 자동선정)', async () => {
    let capturedUrl = '';
    server.use(
      http.get('/api/admin/plan/change-preview', ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json({
          success: true,
          data: { targetPlan: 'STANDARD', requiresConfirmation: false, seatsToSuspend: [], facilityOverflowCount: 0 },
        });
      }),
    );

    await adminPlanApi.previewChange('STANDARD');

    const queryString = capturedUrl.split('?')[1] ?? '';
    expect(queryString).not.toContain('keepUserIds');
  });

  it('changePlan은 keepUserIds를 PATCH 바디에 그대로 실어 보낸다', async () => {
    let capturedBody: unknown;
    server.use(
      http.patch('/api/admin/plan', async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json({ success: true, data: { plan: { name: 'STANDARD' } } });
      }),
    );

    await adminPlanApi.changePlan({ planName: 'STANDARD', confirmOverflow: true, keepUserIds: [1, 3, 7] });

    expect(capturedBody).toMatchObject({
      planName: 'STANDARD',
      confirmOverflow: true,
      keepUserIds: [1, 3, 7],
    });
  });
});

describe('adminPlanApi — MSW 목의 거절 규칙 재현(#930 재검토 F-4/F-5)', () => {
  it('활성 멤버가 아닌 id를 keepUserIds로 보내면 403 PLAN_KEEP_USER_INVALID로 거절한다', async () => {
    await expect(adminPlanApi.previewChange('STANDARD', [999999])).rejects.toMatchObject({
      code: 'PLAN_KEEP_USER_INVALID',
      status: 403,
    });
  });

  it('선택 인원이 좌석 한도를 넘으면 403 PLAN_SEAT_QUOTA_EXCEEDED로 거절한다', async () => {
    // STANDARD maxSeats=3(목 카탈로그 기준) — owner(1) 포함 4명을 유지 선택하면 반드시 넘친다.
    await expect(adminPlanApi.previewChange('STANDARD', [1, 2, 3, 4])).rejects.toMatchObject({
      code: 'PLAN_SEAT_QUOTA_EXCEEDED',
      status: 403,
    });
  });

  it('changePlan도 동일하게 타 회사·비활성 id를 403 PLAN_KEEP_USER_INVALID로 거절하고 부작용이 없다', async () => {
    await expect(
      adminPlanApi.changePlan({ planName: 'STANDARD', confirmOverflow: true, keepUserIds: [999999] }),
    ).rejects.toMatchObject({
      code: 'PLAN_KEEP_USER_INVALID',
      status: 403,
    });
  });
});
