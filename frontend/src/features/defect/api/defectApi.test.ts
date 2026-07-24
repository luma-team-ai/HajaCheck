// @vitest-environment jsdom
// aiClient는 baseURL='/api/ai'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { mockDefects } from '../mocks/defect.mock';
import { defectApi } from './defectApi';
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

    // mockDefects: inspectionId=101 → id 1(grade D), id 2(grade C) 2건.
    expect(inspection101?.defectCount).toBe(2);
    expect(inspection101?.gradeDistribution).toMatchObject({ C: 1, D: 1 });
  });

  it('status 필터를 적용하면 해당 상태의 점검만 반환한다', async () => {
    const res = await defectApi.getInspections({ status: 'REPORTED' });

    expect(res.data.content.every((inspection) => inspection.status === 'REPORTED')).toBe(true);
  });
});

describe('defectApi.getByInspection', () => {
  it('점검에 속한 하자 목록을 반환한다', async () => {
    const res = await defectApi.getByInspection(101);

    expect(res.data.map((defect) => defect.id).sort()).toEqual([1, 2]);
  });

  it('존재하지 않는 점검 id는 INSPECTION_NOT_FOUND 에러로 reject된다', async () => {
    await expect(defectApi.getByInspection(999999)).rejects.toMatchObject({
      code: 'INSPECTION_NOT_FOUND',
    });
  });
});

describe('defectApi.listFacilityOptions', () => {
  it('점검 목록 필터용 시설물 옵션을 반환한다', async () => {
    const res = await defectApi.listFacilityOptions();

    expect(res.data.length).toBeGreaterThan(0);
  });
});

describe('defectApi.submitAction', () => {
  it('조치 결과 등록 필드와 함께 요청하면 상태가 RESOLVED로 바뀌고 actionResult가 채워진다', async () => {
    const res = await defectApi.submitAction(2, {
      status: 'RESOLVED',
      actionContent: '균열 부위 보수 완료',
      actionDate: '2026-07-20',
      assigneeId: 101,
      afterMediaId: 9001,
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
