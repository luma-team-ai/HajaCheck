// @vitest-environment jsdom
// FacilityDetailPage 통합 테스트 — 실제 useFacility(MSW facilityHandlers) + 목 useFacilityInspectionOverview 조합을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityHandlers } from '../api/facilityApi.handlers';
import { FacilityDetailPage } from './FacilityDetailPage';

const server = setupServer(...facilityHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(initialEntry = '/facilities/detail') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <FacilityDetailPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FacilityDetailPage (통합 테스트)', () => {
  it('실 API로 시설물 기본 정보를 불러와 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '강남 오피스타워 A동' })).not.toBeNull();
    expect(screen.getByText(/준공 2008/)).not.toBeNull();
  });

  it('점검 회차/누적 하자/미조치 통계를 목 데이터로 표시한다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    expect(screen.getByText('8')).not.toBeNull();
    expect(screen.getByText('43')).not.toBeNull();
    expect(screen.getByText('12')).not.toBeNull();
  });

  it('기본 활성 탭은 점검 이력이고, 최신 회차만 결과 보기/보고서 링크가 펼쳐진다', async () => {
    renderPage();

    expect(await screen.findByText('8회차 점검')).not.toBeNull();
    expect(screen.getByText(/— 2026-06-21 · 이엔지/)).not.toBeNull();
    expect(screen.getByText('7회차 점검')).not.toBeNull();
    expect(screen.getByText(/— 2025-12-10 · 내부점검/)).not.toBeNull();
    expect(screen.getAllByText('결과 보기')).toHaveLength(1);
  });

  it('다른 탭을 클릭하면 준비 중 안내가 표시된다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    fireEvent.click(screen.getByRole('button', { name: '개요' }));

    expect(screen.getByText('준비 중인 화면입니다.')).not.toBeNull();
  });

  it('존재하지 않는 시설물이면 에러 메시지를 표시한다', async () => {
    renderPage('/facilities/detail?facilityId=999');

    expect(await screen.findByText('시설물 정보를 불러오지 못했습니다.')).not.toBeNull();
  });

  it('+ 새 점검 버튼을 누르면 점검(회차) 생성 화면으로 이동한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/facilities/detail']}>
          <Routes>
            <Route path="/facilities/detail" element={<FacilityDetailPage />} />
            <Route path="/inspections/create" element={<div>점검 생성 화면</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });
    fireEvent.click(screen.getByRole('button', { name: '+ 새 점검' }));

    expect(await screen.findByText('점검 생성 화면')).not.toBeNull();
  });
});
