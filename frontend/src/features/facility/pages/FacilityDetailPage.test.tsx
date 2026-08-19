// @vitest-environment jsdom
// FacilityDetailPage 통합 테스트 — 실제 useFacility(MSW facilityHandlers) + 목 useFacilityInspectionOverview 조합을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { facilityHandlers } from '../api/facilityApi.handlers';
import { mockFacilities } from '../mocks/facility.mock';
import type { Facility } from '../types';
import { FacilityDetailPage } from './FacilityDetailPage';

const server = setupServer(...facilityHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(initialEntry = '/facilities/1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/facilities/:id" element={<FacilityDetailPage />} />
        </Routes>
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

  it('"개요" 탭은 더 이상 표시되지 않는다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    expect(screen.queryByRole('button', { name: '개요' })).toBeNull();
  });

  it('"문서" 탭은 더 이상 표시되지 않는다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    expect(screen.queryByRole('button', { name: '문서' })).toBeNull();
  });

  it('대표 하자가 없으면 "하자 현황" 탭 클릭 시 로컬 탭 전환(준비 중 안내)만 된다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    fireEvent.click(screen.getByRole('button', { name: '하자 현황' }));

    expect(screen.getByText('준비 중인 화면입니다.')).not.toBeNull();
  });

  it('대표 하자가 있으면 "하자 현황" 탭 클릭 시 하자 상세 오버레이로 이동한다', async () => {
    const facilityWithDefect: Facility = { ...mockFacilities[0], latestDefectId: 42 };
    server.use(
      http.get('/api/facilities/:id', () => {
        const body: ApiResponse<Facility> = { success: true, data: facilityWithDefect };
        return HttpResponse.json(body);
      }),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/facilities/1']}>
          <Routes>
            <Route path="/facilities/:id" element={<FacilityDetailPage />} />
            <Route path="/facilities/:id/defects/:defectId" element={<div>하자 상세 오버레이</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    fireEvent.click(screen.getByRole('button', { name: '하자 현황' }));

    expect(await screen.findByText('하자 상세 오버레이')).not.toBeNull();
  });

  it('"결과 보기" 클릭 시 같은 시설물·같은 회차로 좁힌 하자 관리 목록으로 이동한다(#1359 후속)', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/facilities/1']}>
          <Routes>
            <Route path="/facilities/:id" element={<FacilityDetailPage />} />
            <Route path="/defects/list" element={<div>하자 관리 목록</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByText('8회차 점검');

    fireEvent.click(screen.getByText('결과 보기'));

    expect(await screen.findByText('하자 관리 목록')).not.toBeNull();
  });

  it('"보고서" 클릭 시 같은 시설물·같은 회차로 좁힌 보고서 목록으로 이동한다(#1359 후속)', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/facilities/1']}>
          <Routes>
            <Route path="/facilities/:id" element={<FacilityDetailPage />} />
            <Route path="/reports" element={<div>보고서 목록</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByText('8회차 점검');

    fireEvent.click(screen.getByText('보고서'));

    expect(await screen.findByText('보고서 목록')).not.toBeNull();
  });

  it('존재하지 않는 시설물이면 에러 메시지를 표시한다', async () => {
    renderPage('/facilities/999');

    expect(await screen.findByText('시설물 정보를 불러오지 못했습니다.')).not.toBeNull();
  });

  it('id가 숫자가 아니면(예: 사이드바 플레이스홀더 /facilities/detail) 조회 없이 바로 에러 메시지를 표시한다', async () => {
    renderPage('/facilities/detail');

    expect(await screen.findByText('시설물 정보를 불러오지 못했습니다.')).not.toBeNull();
  });

  // #1549 — "+N"(추가 사진) 클릭 시 분석 결과 뷰어로 이동해야 한다(이전엔 onClick 없는 <div>라
  // 클릭해도 아무 동작이 없었음).
  it('점검 이력 "+N" 클릭 시 분석 결과 뷰어로 이동한다(#1549)', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/facilities/1']}>
          <Routes>
            <Route path="/facilities/:id" element={<FacilityDetailPage />} />
            <Route path="/inspections/:id/viewer" element={<div>분석 결과 뷰어 화면</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByText('8회차 점검');
    fireEvent.click(screen.getByRole('button', { name: '+212' }));

    expect(await screen.findByText('분석 결과 뷰어 화면')).not.toBeNull();
  });

  it('+ 새 점검 버튼을 누르면 점검(회차) 생성 화면으로 이동한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/facilities/1']}>
          <Routes>
            <Route path="/facilities/:id" element={<FacilityDetailPage />} />
            <Route path="/inspections/create" element={<div>점검 생성 화면</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });
    fireEvent.click(screen.getByRole('button', { name: '+ 새 점검' }));

    expect(await screen.findByText('점검 생성 화면')).not.toBeNull();
  });

  // #1681 — PUT /api/facilities/{id}는 기존 존재했으나(#618) 이 화면엔 미배선 상태였다. "수정"
  // 버튼으로 진입해 프리필된 폼을 저장하면 실 API로 반영되고, 갱신된 값이 화면에 다시 표시돼야 한다.
  describe('시설물 수정(#1681)', () => {
    it('"수정" 버튼을 누르면 기존 값이 프리필된 수정 모달이 열린다', async () => {
      renderPage();
      await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

      fireEvent.click(screen.getByRole('button', { name: '수정' }));

      expect(await screen.findByRole('heading', { name: '시설물 수정' })).not.toBeNull();
      expect((screen.getByLabelText(/시설물명/) as HTMLInputElement).value).toBe(
        '강남 오피스타워 A동',
      );
    });

    it('수정 폼을 저장하면 PUT API로 반영되고 화면에 갱신된 값이 표시된다', async () => {
      renderPage();
      await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

      fireEvent.click(screen.getByRole('button', { name: '수정' }));
      await screen.findByRole('heading', { name: '시설물 수정' });

      fireEvent.change(screen.getByLabelText(/시설물명/), {
        target: { value: '강남 오피스타워 A동(리모델링)' },
      });

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '수정하기' }));
      });

      expect(
        await screen.findByRole('heading', { name: '강남 오피스타워 A동(리모델링)' }),
      ).not.toBeNull();
      // 저장이 성공하면 모달이 닫힌다 — 다음 렌더에서 수정 모달 제목은 더 이상 없어야 한다.
      expect(screen.queryByRole('heading', { name: '시설물 수정' })).toBeNull();
    });
  });
});
