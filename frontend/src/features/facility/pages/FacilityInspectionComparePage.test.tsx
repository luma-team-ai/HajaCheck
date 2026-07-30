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
  // 2026-07-30 사용자 결정 — "이전 회차"는 서버가 자동으로 고른 값을 읽기 전용으로만
  // 보여주고, "현재 회차"만 사용자가 다른 회차로 바꿔볼 수 있는 드롭다운으로 유지한다.
  it('이전 회차는 읽기 전용, 현재 회차는 선택 가능한 드롭다운으로 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('회차 간 비교')).not.toBeNull();
    // code-reviewer P2 픽스 — aria-label 대신 sr-only 라벨 텍스트 뒤에 실제 값을 두므로,
    // sr-only 라벨의 부모 요소 전체 텍스트로 라벨·값이 함께 있는지 확인한다.
    expect(screen.getByText('이전 회차:', { exact: false }).parentElement?.textContent).toContain(
      `${mockInspectionComparison.beforeCycle.cycle}회차`,
    );

    const afterSelect = screen.getByLabelText('현재 회차');
    expect(afterSelect.tagName).toBe('SELECT');
    expect((afterSelect as HTMLSelectElement).value).toBe(String(mockInspectionComparison.afterCycle.cycle));
    // 편집 가능한 폼 컨트롤은 "현재 회차" 하나뿐이어야 한다("이전 회차"엔 없음).
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
  });

  // PR머신 P2 회귀고정(#1275) — "이전 회차"보다 이르거나 같은 회차를 "현재 회차"로 고르면
  // before>=after가 되어 서버가 400 INVALID_INPUT을 던진다. 선택지 자체에서 그런 회차가
  // 빠져 있어야 한다(목 데이터: beforeCycle=7, availableCycles=[5,6,7,8,9] → 8·9만 남아야 함).
  it('"현재 회차" 선택지에 이전 회차 이하 값이 포함되지 않는다(#1275)', async () => {
    renderPage();
    await screen.findByText('회차 간 비교');

    const afterSelect = screen.getByLabelText('현재 회차') as HTMLSelectElement;
    const optionValues = Array.from(afterSelect.options).map((option) => Number(option.value));

    expect(optionValues.every((cycle) => cycle > mockInspectionComparison.beforeCycle.cycle)).toBe(true);
    expect(optionValues).not.toContain(mockInspectionComparison.beforeCycle.cycle);
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

  // 회귀고정 — "현재 회차"만 바꾸면 beforeCycle 상태가 undefined로 남아, 재요청이 after만
  // 보내고 before는 생략된다. 서버는 이걸 "둘 다 생략"으로 오인해 방금 고른 after값까지
  // 자동 대체로 덮어쓴다("이전 회차"는 UI에 표시된 값과 실제 데이터가 어긋나게 된다).
  // "현재 회차"를 바꿀 때 "이전 회차" 표시값도 함께 실려야 한다.
  it('현재 회차를 다시 선택해도 이전 회차 값을 함께 실어 재요청한다(#1157)', async () => {
    const capturedSearches: string[] = [];
    const captureRequest = ({ request }: { request: Request }) => {
      capturedSearches.push(new URL(request.url).search);
    };
    server.events.on('request:match', captureRequest);

    try {
      renderPage();
      await screen.findByText('회차 간 비교');

      // #1275 필터로 이전 회차(7) 이하는 선택지에서 빠지므로, 유효한 대안 회차(9)를 고른다.
      fireEvent.change(screen.getByLabelText('현재 회차'), { target: { value: '9' } });

      await screen.findByText('회차 간 비교');
      const lastSearch = capturedSearches[capturedSearches.length - 1];
      expect(lastSearch).toContain('after=9');
      expect(lastSearch).toContain('before=');
      expect(lastSearch).not.toBe('');
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