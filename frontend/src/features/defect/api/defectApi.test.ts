// @vitest-environment jsdom
// aiClient는 baseURL='/api/ai'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import { mockDefects, mockInspectionDefectResponses } from '../mocks/defect.mock';
import { mockInspections } from '../mocks/inspection.mock';
import type { InspectionDefect, InspectionDefectResponse, InspectionListItem } from '../types';
import {
  defectApi,
  fetchAllFilteredInspections,
  fetchFilteredDefectsForExport,
} from './defectApi';
import { defectHandlers } from './defectApi.handlers';

const mockDefectExplain = {
  cause: '바닥재 수분 침투 및 시간 경과에 따른 자연 박리',
  risk: '낙상 위험, 보행 불편',
  action: '바닥재 전체 교체 필요',
};

const handlers = [
  http.post('/api/ai/defect-explain', async ({ request }) => {
    const body = (await request.json()) as {
      defect_type: string;
      severity_grade: string;
      location: string;
      facility_type: string;
    };

    if (
      body.defect_type &&
      body.severity_grade &&
      body.location &&
      body.facility_type
    ) {
      const success: ApiResponse<typeof mockDefectExplain> = {
        success: true,
        data: mockDefectExplain,
      };
      return HttpResponse.json(success);
    }

    const failure: ApiResponse<null> = {
      success: false,
      data: null,
      error: {
        code: 'LLM_INVALID_INPUT',
        message: '필수 파라미터가 누락되었습니다.',
      },
    };
    return HttpResponse.json(failure, { status: 400 });
  }),
];

