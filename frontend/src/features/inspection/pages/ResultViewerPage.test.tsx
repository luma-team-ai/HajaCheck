// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ApiResponse } from '../../../shared/api/types';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import type { DefectRevisionRequest } from '../api/inspectionApi';
import type { InspectionResponse, DefectDetailItem, DefectCreateRequest, MediaResponse } from '../api/inspectionApi.types';
import { ResultViewerPage } from './ResultViewerPage';

// 테스트용 목 데이터
const mockInspection: InspectionResponse = {
  id: 1,
  facilityId: 1,
  createdBy: 1,
  assignedInspectorId: 1,
  roundNo: 1,
  inspectionDate: '2026-07-22',
  status: 'ANALYZED',
  createdAt: '2026-07-22T10:00:00Z',
  reviewedCount: 1,
  totalCount: 5,
};

const mockDefects: DefectDetailItem[] = [
  {
    id: 1,
    inspectionId: 1,
    type: 'CRACK',
    grade: 'C',
    status: 'DETECTED',
    confidence: 0.98,
    isReviewed: false,
    bboxX: 0.12,
    bboxY: 0.3,
    bboxW: 0.18,
    bboxH: 0.08,
    crackWidthMm: 3.2,
    crackLengthMm: 45,
    createdAt: '2026-07-22T10:00:00Z',
    mediaId: 67,
    imageUrl: '/api/media/67/thumbnail',
  },
  {
    id: 2,
    inspectionId: 1,
    type: 'SPALLING',
    grade: 'B',
    status: 'DETECTED',
    confidence: 0.81,
    isReviewed: false,
    bboxX: 0.55,
    bboxY: 0.42,
    bboxW: 0.12,
    bboxH: 0.15,
    createdAt: '2026-07-22T10:00:00Z',
    mediaId: 67,
    imageUrl: '/api/media/67/thumbnail',
  },
  {
    id: 3,
    inspectionId: 1,
    type: 'REBAR_EXPOSURE',
    grade: 'D',
    status: 'CONFIRMED',
    confidence: 0.67,
    isReviewed: true,
    bboxX: 0.3,
    bboxY: 0.6,
    bboxW: 0.25,
    bboxH: 0.1,
    createdAt: '2026-07-22T10:00:00Z',
    mediaId: 67,
    imageUrl: '/api/media/67/thumbnail',
  },
  {
    id: 4,
    inspectionId: 1,
    type: 'REBAR_EXPOSURE',
    grade: 'E',
    status: 'DETECTED',
    confidence: 0.58,
    isReviewed: false,
    bboxX: 0.7,
    bboxY: 0.15,
    bboxW: 0.1,
    bboxH: 0.1,
    createdAt: '2026-07-22T10:00:00Z',
    mediaId: 67,
    imageUrl: '/api/media/67/thumbnail',
  },
  {
    id: 5,
    inspectionId: 1,
    type: 'SPALLING',
    grade: 'A',
    status: 'RESOLVED',
    confidence: 0.45,
    isReviewed: true,
    bboxX: 0.05,
    bboxY: 0.75,
    bboxW: 0.2,
    bboxH: 0.08,
    createdAt: '2026-07-22T10:00:00Z',
    mediaId: 67,
    imageUrl: '/api/media/67/thumbnail',
  },
];

