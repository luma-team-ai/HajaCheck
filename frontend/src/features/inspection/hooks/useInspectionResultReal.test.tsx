// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { DefectDetailItem, InspectionResponse, MediaResponse } from '../api/inspectionApi.types';
import type { FacilityDetail } from '../types';
import { resolveDefectImageUrl, useInspectionResultReal } from './useInspectionResultReal';

// PR머신 리뷰 P2(#789) — imageUrl: d.detailUrl ?? d.imageUrl ?? null 변경으로 뷰어에 실제 노출되는
// 이미지 URL의 우선순위가 바뀌었는데 이를 고정하는 테스트가 없었다. 우선순위가 반대로 바뀌거나
// detailUrl 처리가 깨져도 감지되지 않던 문제를 순수 함수로 분리해 직접 검증한다.
describe('resolveDefectImageUrl', () => {
  it('detailUrl이 있으면 detailUrl을 우선 사용한다', () => {
    expect(resolveDefectImageUrl('/api/media/1/thumbnail', '/api/media/1/detail')).toBe(
      '/api/media/1/detail',
    );
  });

  it('detailUrl이 없으면(null) imageUrl로 폴백한다', () => {
    expect(resolveDefectImageUrl('/api/media/1/thumbnail', null)).toBe('/api/media/1/thumbnail');
  });

  it('detailUrl이 없으면(undefined) imageUrl로 폴백한다', () => {
    expect(resolveDefectImageUrl('/api/media/1/thumbnail', undefined)).toBe(
      '/api/media/1/thumbnail',
    );
  });

  it('둘 다 없으면 null을 반환한다(수동 추가 하자 등 mediaId 없는 경우)', () => {
    expect(resolveDefectImageUrl(null, null)).toBeNull();
    expect(resolveDefectImageUrl(undefined, undefined)).toBeNull();
  });
});

// #1643 — 등급수정 API가 세우는 isReviewed(reviewed=true)가 화면(ResultViewerPage)의 "이 하자
// 검수 확정" 버튼 비활성화 판단에 쓰이므로, 훅의 변환 단계에서 유실되지 않는지 직접 검증한다.
// reviewedCount 집계(defectsData 원본의 d.isReviewed 필터)는 이 변경과 무관하게 그대로다.
describe('useInspectionResultReal — isReviewed 변환', () => {
  const server = setupServer();

  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  function renderInspectionResultHook(inspectionId: number) {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    return renderHook(() => useInspectionResultReal(inspectionId), { wrapper });
  }

  it('DefectDetailItem.isReviewed를 Defect.isReviewed로 그대로 전달한다', async () => {
    const inspection: InspectionResponse = {
      id: 1,
      facilityId: 1,
      createdBy: 1,
      assignedInspectorId: 1,
      roundNo: 1,
      inspectionDate: '2026-08-18',
      status: 'ANALYZED',
      createdAt: '2026-08-18T00:00:00Z',
    };
    const defects: DefectDetailItem[] = [
      {
        id: 1,
        inspectionId: 1,
        type: 'CRACK',
        grade: 'C',
        status: 'DETECTED',
        confidence: 0.9,
        isReviewed: true, // 등급수정으로 이미 reviewed=true가 된 경우(status는 DETECTED 그대로)
        bboxX: 0.1,
        bboxY: 0.1,
        bboxW: 0.1,
        bboxH: 0.1,
        createdAt: '2026-08-18T00:00:00Z',
      },
      {
        id: 2,
        inspectionId: 1,
        type: 'SPALLING',
        grade: null,
        status: 'DETECTED',
        confidence: 0.5,
        isReviewed: false,
        bboxX: null,
        bboxY: null,
        bboxW: null,
        bboxH: null,
        createdAt: '2026-08-18T00:00:00Z',
      },
    ];
    const media: MediaResponse[] = [];
    const facility: FacilityDetail = {
      id: 1,
      name: '테스트 시설물',
      type: '건물',
      address: null,
      builtYear: null,
      scale: null,
      nextInspectionDueAt: null,
    };

    server.use(
      http.get('/api/inspections/:id', () =>
        HttpResponse.json({ success: true, data: inspection } satisfies ApiResponse<InspectionResponse>),
      ),
      http.get('/api/inspections/:id/defects', () =>
        HttpResponse.json({ success: true, data: defects } satisfies ApiResponse<DefectDetailItem[]>),
      ),
      http.get('/api/inspections/:id/defects/deleted', () =>
        HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>),
      ),
      http.get('/api/inspections/:id/media', () =>
        HttpResponse.json({ success: true, data: media } satisfies ApiResponse<MediaResponse[]>),
      ),
      http.get('/api/facilities/:id', () =>
        HttpResponse.json({ success: true, data: facility } satisfies ApiResponse<FacilityDetail>),
      ),
    );

    const { result } = renderInspectionResultHook(1);

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    const transformed = result.current.data?.defects;
    expect(transformed?.find((d) => d.id === 1)?.isReviewed).toBe(true);
    expect(transformed?.find((d) => d.id === 2)?.isReviewed).toBe(false);
  });
});
