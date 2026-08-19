// @vitest-environment jsdom
// InspectionDefectsPage 통합 테스트 — 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) KPI/카드 그리드/
// 활동 기록 사이드바 렌더링과, 카드 클릭 시 하자 상세 모달(§화면 구조 ③)이 열리고 닫히는 흐름을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { api } from '../../../shared/api/axios';
import { facilityAssigneeHandlers } from '../../facility/api/facilityAssigneeApi.handlers';
import { inspectionHandlers } from '../../inspection/api/inspectionApi.handlers';
import { defectHandlers } from '../api/defectApi.handlers';
import { defectMediaApi } from '../api/defectMediaApi';
import { mockDefects, mockInspectionDefectResponses } from '../mocks/defect.mock';
import { InspectionDefectsPage } from './InspectionDefectsPage';

const explainHandler = http.post('/api/ai/defect-explain', () =>
  HttpResponse.json({
    success: true,
    data: {
      cause: '구조 응력과 미세한 재료 수축이 복합적으로 작용한 것으로 추정됩니다.',
      risk: '방치 시 균열과 부식이 진행될 수 있습니다.',
      action: '에폭시 주입 후 표면 도포를 권장합니다.',
    },
  }),
);

// 코드리뷰 P2(#1693) — GET /api/inspections/:id는 inspectionApi.handlers.ts가 유일하게 소유한다
// (defectApi.handlers.ts에 더는 등록하지 않음, 중복 시 mocks/handlers.ts 전역 체인에서 죽은
// 코드가 되는 문제 재발 방지). 이 격리 서버도 mocks/handlers.ts의 상대 순서(facilityAssigneeHandlers
// → inspectionHandlers → defectHandlers)를 그대로 반영해, 실제 앱과 동일한 핸들러가 응답하는지
// 검증한다 — facilityAssigneeHandlers를 inspectionHandlers보다 먼저 두지 않으면 inspectionHandlers의
// GET /api/facilities/:id(파라미터 라우트)가 GET /api/facilities/assignable-users를 가로챈다
// (mocks/handlers.ts:50-52 주석과 동일 이유. 두 핸들러의 데이터값 자체는 동일해 지금까지는
// 드러나지 않았을 뿐인 잠재 회귀였다).
const server = setupServer(...facilityAssigneeHandlers, ...inspectionHandlers, ...defectHandlers, explainHandler);
// PATCH 핸들러가 mockDefects를 in-place로 변경한다(조치 결과 등록 테스트가 상태를 RESOLVED로 바꿈) —
// 다음 테스트를 오염시키지 않도록 매 테스트 후 스냅샷으로 복원한다.
const mockDefectsSnapshot = JSON.parse(JSON.stringify(mockDefects)) as typeof mockDefects;
const mockInspectionDefectsSnapshot = JSON.parse(
  JSON.stringify(mockInspectionDefectResponses),
) as typeof mockInspectionDefectResponses;

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  mockDefectsSnapshot.forEach((snapshot, index) => {
    Object.assign(mockDefects[index], snapshot);
  });
  mockInspectionDefectsSnapshot.forEach((snapshot, index) => {
    Object.assign(mockInspectionDefectResponses[index], snapshot);
  });
});
afterAll(() => server.close());

