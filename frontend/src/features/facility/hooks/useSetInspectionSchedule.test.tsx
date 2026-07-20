// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityHandlers, resetFacilityMockStore } from '../api/facilityApi.handlers';
import { resetInspectionCycleStatusMockStore } from '../mocks/inspectionCycle.mock';
import { useInspectionCycleStatusRows } from './useInspectionCycleStatusRows';
import { useSetInspectionSchedule } from './useSetInspectionSchedule';

// react-reviewer P1 회귀 방지: 저장 성공 시 현황 목록 쿼리(inspectionCycleStatusListKey)가
// invalidate되고, mutable 목 store가 실제로 갱신되어 좌측 카드와 우측 현황 테이블이 같은 값을 본다.
const server = setupServer(...facilityHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  resetFacilityMockStore();
  resetInspectionCycleStatusMockStore();
});
afterAll(() => server.close());

// 두 훅을 같은 컴포넌트 트리(한 renderHook 호출)에서 함께 구독한다 — 실제 화면(페이지+테이블)도
// 같은 QueryClientProvider 아래서 동작하며, 별도 renderHook 트리로 쪼개면 옵저버 갱신 타이밍이
// 테스트 환경에서만 어긋나는 오탐을 만들 수 있어 통합된 하나의 트리로 구성한다.
function useTestHarness() {
  const statusList = useInspectionCycleStatusRows();
  const mutation = useSetInspectionSchedule();
  return { statusList, mutation };
}

function renderHarness() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useTestHarness(), { wrapper });
}

describe('useSetInspectionSchedule', () => {
  it('저장 성공 시 현황 목록 쿼리가 갱신되어 해당 행의 주기가 반영된다(좌/우 불일치 회귀 방지)', async () => {
    const { result } = renderHarness();

    await waitFor(() => expect(result.current.statusList.isSuccess).toBe(true));
    const before = result.current.statusList.data?.find((row) => row.id === 3);
    expect(before?.cycleMonths).toBe(6);

    await result.current.mutation.setSchedule({
      facilityId: 3,
      body: { inspectionCycleMonths: 9 },
    });

    await waitFor(() => {
      const after = result.current.statusList.data?.find((row) => row.id === 3);
      expect(after?.cycleMonths).toBe(9);
    });
  });

  it('저장 성공 시 응답의 다음점검일로 현황 목록 행이 갱신된다', async () => {
    const { result } = renderHarness();
    await waitFor(() => expect(result.current.statusList.isSuccess).toBe(true));

    const saveResult = await result.current.mutation.setSchedule({
      facilityId: 3,
      body: { inspectionCycleMonths: 3 },
    });

    await waitFor(() => {
      const after = result.current.statusList.data?.find((row) => row.id === 3);
      expect(after?.nextInspectionDueAt).toBe(saveResult.nextInspectionDueAt);
    });
  });

  it('존재하지 않는 시설물이면 에러를 던지고 현황 목록은 그대로 유지된다', async () => {
    const { result } = renderHarness();
    await waitFor(() => expect(result.current.statusList.isSuccess).toBe(true));

    await expect(
      result.current.mutation.setSchedule({
        facilityId: 999,
        body: { inspectionCycleMonths: 6 },
      }),
    ).rejects.toMatchObject({ code: 'FACILITY_NOT_FOUND' });

    const row3 = result.current.statusList.data?.find((row) => row.id === 3);
    expect(row3?.cycleMonths).toBe(6);
  });

  it('서버 검증 실패(0개월 이하) 시 목 store를 갱신하지 않는다', async () => {
    server.use(
      http.post('/api/facilities/:id/schedule', () =>
        HttpResponse.json(
          {
            success: false,
            data: null,
            error: { code: 'SCHEDULE_VALIDATION_ERROR', message: '점검 주기는 1개월 이상이어야 합니다.' },
          },
          { status: 400 },
        ),
      ),
    );
    const { result } = renderHarness();
    await waitFor(() => expect(result.current.statusList.isSuccess).toBe(true));

    await expect(
      result.current.mutation.setSchedule({ facilityId: 3, body: { inspectionCycleMonths: 0 } }),
    ).rejects.toMatchObject({ code: 'SCHEDULE_VALIDATION_ERROR' });

    const row3 = result.current.statusList.data?.find((row) => row.id === 3);
    expect(row3?.cycleMonths).toBe(6);
  });
});
