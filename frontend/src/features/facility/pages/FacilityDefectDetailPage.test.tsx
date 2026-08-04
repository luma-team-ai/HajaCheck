// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityComparisonHandlers } from '../api/facilityComparisonApi.handlers';
import { facilityDefectHandlers } from '../api/facilityDefectApi.handlers';
import { facilityHandlers } from '../api/facilityApi.handlers';
import { FacilityDefectDetailPage } from './FacilityDefectDetailPage';

// #1350 — 페이지가 useFacility(facilityId)로 AI 설명용 facilityType을 조회하므로
// GET /api/facilities/:id를 목하는 facilityHandlers도 함께 등록해야 한다.
const server = setupServer(...facilityDefectHandlers, ...facilityComparisonHandlers, ...facilityHandlers);

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

  it('기본 선택 탭은 "오버레이"이고 이미지 위에 실 bbox 위치의 마킹 박스가 함께 렌더링된다(#1369)', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    expect(screen.getByRole('tab', { name: '오버레이' }).getAttribute('aria-selected')).toBe(
      'true',
    );
    expect(screen.getByRole('img', { name: '균열 하자 이미지' })).not.toBeNull();
    // #1369 — 이전엔 좌표를 무시한 고정 SVG였다. 이제 목데이터의 bboxX/Y/W/H가 실제로 style에
    // 반영되는지까지 확인해, 값이 하드코딩된 자리표시자로 되돌아가는 회귀를 잡는다.
    const markingBox = screen.getByLabelText('AI 감지 영역');
    expect(markingBox).not.toBeNull();
    expect(markingBox.style.left).toBe('42%');
    expect(markingBox.style.top).toBe('10%');
    expect(markingBox.style.width).toBe('8%');
    expect(markingBox.style.height).toBe('75%');
  });

  it('"원본" 탭 클릭 시 원본으로 전환되고 마킹 박스가 사라진다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('tab', { name: '원본' }));

    expect(screen.getByRole('tab', { name: '원본' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '오버레이' }).getAttribute('aria-selected')).toBe(
      'false',
    );
    expect(screen.queryByLabelText('AI 감지 영역')).toBeNull();
  });

  it('bbox 좌표가 없는(null) 하자는 "오버레이" 탭에서도 마킹 박스를 렌더하지 않는다(#1369)', async () => {
    server.use(
      http.get('/api/defects/:id', () =>
        HttpResponse.json({
          success: true,
          data: {
            id: 101,
            inspectionId: 8,
            facilityId: 1,
            facilityName: '강남 오피스타워 A동',
            location: '외벽 동측 12층 부근',
            assigneeName: '김검수',
            foundCycle: 8,
            typeLabel: '균열',
            grade: 'E',
            status: 'CONFIRMED',
            confidence: 0.94,
            crackWidthMm: 0.8,
            crackLengthMm: 2400,
            imageUrl: null,
            bboxX: null,
            bboxY: null,
            bboxW: null,
            bboxH: null,
            createdAt: '2026-06-21T09:00:00.000Z',
          },
        }),
      ),
    );

    renderPage();
    await screen.findByText('하자 상세');

    expect(screen.queryByLabelText('AI 감지 영역')).toBeNull();
  });

  it('AI 설명 패널은 로딩 후 진단·권장조치 텍스트를 표시한다', async () => {
    renderPage();

    expect(await screen.findByText(/구조적 스트레스로 인한 진행성 균열/)).not.toBeNull();
  });

  // code-reviewer P2(PR #1364) — 시설물 상세 조회(useFacility)가 실패해도 AI 설명 조회 자체가
  // 영구히 막히지 않고(facilityType 자리표시자로 계속 시도) 콘텐츠를 표시해야 한다. 이전엔
  // facilityType이 끝내 비어있으면 enabled가 계속 false로 남아 패널이 조용히 빈 화면이었다.
  it('시설물 조회(GET /api/facilities/:id)가 실패해도 AI 설명 패널은 빈 화면이 아니라 정상 표시된다', async () => {
    server.use(
      http.get('/api/facilities/:id', () => new HttpResponse(null, { status: 500 })),
    );

    renderPage();

    expect(await screen.findByText(/구조적 스트레스로 인한 진행성 균열/)).not.toBeNull();
  });

  it('활동 기록을 GET /api/defects/{id}/revisions 결과로 렌더링한다(#1351)', async () => {
    renderPage();

    expect(await screen.findByText("하자 등급을 'D'에서 'E'(으)로 변경했습니다.")).not.toBeNull();
    expect(screen.getByText("상태를 '신규'에서 '검수확정'(으)로 변경했습니다.")).not.toBeNull();
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
