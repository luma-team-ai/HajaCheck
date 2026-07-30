// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LandingPage from './LandingPage';
import { publicPlanApi, type PublicPlanCatalogResponse } from './api/publicPlanApi';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{ui}</MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

describe('LandingPage 제품 스크린샷 및 요금제', () => {
  it('초기 화면 아래의 제품 스크린샷을 lazy loading 한다', () => {
    renderWithProviders(<LandingPage />);

    const productScreenshots = [
      screen.getByAltText('분석 결과 뷰어 화면'),
      screen.getByAltText('시설물 점검 주기 설정 화면'),
      screen.getByAltText('하자 상세 화면'),
    ];

    productScreenshots.forEach((image) => {
      expect(image.getAttribute('loading')).toBe('lazy');
    });
  });

  it('공개 요금제 API를 통해 요금제 카탈로그 데이터를 동적으로 받아와 렌더링한다', async () => {
    const plans: PublicPlanCatalogResponse = {
      plans: [
        {
          id: 1,
          name: 'FREE',
          maxFacilities: 1,
          maxMonthlyAnalyses: 50,
          maxSeats: 1,
          hasPdfWatermark: true,
          hasCounselorAccess: false,
          hasAiAddon: false,
          priceMonthly: 12345,
        },
      ],
    };
    vi.spyOn(publicPlanApi, 'getPlans').mockResolvedValueOnce({ data: plans } as Awaited<
      ReturnType<typeof publicPlanApi.getPlans>
    >);

    renderWithProviders(<LandingPage />);

    await waitFor(() => {
      expect(screen.getByText('₩12,345')).toBeTruthy();
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('이미 불러온 요금제가 있으면 후속 재조회 실패에도 기존 요금제를 유지한다', async () => {
    const plans: PublicPlanCatalogResponse = {
      plans: [
        {
          id: 1,
          name: 'FREE',
          maxFacilities: 1,
          maxMonthlyAnalyses: 50,
          maxSeats: 1,
          hasPdfWatermark: true,
          hasCounselorAccess: false,
          hasAiAddon: false,
          priceMonthly: 12345,
        },
      ],
    };
    const getPlans = vi.spyOn(publicPlanApi, 'getPlans');
    getPlans.mockResolvedValueOnce({ data: plans } as Awaited<ReturnType<typeof publicPlanApi.getPlans>>);
    getPlans.mockRejectedValueOnce(new Error('Network error'));

    const { queryClient } = renderWithProviders(<LandingPage />);

    await waitFor(() => expect(screen.getByText('₩12,345')).toBeTruthy());
    await queryClient.invalidateQueries({ queryKey: ['publicPlans'] });

    await waitFor(() => expect(getPlans).toHaveBeenCalledTimes(2));
    expect(screen.getByText('₩12,345')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('공개 요금제 API 호출 실패 시 하드코딩 요금제가 아닌 에러 안내 메시지와 다시 시도 버튼을 렌더링한다', async () => {
    vi.spyOn(publicPlanApi, 'getPlans').mockRejectedValueOnce(new Error('Network error'));

    renderWithProviders(<LandingPage />);

    await waitFor(() => {
      expect(screen.getByText('요금제 정보를 불러오지 못했습니다.')).toBeTruthy();
      expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    });
  });
});
