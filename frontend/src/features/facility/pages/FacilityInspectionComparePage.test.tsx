// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { facilityComparisonHandlers } from '../api/facilityComparisonApi.handlers';
import { mockInspectionComparison } from '../mocks/facilityComparison.mock';
import type { InspectionComparisonResult } from '../types';
import { FacilityInspectionComparePage } from './FacilityInspectionComparePage';

// html2canvas는 실 canvas 렌더링에 의존해 jsdom에서 재현 불가능한 브라우저 API 경계라
// export util을 모듈 경계에서 모킹한다(react-testing.md "네트워크/외부 경계 모킹" 관용구).
const exportComparisonReportAsPngMock = vi.fn().mockResolvedValue(undefined);
vi.mock('../utils/exportComparisonReportAsPng', () => ({
  exportComparisonReportAsPng: (...args: unknown[]) => exportComparisonReportAsPngMock(...args),
}));

const server = setupServer(...facilityComparisonHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  exportComparisonReportAsPngMock.mockClear();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/facilities/1/compare']}>
        <Routes>
          <Route path="/facilities/:id/compare" element={<FacilityInspectionComparePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FacilityInspectionComparePage (통합 테스트)', () => {
  // 2026-07-29 사용자 결정 — 회차를 직접 고르는 드롭다운을 제거하고, 서버가 자동으로 고른
  // 최근 2개 회차를 읽기 전용으로만 보여준다.
  it('제목과 서버가 고른 회차 1쌍을 읽기 전용으로 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('회차 간 비교')).not.toBeNull();
    expect(screen.getByLabelText('이전 회차').textContent).toContain(
      `${mockInspectionComparison.beforeCycle.cycle}회차`,
    );
    expect(screen.getByLabelText('현재 회차').textContent).toContain(
      `${mockInspectionComparison.afterCycle.cycle}회차`,
    );
    // select가 아니라 읽기 전용 표시라 편집 가능한 폼 컨트롤이 없어야 한다.
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  // #1157 회귀고정 — 과거엔 DEFAULT_BEFORE_CYCLE=7/DEFAULT_AFTER_CYCLE=8이 하드코딩돼 있어
  // 그 회차가 없는 시설물에서 화면 진입 즉시 실패했다. 최초 요청은 before/after를 아예 보내지
  // 않아야 한다(서버가 자동 대체하도록) — 하드코딩 값이 되살아나면 이 테스트가 잡는다.
  it('최초 진입 시 before/after 파라미터 없이 요청한다(#1157, 하드코딩 회차 회귀 방지)', async () => {
    let capturedSearch: string | null = null;
    const captureRequest = ({ request }: { request: Request }) => {
      capturedSearch = new URL(request.url).search;
    };
    server.events.on('request:match', captureRequest);

    try {
      renderPage();
      await screen.findByText('회차 간 비교');

      expect(capturedSearch).not.toBeNull();
      expect(capturedSearch).not.toContain('before=');
      expect(capturedSearch).not.toContain('after=');
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });

  it('KPI 카드 4개를 값과 함께 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('신규 하자')).not.toBeNull();
    expect(screen.getByText('14')).not.toBeNull();
    expect(screen.getByText('진행성 (악화)')).not.toBeNull();
    expect(screen.getByText('개선/조치 완료')).not.toBeNull();
    expect(screen.getByText('등급 상승')).not.toBeNull();
  });

  it('시각적 비교 패널과 진행성 균열 추이 차트를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('시각적 비교')).not.toBeNull();
    expect(screen.getByText('동일 촬영 지점 정렬됨', { exact: false })).not.toBeNull();
    expect(screen.getByText('진행성 균열 추이')).not.toBeNull();
    expect(screen.getByRole('img', { name: /균열 폭 추이/ })).not.toBeNull();
  });

  it('하자 변화 목록 테이블에 위치·변화 배지를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('하자 변화 목록')).not.toBeNull();
    expect(screen.getByText('외벽 A구간 / 균열')).not.toBeNull();
    expect(screen.getByText('악화')).not.toBeNull();
    expect(screen.getByText('신규')).not.toBeNull();
    expect(screen.getByText('유지')).not.toBeNull();
    expect(screen.getByText('조치완료')).not.toBeNull();
  });

  it('"내보내기" 클릭 시 메인 콘텐츠 영역을 대상으로 PNG 내보내기를 호출한다', async () => {
    renderPage();
    await screen.findByText('회차 간 비교');

    fireEvent.click(screen.getByRole('button', { name: '내보내기' }));

    expect(exportComparisonReportAsPngMock).toHaveBeenCalledTimes(1);
    expect(exportComparisonReportAsPngMock.mock.calls[0][1]).toBe('1');
  });

  // 회귀 고정 — 실 백엔드(HAJA-531/#1112)는 beforeImageUrl/afterImageUrl/crackTrend를 응답에서
  // 아예 생략한다(null/undefined). 이 필드들을 필수로 선언했던 예전 타입 그대로 두면
  // CrackTrendChart가 data.length에서 크래시하고 <img>는 깨진 아이콘만 보였다.
  it('백엔드가 이미지/균열추이 필드를 생략해도(실 API 응답 형태) 크래시 없이 플레이스홀더를 보여준다', async () => {
    server.use(
      http.get('/api/facilities/:id/compare', () => {
        const body: ApiResponse<Omit<InspectionComparisonResult, 'beforeImageUrl' | 'afterImageUrl' | 'crackTrend'>> = {
          success: true,
          data: {
            facilityId: mockInspectionComparison.facilityId,
            facilityName: mockInspectionComparison.facilityName,
            beforeCycle: mockInspectionComparison.beforeCycle,
            afterCycle: mockInspectionComparison.afterCycle,
            kpis: mockInspectionComparison.kpis,
            changes: mockInspectionComparison.changes,
            availableCycles: mockInspectionComparison.availableCycles,
          },
        };
        return HttpResponse.json(body);
      }),
    );

    renderPage();

    expect(await screen.findByText('회차 간 비교')).not.toBeNull();
    expect(await screen.findAllByText('사진 없음')).toHaveLength(2);
    expect(await screen.findByText('표시할 데이터가 없습니다.')).not.toBeNull();
  });
});