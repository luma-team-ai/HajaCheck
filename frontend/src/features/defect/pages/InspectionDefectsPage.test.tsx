// @vitest-environment jsdom
// InspectionDefectsPage 통합 테스트 — 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) KPI/카드 그리드/
// 활동 기록 사이드바 렌더링과, 카드 클릭 시 하자 상세 모달(§화면 구조 ③)이 열리고 닫히는 흐름을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { api } from '../../../shared/api/axios';
import { defectHandlers } from '../api/defectApi.handlers';
import { defectMediaApi } from '../api/defectMediaApi';
import { mockDefects } from '../mocks/defect.mock';
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

const server = setupServer(...defectHandlers, explainHandler);
// PATCH 핸들러가 mockDefects를 in-place로 변경한다(조치 결과 등록 테스트가 상태를 RESOLVED로 바꿈) —
// 다음 테스트를 오염시키지 않도록 매 테스트 후 스냅샷으로 복원한다.
const mockDefectsSnapshot = JSON.parse(JSON.stringify(mockDefects)) as typeof mockDefects;

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  mockDefectsSnapshot.forEach((snapshot, index) => {
    Object.assign(mockDefects[index], snapshot);
  });
});
afterAll(() => server.close());

function renderPage(inspectionId: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/inspections/${inspectionId}/defects`]}>
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

    // inspectionId=101 mock은 철근 노출(CONFIRMED)과 균열(DETECTED)이다. 카드 그리드는 검수 전
    // DETECTED를 숨기므로 철근 노출만 버튼으로 렌더링하고, KPI의 원본 하자 집계는 2건을 유지한다.
    expect(await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: '균열 하자 상세 보기' })).toBeNull();

    const kpi = screen.getByLabelText('점검 하자 요약');
    expect(within(kpi).getByText('총 하자')).not.toBeNull();
    expect(within(kpi).getByText('2건')).not.toBeNull();
  });

  it('헤더에 점검 ID 텍스트를 렌더링한다', async () => {
    renderPage('101');

    expect(await screen.findByRole('heading', { name: /점검 #101/ })).not.toBeNull();
  });

  it('하자가 없는 점검(inspectionId=301)은 빈 상태 메시지를 표시한다', async () => {
    renderPage('301');

    expect(await screen.findByText('조회된 하자가 없습니다. 필터 조건을 변경해 보세요.')).not.toBeNull();
  });

  it('카드를 클릭하면 하자 상세 모달이 열리고, 조치 결과 등록 폼과 활동 기록을 보여준다', async () => {
    renderPage('101');

    const card = await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' });
    fireEvent.click(card);

    const modal = await screen.findByRole('dialog', { name: '하자 상세' });
    // 모달 컨테이너(role=dialog)는 즉시 렌더링되지만 내부 하자 데이터는 useDefect(id) 비동기 조회 후
    // 채워지므로 findBy로 로딩 완료를 기다린다.
    expect(await within(modal).findByText('DEF-0001')).not.toBeNull();
    expect(within(modal).getByRole('heading', { name: '조치 결과 등록' })).not.toBeNull();
    expect(within(modal).getByLabelText('조치 내용 *')).not.toBeNull();
  });

  it('모달의 닫기 버튼을 클릭하면 모달이 닫힌다', async () => {
    renderPage('101');

    const card = await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' });
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

    const card = await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' });
    fireEvent.click(card);
    const modal = await screen.findByRole('dialog', { name: '하자 상세' });
    await within(modal).findByText('DEF-0001');

    const photoInput = within(modal).getByLabelText('조치 후 사진 업로드 *') as HTMLInputElement;
    const file = new File(['dummy'], 'after.png', { type: 'image/png' });
    fireEvent.change(photoInput, { target: { files: [file] } });

    fireEvent.change(within(modal).getByLabelText('조치 내용 *'), {
      target: { value: '균열 부위 에폭시 주입 및 표면 도포 완료' },
    });
    fireEvent.change(within(modal).getByLabelText('조치일 *'), { target: { value: '2026-07-20' } });
    fireEvent.change(within(modal).getByLabelText('담당자 *'), {
      target: { value: (await within(modal).findByRole('option', { name: '김도현 검사자' })).getAttribute('value') },
    });

    // 1차 제출 — 검수확정(CONFIRMED)에서 유효한 다음 단계는 조치중(IN_PROGRESS)뿐(#1128 1단계 전이).
    expect((within(modal).getByLabelText('진행상태 *') as HTMLSelectElement).value).toBe('IN_PROGRESS');
    fireEvent.click(within(modal).getByRole('button', { name: '상태 저장' }));

    // 조치중은 종료 상태가 아니므로 폼이 계속 보인다(읽기 전용 전환 아님) — 다음 제출 대상은 조치완료.
    // 뮤테이션 성공 후 setQueryData로 상세 캐시가 갱신되는 게 비동기라, select 값이 실제로
    // IN_PROGRESS 파생값(RESOLVED가 다음 단계)으로 바뀔 때까지 기다린다.
    await waitFor(() =>
      expect((within(modal).getByLabelText('진행상태 *') as HTMLSelectElement).value).toBe('RESOLVED'),
    );
    expect(within(modal).getByLabelText('조치 후 사진 업로드 *')).not.toBeNull();
    expect(modal.querySelector('.defect-chip--warning')?.textContent).toContain('조치중');
    // #1128 코드리뷰 P2-2 — 성공 후 폼 필드가 초기화되고 성공 알림이 뜬다(중복 업로드/의도치 않은
    // 완결 방지). 초기화된 상태라 재제출은 필드를 다시 채워야만 가능하다.
    expect(within(modal).getByText('조치중(으)로 저장되었습니다.')).not.toBeNull();
    expect((within(modal).getByRole('button', { name: '상태 저장' }) as HTMLButtonElement).disabled).toBe(true);

    // 2차 제출 — 조치중(IN_PROGRESS)에서 유효한 다음 단계는 조치완료(RESOLVED). 초기화된 필드를
    // 다시 채운 뒤 제출한다. RESOLVED는 종료 상태라 이번엔 폼이 읽기 전용 요약으로 전환된다.
    const secondFile = new File(['dummy2'], 'after2.png', { type: 'image/png' });
    fireEvent.change(within(modal).getByLabelText('조치 후 사진 업로드 *'), { target: { files: [secondFile] } });
    fireEvent.change(within(modal).getByLabelText('조치 내용 *'), {
      target: { value: '보수 완료 확인 — 재발 없음' },
    });
    fireEvent.change(within(modal).getByLabelText('조치일 *'), { target: { value: '2026-07-21' } });
    fireEvent.change(within(modal).getByLabelText('담당자 *'), {
      target: { value: (await within(modal).findByRole('option', { name: '김도현 검사자' })).getAttribute('value') },
    });
    fireEvent.click(within(modal).getByRole('button', { name: '상태 저장' }));

    await waitFor(() => expect(within(modal).queryByLabelText('조치 후 사진 업로드 *')).toBeNull());
    // 조치 필드는 1세트뿐이라 2차 등록 내용이 최종 요약에 남는다(1차 내용은 감사기록으로만 보존,
    // #1128 코드리뷰 P2-1).
    expect(within(modal).getByText('보수 완료 확인 — 재발 없음')).not.toBeNull();
    expect(modal.querySelector('.defect-chip--warning')?.textContent).toContain('해결됨');
    expect(uploadSpy).toHaveBeenCalledTimes(2);

    uploadSpy.mockRestore();
  });

  it('우측 활동 기록 사이드바에 점검에 속한 하자들의 변경 이력을 모아 보여준다', async () => {
    renderPage('101');
    await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' });

    const activityPanel = screen.getByLabelText('점검 활동 기록');
    // mockDefectRevisions[1]: 과거 CONFIRMED→ACTION_PENDING 변경 이력이 존재(defect.mock.ts).
    expect(
      await within(activityPanel).findByText("상태를 '확인됨'에서 '조치대기'(으)로 변경했습니다."),
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
