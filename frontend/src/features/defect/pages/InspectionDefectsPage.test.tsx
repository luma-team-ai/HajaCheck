// @vitest-environment jsdom
// InspectionDefectsPage 통합 테스트 — 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) KPI/카드 그리드/
// 활동 기록 사이드바 렌더링과, 카드 클릭 시 하자 상세 모달(§화면 구조 ③)이 열리고 닫히는 흐름을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
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
// 다음 테스트를 오염시키지 않도록 매 테스트 후 스냅샷으로 복원한다(DefectDetailPage.test.tsx와 동일 패턴).
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

    // 카드 그리드의 유형 필터 select에도 같은 라벨의 option이 있어(§DefectCardGrid) 카드 자체는
    // 버튼 role(aria-label)로 특정한다 — 단순 getByText는 select option과 모호(ambiguous)해진다.
    expect(await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '균열 하자 상세 보기' })).not.toBeNull();

    const kpi = screen.getByLabelText('점검 하자 요약');
    expect(within(kpi).getByText('총 하자')).not.toBeNull();
    expect(within(kpi).getByText('2')).not.toBeNull();
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

  it('조치 결과 등록 폼을 제출하면 상태가 해결됨으로 바뀌고 읽기 전용 요약으로 전환된다', async () => {
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

    const submitButton = within(modal).getByRole('button', {
      name: '조치 완료 등록',
    }) as HTMLButtonElement;
    expect(submitButton.disabled).toBe(false);
    fireEvent.click(submitButton);

    expect(await within(modal).findByText('균열 부위 에폭시 주입 및 표면 도포 완료')).not.toBeNull();
    expect(await within(modal).findByText('해결됨')).not.toBeNull();
    expect(uploadSpy).toHaveBeenCalledWith(101, file, expect.any(Function));

    uploadSpy.mockRestore();
  });

  it('우측 활동 기록 사이드바에 점검에 속한 하자들의 변경 이력을 모아 보여준다', async () => {
    renderPage('101');
    await screen.findByRole('button', { name: '철근 노출 하자 상세 보기' });

    const activityPanel = screen.getByLabelText('점검 활동 기록');
    // mockDefectRevisions[1]: 상태 확인됨→조치대기 변경 이력이 존재(defect.mock.ts).
    expect(
      await within(activityPanel).findByText("상태를 '확인됨'에서 '조치대기'(으)로 변경했습니다."),
    ).not.toBeNull();
  });
});
