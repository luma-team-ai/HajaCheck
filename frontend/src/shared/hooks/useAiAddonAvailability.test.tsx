// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ReactNode } from 'react';
import type { ApiResponse } from '../api/types';
import type { CurrentPlanResponse, PlanCatalogResponse } from '../api/planApi';
import { useAiAddonAvailability } from './useAiAddonAvailability';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function currentPlanHandler(name: string) {
  return http.get('/api/me/plan', () => {
    const body: ApiResponse<CurrentPlanResponse> = {
      success: true,
      data: { plan: { name } },
    };
    return HttpResponse.json(body);
  });
}

function catalogHandler(name: string, hasAiAddon: boolean) {
  return http.get('/api/plans', () => {
    const body: ApiResponse<PlanCatalogResponse> = {
      success: true,
      data: {
        plans: [{
          id: 99,
          name,
          maxFacilities: null,
          maxMonthlyAnalyses: null,
          maxSeats: null,
          hasPdfWatermark: false,
          hasCounselorAccess: false,
          hasAiAddon,
          priceMonthly: 0,
        }],
      },
    };
    return HttpResponse.json(body);
  });
}

function renderAvailability() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useAiAddonAvailability(), { wrapper });
}

describe('useAiAddonAvailability', () => {
  it('조회 중에는 차단하고 플랜명 하드코딩 없이 현재 이름과 실시간 정책을 조합한다', async () => {
    server.use(currentPlanHandler('CUSTOM_AI_PLAN'), catalogHandler('CUSTOM_AI_PLAN', true));

    const { result } = renderAvailability();

    expect(result.current).toBe('checking');
    await waitFor(() => expect(result.current).toBe('available'));
  });

  it('현재 플랜의 카탈로그 정책이 AI 미지원이면 unavailable을 반환한다', async () => {
    server.use(currentPlanHandler('CUSTOM_BASIC_PLAN'), catalogHandler('CUSTOM_BASIC_PLAN', false));

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current).toBe('unavailable'));
  });

  it('플랜 조회 실패 시 정상 사용자를 선차단하지 않고 서버 검증으로 폴백한다', async () => {
    server.use(
      http.get('/api/me/plan', () => HttpResponse.error()),
      catalogHandler('CUSTOM_AI_PLAN', true),
    );

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current).toBe('unknown'));
  });

  it('현재 플랜이 실시간 카탈로그에 없으면 잘못 차단하지 않는다', async () => {
    server.use(currentPlanHandler('NEW_PLAN'), catalogHandler('OLDER_PLAN', false));

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current).toBe('unknown'));
  });
});