// 새로운 API 엔드포인트 mock
const testHandlers = [
  ...inspectionHandlers,
  http.get('/api/inspections/:id', () => {
    const body: ApiResponse<InspectionResponse> = { success: true, data: mockInspection };
    return HttpResponse.json(body);
  }),
  http.get('/api/inspections/:id/defects', () => {
    const body: ApiResponse<DefectDetailItem[]> = { success: true, data: mockDefects };
    return HttpResponse.json(body);
  }),
  http.get('/api/inspections/:id/media', () => {
    const mockMedia: MediaResponse[] = [
      {
        id: 67,
        inspectionId: 1,
        fileType: 'IMAGE',
        thumbnailUrl: '/api/media/67/thumbnail',
        detailUrl: '/api/media/67/detail',
        mimeType: 'image/jpeg',
        capturedAt: '2026-07-22T10:00:00Z',
        gpsLat: null,
        gpsLng: null,
        createdAt: '2026-07-22T10:00:00Z',
      },
      {
        id: 68,
        inspectionId: 1,
        fileType: 'IMAGE',
        thumbnailUrl: '/api/media/68/thumbnail',
        detailUrl: '/api/media/68/detail',
        mimeType: 'image/jpeg',
        capturedAt: '2026-07-22T10:05:00Z',
        gpsLat: null,
        gpsLng: null,
        createdAt: '2026-07-22T10:05:00Z',
      },
    ];
    const body: ApiResponse<MediaResponse[]> = { success: true, data: mockMedia };
    return HttpResponse.json(body);
  }),
  http.post('/api/ai/defect-explain', () => {
    const body = {
      success: true,
      data: {
        cause: '콘크리트 표면의 환경 노출로 인한 수축 응력',
        risk: '방치 시 균열 진전으로 구조 안정성 악화',
        action: '우레탄 같은 유연한 충전재로 밀봉 권장',
      },
    };
    return HttpResponse.json(body);
  }),
  http.patch('/api/defects/:id', async ({ request }) => {
    const body = (await request.json()) as DefectRevisionRequest;
    // reason은 필수 필드 (1-500자)
    if (!body.reason || body.reason.trim().length === 0 || body.reason.trim().length > 500) {
      return HttpResponse.json(
        { success: false, error: { code: 'INVALID_INPUT', message: 'reason은 필수이고 1-500자여야 합니다.' } },
        { status: 400 },
      );
    }
    const updatedDefect: DefectDetailItem = mockDefects[0];
    return HttpResponse.json({ success: true, data: updatedDefect });
  }),
  http.patch('/api/defects/:id/status', () => {
    const updatedDefect: DefectDetailItem = {
      ...mockDefects[0],
      status: 'CONFIRMED',
    };
    return HttpResponse.json({ success: true, data: updatedDefect });
  }),
  http.post('/api/inspections/:id/defects', async ({ request }) => {
    const body = (await request.json()) as DefectCreateRequest;
    const newDefect: DefectDetailItem = {
      id: 999,
      inspectionId: 1,
      type: body.type,
      grade: body.grade,
      confidence: 1.0,
      status: 'DETECTED',
      isReviewed: false,
      bboxX: null,
      bboxY: null,
      bboxW: null,
      bboxH: null,
      createdAt: new Date().toISOString(),
    };
    return HttpResponse.json({ success: true, data: newDefect }, { status: 201 });
  }),
];

const server = setupServer(...testHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // vitest globals 미설정 환경이라 RTL 자동 cleanup이 안 걸림 — 명시 호출 필요
  cleanup();
});
afterAll(() => server.close());