const server = setupServer(...handlers, ...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('defectApi.getExplanation', () => {
  it('유효한 파라미터로 요청하면 하자 설명을 반환한다', async () => {
    const res = await defectApi.getExplanation({
      defect_type: '바닥재 박리',
      severity_grade: 'HIGH',
      location: '1층 복도',
      facility_type: '사무실',
    });

    expect(res.data).toMatchObject({
      cause: expect.any(String),
      risk: expect.any(String),
      action: expect.any(String),
    });
    expect(res.data).toEqual(mockDefectExplain);
  });

  it('필수 파라미터가 누락되면 LLM_INVALID_INPUT 에러로 reject된다', async () => {
    await expect(
      defectApi.getExplanation({
        defect_type: '',
        severity_grade: 'HIGH',
        location: '1층 복도',
        facility_type: '사무실',
      }),
    ).rejects.toMatchObject({
      code: 'LLM_INVALID_INPUT',
    });
  });

  it('서버 에러 응답을 처리한다', async () => {
    server.use(
      http.post('/api/ai/defect-explain', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: {
            code: 'LLM_PROCESSING_ERROR',
            message: 'AI 분석 중 오류가 발생했습니다.',
          },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    await expect(
      defectApi.getExplanation({
        defect_type: '바닥재 박리',
        severity_grade: 'HIGH',
        location: '1층 복도',
        facility_type: '사무실',
      }),
    ).rejects.toMatchObject({
      code: 'LLM_PROCESSING_ERROR',
    });
  });

  it('네트워크 에러를 처리한다', async () => {
    server.use(
      http.post('/api/ai/defect-explain', () => {
        return HttpResponse.error();
      }),
    );

    await expect(
      defectApi.getExplanation({
        defect_type: '바닥재 박리',
        severity_grade: 'HIGH',
        location: '1층 복도',
        facility_type: '사무실',
      }),
    ).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    });
  });
});

describe('defectApi.getList', () => {
  it('필터 없이 요청하면 전체 하자 목록을 페이지 응답으로 반환한다', async () => {
    const res = await defectApi.getList();

    expect(res.data.content).toHaveLength(mockDefects.length);
    expect(res.data.totalElements).toBe(mockDefects.length);
  });

  it('grade 필터를 적용하면 해당 등급만 반환한다', async () => {
    const res = await defectApi.getList({ grade: 'D' });

    expect(res.data.content).toHaveLength(1);
    expect(res.data.content[0].grade).toBe('D');
  });

  it('type 필터를 적용하면 해당 유형만 반환한다', async () => {
    const res = await defectApi.getList({ type: 'CRACK' });

    expect(res.data.content.every((defect) => defect.type === 'CRACK')).toBe(true);
  });
});

describe('defectApi.getDetail', () => {
  it('존재하는 id로 요청하면 하자 상세를 반환한다', async () => {
    const res = await defectApi.getDetail(1);

    expect(res.data.id).toBe(1);
    expect(res.data.facilityName).toBe('강남 오피스타워 A동');
  });

  it('존재하지 않는 id로 요청하면 DEFECT_NOT_FOUND 에러로 reject된다', async () => {
    await expect(defectApi.getDetail(999999)).rejects.toMatchObject({
      code: 'DEFECT_NOT_FOUND',
    });
  });
});

// --- 하자 목록·상세 개편 (HAJA-393/394, #725/#726) ---------------------------------------

describe('defectApi.getInspections', () => {
  it('필터 없이 요청하면 점검 단위 목록을 페이지 응답으로 반환한다', async () => {
    const res = await defectApi.getInspections();

    expect(res.data.content.length).toBeGreaterThan(0);
    expect(res.data.content.map((inspection) => inspection.id)).toContain(101);
  });

  it('점검별 하자 건수·등급분포를 mockDefects 기준으로 집계해 반환한다', async () => {
    const res = await defectApi.getInspections();
    const inspection101 = res.data.content.find((item) => item.id === 101);

    // 점검별 raw fixture: inspectionId=101 → id 1(D), id 2(C), id 4(B) 3건.
    expect(inspection101?.defectCount).toBe(3);
    expect(inspection101?.gradeDistribution).toMatchObject({ B: 1, C: 1, D: 1 });
  });

  it('inspectionStatus 필터를 적용하면 해당 상태의 점검만 반환한다', async () => {
    const res = await defectApi.getInspections({ inspectionStatus: ['REPORTED'] });

    expect(res.data.content.every((inspection) => inspection.status === 'REPORTED')).toBe(true);
  });

  it('점검 유형·날짜·회차·전체 하자 건수 조건을 AND로 적용한다', async () => {
    const res = await defectApi.getInspections({
      inspectionType: ['DETAILED'],
      inspectionStatus: ['ANALYZED', 'REVIEWED'],
      inspectionDateFrom: '2026-05-28',
      inspectionDateTo: '2026-07-28',
      roundNoMin: 1,
      roundNoMax: 1,
      defectCountMin: 1,
      defectCountMax: 1,
    });

    expect(res.data.content.map((inspection) => inspection.id)).toEqual([202]);
  });

  it('defectType/defectGrade/defectStatus 조건을 모두 만족하는 단일 하자가 있는 점검만 반환한다', async () => {
    // mockDefects id=1: inspectionId=101, type=REBAR_EXPOSURE, grade=D, status=CONFIRMED —
    // 세 조건을 전부 동시에 만족. id=2(같은 inspectionId=101)는 type=CRACK이라 조건 불일치.
    const res = await defectApi.getInspections({
      defectType: ['REBAR_EXPOSURE'],
      defectGrade: ['D'],
      defectStatus: ['CONFIRMED'],
    });

    expect(res.data.content.map((inspection) => inspection.id)).toEqual([101]);
  });

  it('서로 다른 하자가 조건을 나눠 만족하면 매칭하지 않는다', async () => {
    // inspectionId=101에는 grade=D(id 1)와 type=CRACK(id 2)이 있지만, 같은 하자 하나가 두 조건을
    // 동시에 만족하지는 않는다 — 백엔드 EXISTS 서브쿼리 의미와 동일하게 매칭되지 않아야 한다.
    const res = await defectApi.getInspections({
      defectType: ['CRACK'],
      defectGrade: ['D'],
    });

    expect(res.data.content.map((inspection) => inspection.id)).not.toContain(101);
  });

  // axios 배열 파라미터 직렬화(#726/HAJA-394) — Spring `@RequestParam List<T>`는 대괄호 없는 반복
  // 키(`defectType=A&defectType=B`)를 기대하므로, axios 기본 직렬화가 `defectType[]=A` 형태를 만들지
  // 않는지 실제 요청 URL을 MSW로 가로채 검증한다(가정이 아니라 실측).
  it('배열 필터를 대괄호 없는 반복 키(key=v1&key=v2)로 직렬화해 요청한다', async () => {
    let capturedUrl = '';
    server.use(
      http.get('/api/inspections', ({ request }) => {
        capturedUrl = request.url;
        const body: ApiResponse<PageResponse<InspectionListItem>> = {
          success: true,
          data: { content: [], page: 0, totalElements: 0 },
        };
        return HttpResponse.json(body);
      }),
    );

    await defectApi.getInspections({
      inspectionType: ['REGULAR', 'DETAILED'],
      inspectionStatus: ['ANALYZED', 'REVIEWED'],
      defectType: ['CRACK', 'SPALLING'],
      defectGrade: ['D'],
      inspectionDateFrom: '2026-05-28',
      inspectionDateTo: '2026-07-28',
      roundNoMin: 1,
      roundNoMax: 3,
      defectCountMin: 1,
      defectCountMax: 5,
    });

    const queryString = capturedUrl.split('?')[1] ?? '';
    expect(queryString).toContain('inspectionType=REGULAR');
    expect(queryString).toContain('inspectionType=DETAILED');
    expect(queryString).toContain('status=ANALYZED');
    expect(queryString).toContain('status=REVIEWED');
    expect(queryString).toContain('defectType=CRACK');
    expect(queryString).toContain('defectType=SPALLING');
    expect(queryString).toContain('defectGrade=D');
    // 대괄호가 인코딩되어(%5B%5D) 붙거나 리터럴로 붙는 어느 경우도 없어야 한다.
    expect(queryString).not.toMatch(/defectType(%5B%5D|\[\])/);
    expect(queryString).not.toMatch(/defectGrade(%5B%5D|\[\])/);
    expect(queryString).not.toMatch(/inspectionType(%5B%5D|\[\])/);
    expect(queryString).not.toMatch(/status(%5B%5D|\[\])/);
    expect(queryString).toContain('inspectionDateFrom=2026-05-28');
    expect(queryString).toContain('inspectionDateTo=2026-07-28');
    expect(queryString).toContain('roundNoMin=1');
    expect(queryString).toContain('roundNoMax=3');
    expect(queryString).toContain('defectCountMin=1');
    expect(queryString).toContain('defectCountMax=5');
  });

  it('빈 배열과 null 성격의 값은 쿼리에서 제외한다', async () => {
    let capturedUrl = '';
    server.use(
      http.get('/api/inspections', ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json({
          success: true,
          data: { content: [], page: 0, totalElements: 0 },
        });
      }),
    );

    await defectApi.getInspections({
      inspectionType: [],
      inspectionStatus: [],
      defectType: [],
      inspectionDateFrom: '',
      page: 0,
      size: 10,
    });

    const queryString = capturedUrl.split('?')[1] ?? '';
    expect(queryString).not.toContain('inspectionType');
    expect(queryString).not.toContain('status=');
    expect(queryString).not.toContain('defectType');
    expect(queryString).not.toContain('inspectionDateFrom');
    expect(queryString).toContain('page=0');
    expect(queryString).toContain('size=10');
  });
});

