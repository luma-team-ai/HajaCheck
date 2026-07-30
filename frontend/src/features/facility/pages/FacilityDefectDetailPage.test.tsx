// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityComparisonHandlers } from '../api/facilityComparisonApi.handlers';
import { facilityDefectHandlers } from '../api/facilityDefectApi.handlers';
import { FacilityDefectDetailPage } from './FacilityDefectDetailPage';

const server = setupServer(...facilityDefectHandlers, ...facilityComparisonHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(initialEntry = '/facilities/1/defects/1'): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/facilities/:id/defects/:defectId" element={<FacilityDefectDetailPage />} />
          <Route path="/facilities/:id/defects/:defectId/compare" element={<div>회차비교 화면</div>} />
          <Route path="/inspections/:id/defects" element={<div>점검 하자 목록 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FacilityDefectDetailPage (통합 테스트)', () => {
  it('하자 정보(유형·등급·크기·발견·담당)를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('하자 상세')).not.toBeNull();
    expect(screen.getByText('균열')).not.toBeNull();
    expect(screen.getByText('외벽 동측 12층 부근')).not.toBeNull();
    expect(screen.getByText('김검수')).not.toBeNull();
  });

  it('기본 선택 탭은 "오버레이"이고 이미지 위에 마킹 레이어가 함께 렌더링된다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    expect(screen.getByRole('tab', { name: '오버레이' }).getAttribute('aria-selected')).toBe(
      'true',
    );
    // 오버레이 마킹 레이어는 alt=""(장식용)라 접근성 트리에서 이미지 role이 아니라 hidden으로 빠진다 —
    // aria-hidden 이미지는 role 쿼리로 잡히지 않으므로 원본 사진(하자 이미지)만 role로 확인한다.
    expect(screen.getByRole('img', { name: '균열 하자 이미지' })).not.toBeNull();
  });

  it('"원본" 탭 클릭 시 원본으로 전환되고 오버레이 마킹 레이어가 사라진다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('tab', { name: '원본' }));

    expect(screen.getByRole('tab', { name: '원본' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '오버레이' }).getAttribute('aria-selected')).toBe(
      'false',
    );
  });

  it('AI 설명 패널은 로딩 후 진단·권장조치 텍스트를 표시한다', async () => {
    renderPage();

    expect(await screen.findByText(/구조적 스트레스로 인한 진행성 균열/)).not.toBeNull();
  });

  it('활동 기록을 GET /api/defects/{id}/revisions 결과로 렌더링한다(#1351)', async () => {
    renderPage();

    expect(await screen.findByText("하자 등급을 'D'에서 'E'(으)로 변경했습니다.")).not.toBeNull();
    expect(screen.getByText("상태를 '탐지됨'에서 '확인됨'(으)로 변경했습니다.")).not.toBeNull();
  });

  it('defectId가 숫자로 변환되지 않으면(NaN) 활동 기록 패널을 렌더하지 않고 revisions API를 호출하지 않는다(#1351)', async () => {
    let revisionsCallCount = 0;
    server.use(
      http.get('/api/defects/:id/revisions', () => {
        revisionsCallCount += 1;
        return HttpResponse.json({
          success: true,
          data: { content: [], page: 0, totalElements: 0 },
        });
      }),
    );

    // 라우트 세그먼트 자체는 채워지지만 숫자로 변환 불가능한 값(비정상 진입/오래된 링크 등)이라
    // Number(defectId)가 NaN이 되는 경로를 재현한다. facilityDefectApi.handlers.ts의
    // GET /api/defects/:id는 id 검증 없이 항상 성공 응답을 주므로(mockFacilityDefectDetailResponse
    // 고정 반환) 이 경로에서도 하자 상세 자체는 정상 렌더된다 — 활동 기록 패널만 가드돼야 한다.
    renderPage('/facilities/1/defects/abc');

    expect(await screen.findByText('하자 상세')).not.toBeNull();
    expect(screen.queryByText('활동 기록')).toBeNull();
    expect(revisionsCallCount).toBe(0);
  });

  it('"다음 단계로 전이" 클릭 시 해당 점검의 하자 목록으로 이동한다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('button', { name: '다음 단계로 전이' }));

    expect(await screen.findByText('점검 하자 목록 화면')).not.toBeNull();
  });

  it('"회차비교" 탭 클릭 시 /facilities/:id/compare로 이동한다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('tab', { name: '회차비교' }));

    expect(await screen.findByText('회차비교 화면')).not.toBeNull();
  });
});
