// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LandingPage from './LandingPage';
import { useAuthStore } from '../auth/store/authStore';
import type { User } from '../auth/types';
import { planApi, planQueryKeys, type PlanCatalogResponse } from '../../shared/api/planApi';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '하자체크',
  status: 'ACTIVE',
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useAuthStore.setState({ user: null });
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

// 로그인 사용자 리다이렉트 검증용 — 랜딩이 /dashboard로 넘어갔는지를 화면 전환으로 확인한다.
function renderLandingRoute() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<LandingPage />} />
          <Route path="/dashboard" element={<div>대시보드 페이지</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
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

  it('제품 스크린샷에 고유 크기를 지정해 로드 전 레이아웃 이동(CLS)을 방지한다', () => {
    renderWithProviders(<LandingPage />);

    const productScreenshots = [
      screen.getByAltText('분석 결과 뷰어 화면'),
      screen.getByAltText('시설물 점검 주기 설정 화면'),
      screen.getByAltText('하자 상세 화면'),
    ];

    productScreenshots.forEach((image) => {
      expect(image.getAttribute('width')).toBeTruthy();
      expect(image.getAttribute('height')).toBeTruthy();
    });
  });

  it('요금제 카드에는 가입 또는 문의 CTA를 표시하지 않는다', () => {
    renderWithProviders(<LandingPage />);

    expect(screen.queryByRole('link', { name: '무료 시작' })).toBeNull();
    expect(screen.queryByRole('link', { name: '업그레이드 문의' })).toBeNull();
    expect(screen.queryByRole('link', { name: '도입 문의' })).toBeNull();
  });

  it('레포에 없는 센서, BIM, 드론 연동 기능을 랜딩에 소개하지 않는다', () => {
    renderWithProviders(<LandingPage />);

    expect(screen.queryByText(/센서 데이터|BIM 연동|드론 촬영/)).toBeNull();
    expect(screen.getByText(/시설물 정보와 점검 이력을 한 곳에서 관리하고/)).toBeTruthy();
    expect(screen.getByText(/현장 사진과 영상을 업로드하면/)).toBeTruthy();
  });

  it('공개 요금제 API를 통해 요금제 카탈로그 데이터를 동적으로 받아와 렌더링한다', async () => {
    const plans: PlanCatalogResponse = {
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
    vi.spyOn(planApi, 'getPlans').mockResolvedValueOnce({ data: plans } as Awaited<
      ReturnType<typeof planApi.getPlans>
    >);

    renderWithProviders(<LandingPage />);

    await waitFor(() => {
      expect(screen.getByText('₩12,345')).toBeTruthy();
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('이미 불러온 요금제가 있으면 후속 재조회 실패에도 기존 요금제를 유지한다', async () => {
    const plans: PlanCatalogResponse = {
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
    const getPlans = vi.spyOn(planApi, 'getPlans');
    getPlans.mockResolvedValueOnce({ data: plans } as Awaited<ReturnType<typeof planApi.getPlans>>);
    getPlans.mockRejectedValueOnce(new Error('Network error'));

    const { queryClient } = renderWithProviders(<LandingPage />);

    await waitFor(() => expect(screen.getByText('₩12,345')).toBeTruthy());
    await queryClient.invalidateQueries({ queryKey: planQueryKeys.catalog });

    await waitFor(() => expect(getPlans).toHaveBeenCalledTimes(2));
    expect(screen.getByText('₩12,345')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  // 로그인 사용자 리다이렉트(#1474) — WAITING은 예외. 대시보드로 보내면 ProtectedRoute가 다시
  // /invite-code로 되돌려 초대 코드 화면의 "홈으로"·로고·"취소"가 제자리로 튕긴다.
  it('status=WAITING(초대 코드 미입력) 사용자는 대시보드로 보내지 않고 랜딩에 머무르게 한다', async () => {
    useAuthStore.setState({ user: { ...mockUser, companyId: null, status: 'WAITING' } });

    renderLandingRoute();

    await waitFor(() => expect(screen.getByAltText('하자 상세 화면')).toBeTruthy());
    expect(screen.queryByText('대시보드 페이지')).toBeNull();
  });

  it('status=ACTIVE 사용자는 기존대로 대시보드로 이동한다', async () => {
    useAuthStore.setState({ user: mockUser });

    renderLandingRoute();

    await waitFor(() => expect(screen.getByText('대시보드 페이지')).toBeTruthy());
  });

  it('WAITING 사용자에게는 헤더 CTA를 로그인 대신 초대 코드 입력으로 노출한다', async () => {
    useAuthStore.setState({ user: { ...mockUser, companyId: null, status: 'WAITING' } });

    renderLandingRoute();

    const cta = await screen.findByRole('link', { name: '초대 코드 입력' });
    expect(cta.getAttribute('href')).toBe('/invite-code');
    expect(screen.queryByRole('link', { name: '로그인' })).toBeNull();
  });

  it('공개 요금제 API 호출 실패 시 하드코딩 요금제가 아닌 에러 안내 메시지와 다시 시도 버튼을 렌더링한다', async () => {
    vi.spyOn(planApi, 'getPlans').mockRejectedValueOnce(new Error('Network error'));

    renderWithProviders(<LandingPage />);

    await waitFor(() => {
      expect(screen.getByText('요금제 정보를 불러오지 못했습니다.')).toBeTruthy();
      expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    });
  });
});
