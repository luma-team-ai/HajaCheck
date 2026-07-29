// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it } from 'vitest';
import LandingPage from './LandingPage';

afterEach(() => {
  cleanup();
});

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
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

  it('공개 요금제 API를 통해 요금제 카탈로그 데이터를 동적으로 받아와 렌더링한다', async () => {
    renderWithProviders(<LandingPage />);

    await waitFor(() => {
      expect(screen.getByText('합리적인 요금제')).toBeTruthy();
      expect(screen.getByText('₩0')).toBeTruthy();
      expect(screen.getByText('₩29,000')).toBeTruthy();
      expect(screen.getByText('₩59,000')).toBeTruthy();
    });
  });
});