describe('defectApi.getByInspection', () => {
  it('점검에 속한 하자 목록을 반환한다', async () => {
    const res = await defectApi.getByInspection(101);
    const defects: InspectionDefect[] = res.data;

    expect(defects.map((defect) => defect.id).sort()).toEqual([1, 2, 4]);
    expect(defects[0].reviewed).toBe(true);
    expect('isReviewed' in defects[0]).toBe(false);
    expect('facilityName' in defects[0]).toBe(false);
    expect(defects[0].detailUrl).toBe('/api/media/901/detail');
  });

  it('존재하지 않는 점검 id는 INSPECTION_NOT_FOUND 에러로 reject된다', async () => {
    await expect(defectApi.getByInspection(999999)).rejects.toMatchObject({
      code: 'INSPECTION_NOT_FOUND',
    });
  });
});

describe('fetchAllFilteredInspections', () => {
  it('화면 페이지·크기를 무시하고 필터를 유지한 채 모든 페이지를 순회한다', async () => {
    const requests: Array<{ facilityId: string | null; page: string | null; size: string | null }> = [];
    server.use(
      http.get('/api/inspections', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page'));
        requests.push({
          facilityId: url.searchParams.get('facilityId'),
          page: url.searchParams.get('page'),
          size: url.searchParams.get('size'),
        });
        const content = page === 0 ? mockInspections.slice(0, 2) : mockInspections.slice(2);
        const body: ApiResponse<PageResponse<InspectionListItem>> = {
          success: true,
          data: {
            content,
            page,
            totalElements: mockInspections.length,
          },
        };
        return HttpResponse.json(body);
      }),
    );

    const result = await fetchAllFilteredInspections({
      facilityId: 1,
      page: 7,
      size: 1,
    });

    expect(requests).toEqual([
      { facilityId: '1', page: '0', size: '100' },
      { facilityId: '1', page: '1', size: '100' },
    ]);
    expect(result.map((inspection) => inspection.id)).toEqual(
      mockInspections.map((inspection) => inspection.id),
    );
  });

  it('5,000건을 초과해도 고정 페이지 상한 없이 totalElements까지 조회한다', async () => {
    const totalElements = 5_001;
    const requestedPages: number[] = [];
    server.use(
      http.get('/api/inspections', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page'));
        requestedPages.push(page);
        const start = page * 100;
        const content = Array.from(
          { length: Math.min(100, totalElements - start) },
          (_, index) => ({
            ...mockInspections[0],
            id: start + index + 1,
          }),
        );
        const body: ApiResponse<PageResponse<InspectionListItem>> = {
          success: true,
          data: { content, page, totalElements },
        };
        return HttpResponse.json(body);
      }),
    );

    const result = await fetchAllFilteredInspections({});

    expect(result).toHaveLength(totalElements);
    expect(result.at(-1)?.id).toBe(totalElements);
    expect(requestedPages).toHaveLength(51);
    expect(requestedPages.at(-1)).toBe(50);
  });

  it('전체 건수에 도달하기 전에 빈 페이지가 오면 부분 결과를 반환하지 않고 실패한다', async () => {
    server.use(
      http.get('/api/inspections', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'));
        const content = page === 0 ? mockInspections.slice(0, 2) : [];
        const body: ApiResponse<PageResponse<InspectionListItem>> = {
          success: true,
          data: { content, page, totalElements: 3 },
        };
        return HttpResponse.json(body);
      }),
    );

    await expect(fetchAllFilteredInspections({})).rejects.toThrow(
      '점검 내보내기 데이터를 끝까지 불러오지 못했습니다.',
    );
  });
});