function renderPage(path: string = '/inspections/1/viewer'): void {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/inspections/:id/viewer" element={<ResultViewerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ResultViewerPage (통합 테스트)', () => {
  it('정상 렌더: 점검 결과 데이터(결함코드, 시설물명)를 로드해 표시한다', async () => {
    renderPage();

    // mock 데이터에서 실제 값 확인 (defectCode는 이제 DEF-{id} 형식)
    expect(await screen.findByText('DEF-0001')).not.toBeNull();
    expect(await screen.findByText('강남 오피스타워 A동')).not.toBeNull();
  });

  it('좌측 이미지 컬럼은 넘치면 버튼이 클립되지 않고 스크롤된다(#902)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // 부모 Unified Card가 overflow-hidden이라, 이미지(max-h-[79vh], #897)가 낮은
    // 뷰포트에서 진행률바·검수확정 버튼과 합쳐 넘치면 버튼이 영구히 안 보이게 된다 —
    // 이 컬럼에 overflow-y-auto가 있어야 넘칠 때 스크롤로 항상 닿을 수 있다.
    const confirmButton = screen.getByRole('button', { name: '이 하자 검수 확정' });
    const leftColumn = confirmButton.closest('.overflow-y-auto');
    expect(leftColumn).not.toBeNull();
  });

  it('선택된 하자가 필터로 제외되면 목록의 첫 항목으로 자동 대체된다', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // id=2(박리박락, confidence 0.81)를 선택
    fireEvent.click(screen.getByTitle(/박리박락/));
    // AI 패널에서 AI response 확인
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();

    // 신뢰도 threshold를 0.9로 올려 id=2를 필터에서 제외
    fireEvent.change(screen.getByRole('slider'), { target: { value: '0.9' } });

    // 선택이 남아있는 첫 항목(id=1, 균열)으로 자동 대체된다 — AI 패널도 재렌더
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();
  });

  it('필터 결과가 0건이면(원본 데이터는 있음) 안내 메시지를 표시한다(#368)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // 신뢰도 threshold를 최대로 올려 모든 하자(최고 confidence 0.98)를 필터에서 제외
    fireEvent.change(screen.getByRole('slider'), { target: { value: '1' } });

    expect(await screen.findByText('조건에 맞는 하자가 없습니다.')).not.toBeNull();
  });

  it('"검수 확정" 버튼이 활성화되어 있고 클릭하면 상태를 변경한다(#566)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 하자 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(false);

    fireEvent.click(button);

    // 로딩 중 비활성화 확인 (짧은 시간이지만)
    expect(button.hasAttribute('disabled')).toBe(true);

    // API 호출 완료 후 버튼 다시 활성화 확인 (refetch 완료 후)
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('"검수 확정" 실패 시 에러 메시지를 표시한다(#566)', async () => {
    // 실패 응답을 반환하는 핸들러 설정
    server.use(
      http.patch('/api/defects/:id/status', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_STATUS_TRANSITION', message: '검수 실패' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 하자 검수 확정' });
    fireEvent.click(button);

    // 에러 메시지 표시 확인 (기본 에러 메시지가 표시됨)
    expect(await screen.findByText(/검수 확정에 실패했습니다/)).not.toBeNull();
  });

  it('status가 CONFIRMED면 "검수 확정" 버튼이 비활성화된다(#575)', async () => {
    // 첫 번째 defect를 CONFIRMED로 변경한 mock data
    const confirmedDefectsMock = [
      { ...mockDefects[0], status: 'CONFIRMED' as const }, // id=1을 CONFIRMED로
      ...mockDefects.slice(1),
    ] as DefectDetailItem[];

    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: confirmedDefectsMock };
        return HttpResponse.json(body);
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 하자 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(true);
  });

  it('status가 RESOLVED면 "검수 확정" 버튼이 비활성화된다(#575)', async () => {
    // 첫 번째 defect를 RESOLVED로 변경한 mock data
    const resolvedDefectsMock = [
      { ...mockDefects[0], status: 'RESOLVED' as const }, // id=1을 RESOLVED로
      ...mockDefects.slice(1),
    ] as DefectDetailItem[];

    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: resolvedDefectsMock };
        return HttpResponse.json(body);
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 하자 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(true);
  });

  it('이 이미지의 마지막 하자를 확정하면 다음 이미지로 자동 이동한다(#784)', async () => {
    // mediaId=67(이미지1)은 id=2(박리박락)만 DETECTED로 남기고 나머지는 이미 확정/해결 상태로,
    // mediaId=68(이미지2)에 별도 하자 하나를 추가한 2-이미지 시나리오.
    const twoImageDefectsMock: DefectDetailItem[] = [
      { ...mockDefects[0], status: 'CONFIRMED' },
      mockDefects[1], // id=2, status: 'DETECTED' — 이미지1의 마지막 미확정 하자
      { ...mockDefects[2], status: 'CONFIRMED' },
      { ...mockDefects[3], status: 'CONFIRMED' },
      { ...mockDefects[4], status: 'RESOLVED' },
      {
        id: 6,
        inspectionId: 1,
        type: 'LEAK_EFFLORESCENCE',
        grade: 'A',
        status: 'DETECTED',
        confidence: 0.7,
        isReviewed: false,
        bboxX: 0.4,
        bboxY: 0.4,
        bboxW: 0.1,
        bboxH: 0.1,
        createdAt: '2026-07-22T10:00:00Z',
        mediaId: 68,
        imageUrl: '/api/media/68/thumbnail',
      },
    ];
    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: twoImageDefectsMock };
        return HttpResponse.json(body);
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');
    expect(await screen.findByText('이미지 1/2')).not.toBeNull();

    // 이미지1의 유일한 DETECTED 하자(id=2, 박리박락)를 선택 후 확정
    fireEvent.click(screen.getByTitle(/박리박락/));
    fireEvent.click(screen.getByRole('button', { name: '이 하자 검수 확정' }));

    expect(await screen.findByText('이미지 2/2')).not.toBeNull();
  });

  it('하자 0건 이미지로 이동해도 이미지 자체는 렌더되고 문구만 같이 뜬다(#815)', async () => {
    // 기본 mock: mediaId=67에만 하자 5건, media 목록은 67·68 — 68은 하자 0건인 채로 그대로 사용.
    renderPage();
    await screen.findByText('DEF-0001');
    expect(await screen.findByText('이미지 1/2')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '다음 이미지 →' }));

    expect(await screen.findByText('이미지 2/2')).not.toBeNull();
    // 이미지 자체(DefectOverlay)는 계속 렌더되어야 한다 — 문구로 대체되면 안 됨.
    expect(screen.getByAltText('점검 이미지')).not.toBeNull();
    expect(screen.getByText('이 이미지에 해당하는 하자가 없습니다.')).not.toBeNull();
  });

  it('하자 0건 이미지에서도 누락추가 버튼이 계속 보인다(#874)', async () => {
    // 기본 mock: media 68은 하자 0건 — 선택된 하자(selected)가 없어도
    // 우측 패널·누락추가 버튼이 통째로 사라지면 안 된다(#874 회귀 버그).
    renderPage();
    await screen.findByText('DEF-0001');
    fireEvent.click(screen.getByRole('button', { name: '다음 이미지 →' }));
    await screen.findByText('이 이미지에 해당하는 하자가 없습니다.');

    const button = screen.getByRole('button', { name: '누락 추가' });
    expect(button.hasAttribute('disabled')).toBe(false);
    expect(screen.getByText('선택된 하자가 없습니다.')).not.toBeNull();
  });

  it('오탐 삭제 버튼이 활성화되어 있다(#553)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '오탐 삭제' });
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('등급 수정 버튼이 활성화되어 있다(#553)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '등급 수정' });
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('하자 마커 클릭 → AI 패널 요약 텍스트가 선택된 하자로 갱신된다', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // 초기 상태: id=1(균열)의 AI 설명 표시
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();

    // id=2(박리박락) 마커 클릭
    const secondDefectButton = screen.getByTitle(/박리박락/);
    fireEvent.click(secondDefectButton);

    // AI 패널의 설명이 id=2로 갱신됨 (같은 mock 응답 재사용)
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();
  });

  it('균열(CRACK) 하자는 면적 비율이 아니라 예상 길이(mm)를 표시한다(#881)', async () => {
    // 백엔드는 type을 영문 코드로 내려주므로(#881), 훅에서 한글로 번역돼야만
    // '균열' 분기(예상 길이)를 탄다. id=1은 CRACK·crackLengthMm=45.
    renderPage();
    await screen.findByText('DEF-0001');

    expect(screen.getByText('예상 길이')).not.toBeNull();
    expect(screen.getByText('45mm')).not.toBeNull();
    expect(screen.queryByText('면적 비율')).toBeNull();
  });

  it('박리박락(SPALLING) 하자는 면적 비율을 표시한다(#881)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // id=2(박리박락) 마커 클릭 — areaRatio 미제공이라 '준비 중'으로 표시된다.
    fireEvent.click(screen.getByTitle(/박리박락/));

    expect(screen.getByText('면적 비율')).not.toBeNull();
    expect(screen.getByText('준비 중')).not.toBeNull();
    expect(screen.queryByText('예상 길이')).toBeNull();
  });

  it('빈 데이터: 탐지된 하자가 없으면 해당 메시지를 표시한다', async () => {
    // 빈 defects 배열 응답으로 오버라이드
    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const emptyResult: ApiResponse<DefectDetailItem[]> = { success: true, data: [] };
        return HttpResponse.json(emptyResult);
      }),
    );

    renderPage();
    expect(await screen.findByText('탐지된 하자가 없습니다.')).not.toBeNull();
  });

  it('"누락 추가" 버튼을 클릭하면 메인 뷰어 위 그리기 모드로 전환된다 (#874, 2안)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '누락 추가' });
    expect(button.hasAttribute('disabled')).toBe(false);

    fireEvent.click(button);

    expect(await screen.findByText('이미지 위에 드래그해서 하자 위치를 표시하세요.')).not.toBeNull();
    expect(screen.queryByText('누락된 하자 추가')).toBeNull();
  });

  it('그리기 모드에서 "박스 없이 계속"을 누르면 유형/등급 선택 모달이 열린다 (#874)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    await screen.findByText('이미지 위에 드래그해서 하자 위치를 표시하세요.');
    fireEvent.click(screen.getByRole('button', { name: '박스 없이 계속' }));

    expect(await screen.findByText('누락된 하자 추가')).not.toBeNull();
    expect(screen.getByText(/하자 위치가 지정되지 않았습니다/)).not.toBeNull();
    expect(screen.getByDisplayValue('유형 선택')).not.toBeNull();
    expect(screen.getByDisplayValue('등급 선택')).not.toBeNull();
  });

  it('그리기 모드에서 "취소"를 누르면 모달 없이 원래 화면으로 돌아간다 (#874)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    await screen.findByText('이미지 위에 드래그해서 하자 위치를 표시하세요.');
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByText('이미지 위에 드래그해서 하자 위치를 표시하세요.')).toBeNull();
    expect(screen.queryByText('누락된 하자 추가')).toBeNull();
  });

  it('모달에서 유형과 등급을 선택하지 않으면 저장 버튼이 비활성화된다 (#622)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    expect(saveButton?.hasAttribute('disabled')).toBe(true);
  });

  it('모달에서 유형만 선택하면 저장 버튼이 비활성화된다 (#622)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const typeSelect = screen.getAllByDisplayValue('유형 선택')[0];
    fireEvent.change(typeSelect, { target: { value: 'CRACK' } });

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    expect(saveButton?.hasAttribute('disabled')).toBe(true);
  });

  it('모달에서 유형과 등급을 선택하면 저장 버튼이 활성화된다 (#622)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const selects = screen.getAllByDisplayValue(/유형 선택|등급 선택/);
    fireEvent.change(selects[0], { target: { value: 'CRACK' } });
    fireEvent.change(selects[1], { target: { value: 'A' } });

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    expect(saveButton?.hasAttribute('disabled')).toBe(false);
  });

  it('모달에서 저장하면 POST 요청을 보내고 모달이 닫힌다 (#622)', async () => {
    let postCalled = false;
    server.use(
      http.post('/api/inspections/:id/defects', async ({ request }) => {
        postCalled = true;
        const body = (await request.json()) as DefectCreateRequest;
        expect(body.type).toBe('SPALLING');
        expect(body.grade).toBe('B');
        const newDefect: DefectDetailItem = {
          id: 999,
          inspectionId: 1,
          type: body.type,
          grade: body.grade,
          confidence: 1.0,
          status: 'DETECTED',
          isReviewed: false,
          bboxX: null,
          bboxY: null,
          bboxW: null,
          bboxH: null,
          createdAt: new Date().toISOString(),
        };
        return HttpResponse.json({ success: true, data: newDefect }, { status: 201 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const selects = screen.getAllByDisplayValue(/유형 선택|등급 선택/);
    fireEvent.change(selects[0], { target: { value: 'SPALLING' } });
    fireEvent.change(selects[1], { target: { value: 'B' } });

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    fireEvent.click(saveButton!);

    // POST 요청이 완료되고 모달이 닫혀야 한다
    await waitFor(() => {
      expect(postCalled).toBe(true);
      expect(screen.queryByText('누락된 하자 추가')).toBeNull();
    });
  });

  it('모달에서 취소하면 API 호출 없이 모달이 닫힌다 (#622)', async () => {
    let postCalled = false;
    server.use(
      http.post('/api/inspections/:id/defects', async () => {
        postCalled = true;
        return HttpResponse.json({ success: false }, { status: 500 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const cancelButton = screen.getAllByRole('button', { name: '취소' }).pop();
    fireEvent.click(cancelButton!);

    // 모달이 닫혀야 하므로 제목이 더 이상 보이지 않아야 한다
    expect(screen.queryByText('누락된 하자 추가')).toBeNull();

    // POST 호출이 없어야 한다
    expect(postCalled).toBe(false);
  });

  it('모달에서 저장 실패 시 에러 메시지를 표시한다 (#622)', async () => {
    server.use(
      http.post('/api/inspections/:id/defects', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_INPUT', message: '누락 추가에 실패했습니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const selects = screen.getAllByDisplayValue(/유형 선택|등급 선택/);
    fireEvent.change(selects[0], { target: { value: 'CRACK' } });
    fireEvent.change(selects[1], { target: { value: 'A' } });

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    fireEvent.click(saveButton!);

    // 에러 메시지가 표시된다 (모달 내 에러 메시지 확인)
    await waitFor(() => {
      const errorMessages = screen.getAllByText(/누락 추가에 실패했습니다/);
      expect(errorMessages.length).toBeGreaterThan(0);
    });
  });

  it('검수가 미완료일 때 "보고서 생성" 버튼이 비활성화된다 (#829)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '보고서 생성' });
    expect(button.hasAttribute('disabled')).toBe(true);
    // mockDefects 중 id=3(CONFIRMED), id=5(RESOLVED)가 검수 확정으로 계산 → reviewedCount=2
    expect(button.getAttribute('title')).toBe('2/5 하자 검수 확정 필요');
  });

  it('모든 하자를 검수 확정하면 "보고서 생성" 버튼이 활성화된다 (#829)', async () => {
    const allConfirmedDefects: DefectDetailItem[] = mockDefects.map((d) => ({
      ...d,
      status: 'CONFIRMED' as const,
      isReviewed: true,
    }));

    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: allConfirmedDefects };
        return HttpResponse.json(body);
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '보고서 생성' });
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  // 등급 수정 모달 테스트 (#827)
  it('"등급 수정" 버튼을 클릭하면 라디오 버튼 모달이 열린다 (#827)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '등급 수정' });
    expect(button.hasAttribute('disabled')).toBe(false);

    fireEvent.click(button);

    expect(await screen.findByText('등급 수정')).not.toBeNull();
    // 라디오 그룹 확인
    const radioGroup = screen.getByRole('radiogroup', { name: '등급 선택' });
    expect(radioGroup).not.toBeNull();
    // 등급별 색상 점이 5개 전부 렌더되는지(#944 — 등급관리 색상표와 동일한 색)
    const dots = radioGroup.querySelectorAll('span[aria-hidden="true"]');
    expect(dots.length).toBe(5);
    expect((dots[0] as HTMLElement).style.backgroundColor).toBe('rgb(22, 163, 74)'); // A #16A34A
    // B는 프로젝트 표준 팔레트(dashboard/map/chart 공통) 값 — "연한" 변형과 혼동 금지 회귀 방지(#957)
    expect((dots[1] as HTMLElement).style.backgroundColor).toBe('rgb(101, 163, 13)'); // B #65A30D
    expect((dots[2] as HTMLElement).style.backgroundColor).toBe('rgb(234, 179, 8)'); // C #EAB308
    expect((dots[3] as HTMLElement).style.backgroundColor).toBe('rgb(249, 115, 22)'); // D #F97316
    expect((dots[4] as HTMLElement).style.backgroundColor).toBe('rgb(220, 38, 38)'); // E #DC2626
  });

  it('등급 수정 모달에서 라디오 버튼으로 등급을 선택할 수 있다 (#827)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '등급 수정' }));
    // 모달이 열려있는지 라디오 그룹으로 확인
    const radioGroup = await screen.findByRole('radiogroup', { name: '등급 선택' });

    // D 라벨을 클릭해서 라디오 선택
    const dGradeLabel = screen.getByText('D (주의)');
    fireEvent.click(dGradeLabel);

    // 라디오 그룹이 여전히 표시되어 있는지 확인 (모달이 열려있음)
    expect(radioGroup).not.toBeNull();
  });

  it('등급 수정 모달에서 사유를 입력하지 않으면 확인 버튼이 비활성화된다 (#827)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '등급 수정' }));
    await screen.findByText('등급 수정');

    // 등급 선택 (라벨 클릭)
    fireEvent.click(screen.getByText('D (주의)'));

    // 확인 버튼 찾기 (라벨 "등급 수정" 모달 내)
    const confirmButtons = screen.getAllByRole('button', { name: '확인' });
    // 마지막 "확인" 버튼이 등급 수정 모달의 버튼 (누락 추가 모달이 없어서)
    const gradeConfirmButton = confirmButtons[confirmButtons.length - 1];
    expect(gradeConfirmButton.hasAttribute('disabled')).toBe(true);
  });

  it('등급 수정 모달에서 등급과 사유를 입력하면 확인 버튼이 활성화된다 (#827)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '등급 수정' }));
    await screen.findByText('등급 수정');

    // 등급 선택
    fireEvent.click(screen.getByText('D (주의)'));

    // 사유 입력 — getByPlaceholderText 사용
    const textarea = screen.getByPlaceholderText('수정 사유를 입력해주세요 (1-500자)');
    fireEvent.change(textarea, { target: { value: '검토 결과 등급 상향' } });

    // 확인 버튼 활성화 확인
    const confirmButtons = screen.getAllByRole('button', { name: '확인' });
    const gradeConfirmButton = confirmButtons[confirmButtons.length - 1];
    expect(gradeConfirmButton.hasAttribute('disabled')).toBe(false);
  });

  it('등급 수정 모달에서 저장하면 PATCH 요청을 보낸다 (#827)', async () => {
    let patchCalled = false;
    let patchPayload: DefectRevisionRequest | null = null;
    server.use(
      http.patch('/api/defects/:id', async ({ request }) => {
        patchCalled = true;
        patchPayload = (await request.json()) as DefectRevisionRequest;
        // 요청 검증: grade와 reason이 모두 포함되어야 함
        if (!patchPayload.reason || patchPayload.reason.trim().length === 0) {
          return HttpResponse.json(
            { success: false, error: { code: 'INVALID_INPUT', message: 'reason은 필수이고 1-500자여야 합니다.' } },
            { status: 400 },
          );
        }
        const updatedDefect: DefectDetailItem = mockDefects[0];
        return HttpResponse.json({ success: true, data: updatedDefect });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '등급 수정' }));
    await screen.findByText('등급 수정');

    // 등급 선택
    fireEvent.click(screen.getByText('D (주의)'));

    // 사유 입력 — getByPlaceholderText 사용
    const textarea = screen.getByPlaceholderText('수정 사유를 입력해주세요 (1-500자)');
    fireEvent.change(textarea, { target: { value: '검토 결과 등급 상향' } });

    // 확인 버튼 클릭
    const confirmButtons = screen.getAllByRole('button', { name: '확인' });
    const gradeConfirmButton = confirmButtons[confirmButtons.length - 1];
    fireEvent.click(gradeConfirmButton);

    // PATCH 요청 완료 대기 및 검증
    await waitFor(() => {
      expect(patchCalled).toBe(true);
      expect(patchPayload?.grade).toBe('D');
      expect(patchPayload?.reason).toBe('검토 결과 등급 상향');
    });
  });

  it('등급 수정 모달에서 취소하면 API 호출 없이 모달이 닫힌다 (#827)', async () => {
    let patchCalled = false;
    server.use(
      http.patch('/api/defects/:id', async () => {
        patchCalled = true;
        return HttpResponse.json({ success: false }, { status: 500 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '등급 수정' }));
    // 모달이 열렸는지 라디오 그룹으로 확인
    await screen.findByRole('radiogroup', { name: '등급 선택' });

    const cancelButtons = screen.getAllByRole('button', { name: '취소' });
    const gradeCancelButton = cancelButtons[cancelButtons.length - 1];
    fireEvent.click(gradeCancelButton);

    // 모달 다이얼로그가 닫혀야 한다 (라디오 그룹이 사라져야 함)
    expect(screen.queryByRole('radiogroup', { name: '등급 선택' })).toBeNull();

    // PATCH 호출이 없어야 한다
    expect(patchCalled).toBe(false);
  });

  it('등급 수정 모달에서 저장 실패 시 에러 메시지를 표시한다 (#827)', async () => {
    server.use(
      http.patch('/api/defects/:id', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_INPUT', message: '등급 수정에 실패했습니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '등급 수정' }));
    await screen.findByText('등급 수정');

    // 등급 선택
    fireEvent.click(screen.getByText('D (주의)'));

    // 사유 입력 — getByPlaceholderText 사용
    const textarea = screen.getByPlaceholderText('수정 사유를 입력해주세요 (1-500자)');
    fireEvent.change(textarea, { target: { value: '검토 결과' } });

    // 확인 버튼 클릭
    const confirmButtons = screen.getAllByRole('button', { name: '확인' });
    const gradeConfirmButton = confirmButtons[confirmButtons.length - 1];
    fireEvent.click(gradeConfirmButton);

    // 에러 메시지가 표시된다 (모달 내 에러 메시지 확인)
    await waitFor(() => {
      const errorMessages = screen.getAllByText(/등급 수정에 실패했습니다/);
      expect(errorMessages.length).toBeGreaterThan(0);
    });
  });

  // 회귀 테스트: 누락 추가 후 생성된 하자(mediaId=null)의 검수 확정 (#787)
  it('누락 추가로 생성한 하자를 검수 확정하면 그 하자의 id로 API를 호출한다 (#787)', async () => {
    let patchStatusCalledWith: number | null = null;

    server.use(
      http.post('/api/inspections/:id/defects', async ({ request }) => {
        const body = (await request.json()) as DefectCreateRequest;
        const newDefect: DefectDetailItem = {
          id: 999,
          inspectionId: 1,
          type: body.type,
          grade: body.grade,
          confidence: 1.0,
          status: 'DETECTED',
          isReviewed: false,
          bboxX: null,
          bboxY: null,
          bboxW: null,
          bboxH: null,
          createdAt: new Date().toISOString(),
          mediaId: null, // 누락 추가는 mediaId=null
        };
        return HttpResponse.json({ success: true, data: newDefect }, { status: 201 });
      }),
      http.get('/api/inspections/:id/defects', () => {
        // refetch 시마다 새 하자를 포함해서 반환
        const defectsWithNew: DefectDetailItem[] = [
          ...mockDefects,
          {
            id: 999,
            inspectionId: 1,
            type: 'CRACK',
            grade: 'A',
            confidence: 1.0,
            status: 'DETECTED',
            isReviewed: false,
            bboxX: null,
            bboxY: null,
            bboxW: null,
            bboxH: null,
            createdAt: new Date().toISOString(),
            mediaId: null,
          },
        ];
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: defectsWithNew };
        return HttpResponse.json(body);
      }),
      http.patch('/api/defects/:id/status', ({ params }) => {
        patchStatusCalledWith = Number(params.id);
        const updated: DefectDetailItem = {
          id: Number(params.id),
          inspectionId: 1,
          type: 'CRACK',
          grade: 'A',
          status: 'CONFIRMED',
          confidence: 1.0,
          isReviewed: true,
          bboxX: null,
          bboxY: null,
          bboxW: null,
          bboxH: null,
          createdAt: new Date().toISOString(),
          mediaId: null,
        };
        return HttpResponse.json({ success: true, data: updated });
      }),
      http.patch('/api/defects/:id', ({ params }) => {
        const updated: DefectDetailItem = {
          id: Number(params.id),
          inspectionId: 1,
          type: 'CRACK',
          grade: 'B',
          status: 'DETECTED',
          confidence: 1.0,
          isReviewed: false,
          bboxX: null,
          bboxY: null,
          bboxW: null,
          bboxH: null,
          createdAt: new Date().toISOString(),
          mediaId: null,
        };
        return HttpResponse.json({ success: true, data: updated });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    // 1. 누락 추가 클릭 → 모달 열기
    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    // 2. 유형/등급 선택 후 저장
    const selects = screen.getAllByDisplayValue(/유형 선택|등급 선택/);
    fireEvent.change(selects[0], { target: { value: 'CRACK' } });
    fireEvent.change(selects[1], { target: { value: 'A' } });

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    fireEvent.click(saveButton!);

    // 3. 모달이 닫혀야 함
    await waitFor(() => {
      expect(screen.queryByText('누락된 하자 추가')).toBeNull();
    });

    // 4. 새로운 하자(id=999)가 selectedDefectId로 설정됨
    // 5. "검수 확정" 버튼을 클릭하면 id=999가 아니라 첫 번째 하자의 id(1)로 호출되는 버그 발생
    //    수정 후에는 id=999로 올바르게 호출되어야 함
    const confirmButton = screen.getByRole('button', { name: '이 하자 검수 확정' });
    fireEvent.click(confirmButton);

    // PATCH /api/defects/999/status이 호출되어야 함 (버그: 1이 호출되던 것)
    await waitFor(() => {
      expect(patchStatusCalledWith).toBe(999);
    });
  });

  it('누락 추가한 하자를 선택하면 렌더링(버튼 활성/AI 패널)도 그 하자 기준으로 표시된다 (#975)', async () => {
    // mockDefects[0](currentDefects[0])을 CONFIRMED로 바꿔, 렌더용 selected가
    // currentDefects[0]로 폴백될 경우 "검수 확정" 버튼이 disabled 되는지로 버그를 검증한다.
    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const defectsWithNew: DefectDetailItem[] = [
          { ...mockDefects[0], status: 'CONFIRMED' as const },
          ...mockDefects.slice(1),
          {
            id: 999,
            inspectionId: 1,
            type: 'CRACK',
            grade: 'A',
            confidence: 1.0,
            status: 'DETECTED',
            isReviewed: false,
            bboxX: null,
            bboxY: null,
            bboxW: null,
            bboxH: null,
            createdAt: new Date().toISOString(),
            mediaId: null,
          },
        ];
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: defectsWithNew };
        return HttpResponse.json(body);
      }),
      http.post('/api/inspections/:id/defects', async ({ request }) => {
        const body = (await request.json()) as DefectCreateRequest;
        const newDefect: DefectDetailItem = {
          id: 999,
          inspectionId: 1,
          type: body.type,
          grade: body.grade,
          confidence: 1.0,
          status: 'DETECTED',
          isReviewed: false,
          bboxX: null,
          bboxY: null,
          bboxW: null,
          bboxH: null,
          createdAt: new Date().toISOString(),
          mediaId: null,
        };
        return HttpResponse.json({ success: true, data: newDefect }, { status: 201 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    fireEvent.click(screen.getByRole('button', { name: '누락 추가' }));
    fireEvent.click(await screen.findByRole('button', { name: '박스 없이 계속' }));
    await screen.findByText('누락된 하자 추가');

    const selects = screen.getAllByDisplayValue(/유형 선택|등급 선택/);
    fireEvent.change(selects[0], { target: { value: 'CRACK' } });
    fireEvent.change(selects[1], { target: { value: 'A' } });

    const saveButton = screen.getAllByRole('button', { name: '저장' }).pop();
    fireEvent.click(saveButton!);

    await waitFor(() => {
      expect(screen.queryByText('누락된 하자 추가')).toBeNull();
    });

    // 새로 추가한 하자(DETECTED)가 선택된 상태이므로 "검수 확정" 버튼은 비활성화되면 안 된다.
    // currentDefects[0](CONFIRMED)로 잘못 폴백되면 이 버튼이 disabled된다.
    await waitFor(() => {
      const confirmButton = screen.getByRole('button', { name: '이 하자 검수 확정' }) as HTMLButtonElement;
      expect(confirmButton.disabled).toBe(false);
    });

    // AI 분석 패널도 새로 추가한 하자(신뢰도 100%)를 표시해야 한다 — mockDefects[0]의 값이 아니라.
    expect(screen.getByText('100%')).not.toBeNull();
  });
});