function renderPage(inspectionId: string, search = '') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/inspections/${inspectionId}/defects${search}`]}>
        <Routes>
          <Route path="/inspections/:id/defects" element={<InspectionDefectsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('InspectionDefectsPage (통합 테스트)', () => {
  it('점검(inspectionId=101)에 속한 하자 카드와 KPI를 렌더링한다', async () => {
    renderPage('101');

    // inspectionId=101은 같은 이미지의 관리 대상 2건과 별도 DETECTED 1건이다.
    expect(await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' })).not.toBeNull();

    const kpi = screen.getByLabelText('점검 하자 요약');
    expect(within(kpi).getByText('총 하자')).not.toBeNull();
    expect(within(kpi).getByText('3건')).not.toBeNull();
  });

  it('헤더에 점검 코드(INS-nnnn) 텍스트를 렌더링한다', async () => {
    renderPage('101');

    expect(await screen.findByRole('heading', { name: /INS-0101/ })).not.toBeNull();
  });

  // #1693 — 목록 "상태" 컬럼(InspectionStatus)과 상세 KPI(DefectStatus)가 서로 다른 엔티티의
  // 상태인데 화면에 구분 표시가 없어 오인 사고가 났다. 상세 화면에 점검 상태 배지를 별도로 렌더링해야
  // 한다(mockInspections id=101 status=REVIEWED → "검수완료").
  it('헤더에 점검 상태(InspectionStatus) 배지를 렌더링한다', async () => {
    renderPage('101');

    expect(await screen.findByText('점검 상태: 검수완료')).not.toBeNull();
  });

  // 점검 조회(useInspection)는 하자 목록 조회(useInspectionDefects)와 완전히 독립된 쿼리라, 점검
  // 조회가 아직 로딩 중이어도 하자 카드 그리드 렌더는 막지 않아야 한다 — 배지만 미표시.
  it('점검 조회가 로딩 중이어도 하자 목록은 정상 렌더되고, 점검 상태 배지만 표시되지 않는다', async () => {
    server.use(
      http.get('/api/inspections/:id', async () => {
        await delay('infinite');
      }),
    );

    renderPage('101');

    expect(await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' })).not.toBeNull();
    expect(screen.queryByText(/^점검 상태:/)).toBeNull();
  });

  // 점검 조회가 실패해도(예: 네트워크 오류) 기존 하자 목록/에러 분기(isLoading/isError/ErrorFallback)
  // 동작을 그대로 유지해야 한다 — 점검 상태 배지 하나만 조용히 빠진다.
  it('점검 조회가 에러여도 하자 목록은 정상 렌더되고, 점검 상태 배지만 표시되지 않는다', async () => {
    server.use(
      http.get('/api/inspections/:id', () =>
        HttpResponse.json(
          {
            success: false,
            data: null,
            error: { code: 'INSPECTION_NOT_FOUND', message: '점검 회차를 찾을 수 없습니다.' },
          },
          { status: 404 },
        ),
      ),
    );

    renderPage('101');

    expect(await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' })).not.toBeNull();
    expect(screen.queryByText(/^점검 상태:/)).toBeNull();
  });

  it('하자가 없는 점검(inspectionId=301)은 빈 상태 메시지를 표시한다', async () => {
    renderPage('301');

    expect(await screen.findByText('조회된 하자가 없습니다. 필터 조건을 변경해 보세요.')).not.toBeNull();
  });

  it('카드를 클릭하면 하자 상세 모달이 열리고, 조치 결과 등록 폼과 활동 기록을 보여준다', async () => {
    renderPage('101');

    const card = await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' });
    fireEvent.click(card);

    const modal = await screen.findByRole('dialog', { name: '하자 상세' });
    // 모달 컨테이너(role=dialog)는 즉시 렌더링되지만 내부 하자 데이터는 useDefect(id) 비동기 조회 후
    // 채워지므로 findBy로 로딩 완료를 기다린다.
    expect(await within(modal).findByText('DEF-0001')).not.toBeNull();
    expect(within(modal).getByRole('heading', { name: '조치 결과 등록' })).not.toBeNull();
    expect(within(modal).getByLabelText('조치 내용')).not.toBeNull();
  });

  // 대시보드 "검수하기" → defectId 쿼리파라미터 딥링크(#1117 회귀 수정) — 카드를 클릭하지 않아도
  // 진입 시점에 모달이 자동으로 열려야 한다.
  it('URL에 defectId 쿼리파라미터가 있으면 카드 클릭 없이도 하자 상세 모달이 자동으로 열린다', async () => {
    renderPage('101', '?defectId=1');

    const modal = await screen.findByRole('dialog', { name: '하자 상세' });
    expect(await within(modal).findByText('DEF-0001')).not.toBeNull();
  });

  it('같은 mediaId의 하자는 카드 하나로 묶고 딥링크는 대표 하자가 아닌 지정 하자를 정확히 연다', async () => {
    const groupedDefects = [
      { ...mockInspectionDefectResponses[0] },
      {
        ...mockInspectionDefectResponses[0],
        id: 6,
        mediaId: 901,
        type: 'CRACK' as const,
        typeLabel: '균열',
        grade: 'E' as const,
        status: 'IN_PROGRESS' as const,
        confidence: 0.97,
        bboxX: 0.55,
        bboxY: 0.45,
        bboxW: 0.18,
        bboxH: 0.22,
      },
    ];
    server.use(
      http.get('/api/inspections/:id/defects', () =>
        HttpResponse.json({ success: true, data: groupedDefects }),
      ),
      http.get('/api/defects/:id', ({ params }) => {
        const found = groupedDefects.find((defect) => defect.id === Number(params.id));
        return HttpResponse.json({
          success: true,
          data: found
            ? {
                ...mockDefects[0],
                id: found.id,
                type: found.type,
                typeLabel: found.typeLabel,
                grade: found.grade,
                status: found.status,
                confidence: found.confidence,
                reviewed: found.isReviewed,
              }
            : null,
        });
      }),
    );

    renderPage('101', '?defectId=1');

    const cards = await screen.findAllByRole('button', {
      name: '균열 · 철근 노출 이미지 카드 상세 보기',
      hidden: true,
    });
    expect(cards).toHaveLength(1);
    expect(screen.getByText('하자 2건')).not.toBeNull();
    const modal = await screen.findByRole('dialog', { name: '하자 상세' });
    expect(await within(modal).findByText('DEF-0001')).not.toBeNull();
  });

  // 코드리뷰 P2 — defectId를 URL에 남겨두면 모달을 닫은 뒤 새로고침/공유 시 같은 모달이 사용자 의사와
  // 무관하게 재오픈된다. 초기값으로 소비한 즉시 URL에서 제거되는지 검증한다.
  it('defectId 쿼리파라미터는 진입 시 모달을 연 뒤 URL에서 즉시 제거된다', async () => {
    function PageWithUrlProbe() {
      const [searchParams] = useSearchParams();
      return (
        <>
          <InspectionDefectsPage />
          <div data-testid="url-probe">{searchParams.toString()}</div>
        </>
      );
    }
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/inspections/101/defects?defectId=1']}>
          <Routes>
            <Route path="/inspections/:id/defects" element={<PageWithUrlProbe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByRole('dialog', { name: '하자 상세' });

    await waitFor(() => expect(screen.getByTestId('url-probe').textContent).toBe(''));
  });

  it('모달의 닫기 버튼을 클릭하면 모달이 닫힌다', async () => {
    renderPage('101');

    const card = await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' });
    fireEvent.click(card);
    await screen.findByRole('dialog', { name: '하자 상세' });

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(screen.queryByRole('dialog', { name: '하자 상세' })).toBeNull();
  });

  // #1128 — targetStatus 도입 이후 "검수확정→조치완료" 한 번의 제출로 바로 가지 않는다. 백엔드의
  // 정방향 1단계 전이 규칙상 CONFIRMED에서 유효한 다음 단계는 IN_PROGRESS뿐이라, 조치완료까지
  // 가려면 같은 폼을 두 번(조치중 등록 → 조치완료 등록) 제출해야 한다. RESOLVED(종료 상태)에
  // 도달했을 때만 폼이 읽기 전용 요약으로 전환된다.
  it('조치 결과 등록 폼을 두 번 제출하면 검수확정→조치중→조치완료로 순차 전이하고, 조치완료 시점에만 읽기 전용 요약으로 전환된다', async () => {
    // 실 axios→MSW 네트워크 경로로 File/FormData를 태우면 jsdom File과 Node undici의 multipart
    // 파서가 호환되지 않아 테스트가 깨진다(inspection feature의 InspectionCreatePage.test.tsx와
    // 동일하게 이미 겪은 문제) — 업로드 API 자체는 mediaApi.upload처럼 spy로 우회하고, 이 테스트는
    // "업로드 후 PATCH로 상태/조치결과가 반영되는" 흐름에 집중한다.
    const uploadSpy = vi
      .spyOn(defectMediaApi, 'uploadActionPhoto')
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .mockResolvedValue({ data: [{ id: 9001, thumbnailUrl: '/api/media/9001/thumbnail' }] } as any);

    renderPage('101');

    const card = await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' });
    fireEvent.click(card);
    const modal = await screen.findByRole('dialog', { name: '하자 상세' });
    await within(modal).findByText('DEF-0001');

    const photoInput = within(modal).getByLabelText('조치 후 사진 업로드') as HTMLInputElement;
    const file = new File(['dummy'], 'after.png', { type: 'image/png' });
    fireEvent.change(photoInput, { target: { files: [file] } });

    fireEvent.change(within(modal).getByLabelText('조치 내용'), {
      target: { value: '균열 부위 에폭시 주입 및 표면 도포 완료' },
    });
    fireEvent.change(within(modal).getByLabelText('조치일'), { target: { value: '2026-07-20' } });
    fireEvent.change(within(modal).getByLabelText('담당자'), {
      target: { value: (await within(modal).findByRole('option', { name: '김도현 검사자' })).getAttribute('value') },
    });

    // 1차 제출 — 검수확정(CONFIRMED)에서 유효한 다음 단계는 조치중(IN_PROGRESS)뿐(#1128 1단계 전이).
    expect((within(modal).getByLabelText('진행상태 *') as HTMLSelectElement).value).toBe('IN_PROGRESS');
    fireEvent.click(within(modal).getByRole('button', { name: '상태 저장' }));

    // 조치중은 종료 상태가 아니므로 폼이 계속 보인다(읽기 전용 전환 아님). #1193/HAJA-569 —
    // IN_PROGRESS에서는 select가 "조치중"(유지)/"조치완료" 두 옵션으로 활성화되고, 실수 방지를
    // 위해 기본값은 여전히 "조치중"(자동으로 다음 단계까지 넘어가지 않음). 뮤테이션 성공 후
    // setQueryData로 상세 캐시가 갱신되는 게 비동기라, select가 활성화될 때까지 기다린다.
    await waitFor(() =>
      expect((within(modal).getByLabelText('진행상태 *') as HTMLSelectElement).disabled).toBe(false),
    );
    expect((within(modal).getByLabelText('진행상태 *') as HTMLSelectElement).value).toBe('IN_PROGRESS');
    expect(within(modal).getByLabelText('조치 후 사진 업로드')).not.toBeNull();
    // 헤더 상태 칩은 카드 그리드 목록 캐시(별도 invalidateQueries, 비동기 refetch)를 참조하므로
    // 상세 캐시 갱신(위 select 활성화)과는 독립적으로 완료된다 — 명시적으로 기다린다(레이스 방지).
    await waitFor(() =>
      expect(modal.querySelector('.defect-chip--warning')?.textContent).toContain('조치중'),
    );
    // #1128 코드리뷰 P2-2 — 성공 후 폼 필드가 초기화되고 성공 알림이 뜬다(중복 업로드/의도치 않은
    // 완결 방지). 초기화된 상태라 재제출은 필드를 다시 채워야만 가능하다.
    expect(within(modal).getByText('조치중(으)로 저장되었습니다.')).not.toBeNull();
    expect((within(modal).getByRole('button', { name: '상태 저장' }) as HTMLButtonElement).disabled).toBe(true);

    // 2차 제출 — 조치중(IN_PROGRESS)에서 select 기본값은 "조치중"(유지)이므로, 종료 상태인
    // 조치완료(RESOLVED)로 전이하려면 이번엔 사용자가 명시적으로 "조치완료"를 선택해야 한다
    // (#1193/HAJA-569 — 자동 다음 단계 강제 제거). 초기화된 필드를 다시 채운 뒤 제출한다.
    const secondFile = new File(['dummy2'], 'after2.png', { type: 'image/png' });
    fireEvent.change(within(modal).getByLabelText('조치 후 사진 업로드'), { target: { files: [secondFile] } });
    fireEvent.change(within(modal).getByLabelText('조치 내용'), {
      target: { value: '보수 완료 확인 — 재발 없음' },
    });
    fireEvent.change(within(modal).getByLabelText('조치일'), { target: { value: '2026-07-21' } });
    fireEvent.change(within(modal).getByLabelText('담당자'), {
      target: { value: (await within(modal).findByRole('option', { name: '김도현 검사자' })).getAttribute('value') },
    });
    fireEvent.change(within(modal).getByLabelText('진행상태 *'), { target: { value: 'RESOLVED' } });
    fireEvent.click(within(modal).getByRole('button', { name: '상태 저장' }));

    await waitFor(() => expect(within(modal).queryByLabelText('조치 후 사진 업로드')).toBeNull());
    // 조치 필드는 1세트뿐이라 2차 등록 내용이 최종 요약에 남는다(1차 내용은 감사기록으로만 보존,
    // #1128 코드리뷰 P2-1).
    expect(within(modal).getByText('보수 완료 확인 — 재발 없음')).not.toBeNull();
    // 헤더 상태 칩은 이제 사진(그룹) 단위 집계다(#1644, defectGroupSummary.ts) — 목록 캐시(별도
    // 비동기 refetch)를 참조하므로 명시적으로 기다린다(레이스 방지). id=1(철근 노출)과 같은 사진
    // (mediaId=901)에 id=4(균열, mock 고정값 IN_PROGRESS)가 함께 있어, id=1만 조치완료로 전이해도
    // 그룹 전체가 RESOLVED는 아니라(하나라도 IN_PROGRESS 이상이면 IN_PROGRESS) 칩은 "조치중"인
    // 채로 남는다 — id=1의 상태 자체는 아래에서 읽기 전용 요약 전환으로 별도 검증한다.
    await waitFor(() =>
      expect(modal.querySelector('.defect-chip--warning')?.textContent).toContain('이 사진의 조치 상태: 조치중 (하자 2건)'),
    );
    expect(uploadSpy).toHaveBeenCalledTimes(2);

    uploadSpy.mockRestore();
  });

  it('우측 활동 기록 사이드바에 점검에 속한 하자들의 변경 이력을 모아 보여준다', async () => {
    renderPage('101');
    await screen.findByRole('button', { name: '철근 노출 · 균열 이미지 카드 상세 보기' });

    const activityPanel = screen.getByLabelText('점검 활동 기록');
    // mockDefectRevisions[1]: 과거 CONFIRMED→ACTION_PENDING 변경 이력이 존재(defect.mock.ts).
    expect(
      await within(activityPanel).findByText("상태를 '검수확정'에서 '조치대기'(으)로 변경했습니다."),
    ).not.toBeNull();
  });
});

// PR머신 P1 회귀 방지 — mocks/handlers.ts의 전역 등록 순서(mediaHandlers가 defectHandlers보다
// 먼저 등록)를 그대로 재현해 "조치 후 사진 업로드"가 실제 앱과 동일한 핸들러 충돌 경로를 타는지
// 검증한다. 위 describe는 격리된 setupServer(...defectHandlers)만 써서 이 충돌을 잡지 못했다
// (defectHandlers만 있으면 defectHandlers 안의 자체 media mock이 응답해 항상 성공해버림) —
// 반드시 전역 handlers 배열로 별도 서버를 띄워 검증한다. 기존 describe의 격리 서버와는 완전히
// 분리된 인스턴스라 서로의 handler/state에 영향을 주지 않는다.
describe('InspectionDefectsPage — 전역 MSW 핸들러 등록 순서 회귀 테스트(PR머신 P1)', () => {
  // mocks/handlers.ts가 집계하는 feature handler 수가 늘어날수록 동적 import 비용이 커진다 —
  // 전체 스위트 병렬 실행 시 기본 5000ms 타임아웃에 종종 걸린다(router.dev-routes.test.tsx와
  // 동일한 이유). 넉넉한 타임아웃으로 여유를 둔다.
  it(
    'inspectionId=202(한강대교 북단)로 온 요청이 mediaHandlers 화이트리스트를 통과한다',
    async () => {
      const { allMockHandlers } = await import('../../../mocks/handlers');
      const globalServer = setupServer(...allMockHandlers);
      globalServer.listen({ onUnhandledRequest: 'error' });

      try {
        // features/inspection/api/mediaApi.handlers.ts의 KNOWN_INSPECTION_IDS 화이트리스트에 202가
        // 없으면(회귀 시) mediaHandlers가 defectHandlers보다 먼저 등록돼 있어(mocks/handlers.ts)
        // 이 요청이 항상 INSPECTION_NOT_FOUND(404)로 실패한다.
        //
        // 실제 File을 담아 undici로 보내면 jsdom File과 Node multipart 파서가 호환되지 않아 무관한
        // ERR_ASSERTION이 나므로(별도 환경 이슈, 실제 앱 동작과는 무관), 파일 없는 FormData로 요청해
        // "inspectionId 화이트리스트 검사"만 특정해 확인한다 — mediaApi.handlers.ts는 그 검사를 파일
        // 유무 확인보다 먼저 수행하므로(handler 상단), 404가 아니라 FILE_REQUIRED(400)가 오면 202가
        // 화이트리스트를 통과했다는 뜻이다.
        await expect(api.post('/inspections/202/media', new FormData())).rejects.toMatchObject({
          code: 'FILE_REQUIRED',
        });
      } finally {
        globalServer.close();
      }
    },
    15_000,
  );
});