describe('fetchFilteredDefectsForExport', () => {
  it('점검별 요청을 최대 5개만 병렬 실행하고 하자 조건을 PDF 행에도 적용한다', async () => {
    const inspections = Array.from({ length: 12 }, (_, index) => ({
      ...mockInspections[0],
      id: 1_000 + index,
    }));
    let inFlight = 0;
    let peakInFlight = 0;

    server.use(
      http.get('/api/inspections', () => {
        const body: ApiResponse<PageResponse<InspectionListItem>> = {
          success: true,
          data: {
            content: inspections,
            page: 0,
            totalElements: inspections.length,
          },
        };
        return HttpResponse.json(body);
      }),
      http.get('/api/inspections/:id/defects', async ({ params }) => {
        const inspectionId = Number(params.id);
        const source = mockInspectionDefectResponses[
          (inspectionId - 1_000) % mockInspectionDefectResponses.length
        ];
        inFlight += 1;
        peakInFlight = Math.max(peakInFlight, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 5));
        inFlight -= 1;

        const defect: InspectionDefectResponse = {
          ...source,
          id: inspectionId,
          inspectionId,
        };
        const body: ApiResponse<InspectionDefectResponse[]> = {
          success: true,
          data: [defect],
        };
        return HttpResponse.json(body);
      }),
    );

    const result = await fetchFilteredDefectsForExport({
      defectType: ['CRACK'],
      defectGrade: ['C'],
      defectStatus: ['DETECTED'],
    });

    expect(peakInFlight).toBeGreaterThan(1);
    expect(peakInFlight).toBeLessThanOrEqual(5);
    expect(result).toHaveLength(3);
    expect(
      result.every(
        (defect) =>
          defect.type === 'CRACK' &&
          defect.grade === 'C' &&
          defect.status === 'DETECTED',
      ),
    ).toBe(true);
    expect(result.every((defect) => defect.facilityName === '강남 오피스타워 A동')).toBe(true);
    expect(result.every((defect) => defect.facilityType === '건물')).toBe(true);
  });
});

describe('defectApi.listFacilityOptions', () => {
  it('점검 목록 필터용 시설물 옵션을 반환한다', async () => {
    const res = await defectApi.listFacilityOptions();

    expect(res.data.length).toBeGreaterThan(0);
  });
});

describe('defectApi.submitAction', () => {
  it('targetStatus=RESOLVED로 호출 시 상태가 RESOLVED로 바뀌고 actionResult가 채워진다', async () => {
    const res = await defectApi.submitAction(2, {
      actionContent: '균열 부위 보수 완료',
      actionDate: '2026-07-20',
      actionAssigneeId: 101,
      actionMediaId: 9001,
      targetStatus: 'RESOLVED',
    });

    expect(res.data.status).toBe('RESOLVED');
    expect(res.data.actionResult).toMatchObject({
      actionContent: '균열 부위 보수 완료',
      actionDate: '2026-07-20',
      assigneeId: 101,
      assigneeName: '김도현 검사자',
      afterPhotoUrl: '/api/media/9001/thumbnail',
    });
  });
});
