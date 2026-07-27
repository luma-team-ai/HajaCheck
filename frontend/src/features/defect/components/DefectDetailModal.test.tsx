// @vitest-environment jsdom
// 하자 상세 모달 — 조치 전/후 사진 탭(#969) 단위 테스트. DefectDetailPage.test.tsx와 동일하게
// 훅을 모킹하지 않고 QueryClientProvider + MSW(defectHandlers, mockDefects)로 실제 데이터 흐름을
// 그대로 태운다(이 프로젝트 관례 — feature 훅을 직접 mock하는 기존 사례 없음).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { mockDefects } from '../mocks/defect.mock';
import type { Defect } from '../types';
import { DefectDetailModal } from './DefectDetailModal';

// DefectExplainPanel이 마운트 시 자동으로 호출하는 AI 설명 엔드포인트 — DefectDetailPage.test.tsx와
// 동일한 최소 목(모달 자체의 관심사가 아니므로 응답 형태만 맞춘다).
const explainHandler = http.post('/api/ai/defect-explain', () =>
  HttpResponse.json({
    success: true,
    data: {
      cause: '원인 예시',
      risk: '위험 예시',
      action: '조치 예시',
    },
  }),
);

const server = setupServer(...defectHandlers, explainHandler);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderModal(defectId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DefectDetailModal defectId={defectId} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

// mockDefects id=1(철근 노출)에 actionResult를 얹어 반환하는 오버라이드 핸들러.
function mockDefectWithActionResult(): Defect {
  return {
    ...mockDefects[0],
    actionResult: {
      actionContent: '에폭시 주입 처리',
      actionDate: '2026-07-20',
      assigneeId: 1,
      assigneeName: '홍길동',
      afterPhotoUrl: '/api/media/999/thumbnail',
    },
  };
}

describe('DefectDetailModal — 조치 전/후 사진 탭', () => {
  it('actionResult가 없으면 탭바 없이 기존 라벨("조치 전 사진 (원본)")만 보인다', async () => {
    renderModal(1); // mockDefects id=1: actionResult 없음

    await screen.findByText('철근 노출');

    expect(screen.getByText('조치 전 사진 (원본)')).not.toBeNull();
    expect(screen.queryByRole('tablist', { name: '조치 전/후 사진' })).toBeNull();
  });

  it('actionResult가 있으면 탭바가 렌더되고 기본은 "조치 전 사진" 탭이 활성 상태다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<Defect> = { success: true, data: mockDefectWithActionResult() };
        return HttpResponse.json(body);
      }),
    );

    renderModal(1);
    await screen.findByText('철근 노출');

    const tablist = screen.getByRole('tablist', { name: '조치 전/후 사진' });
    expect(tablist).not.toBeNull();

    const beforeTab = screen.getByRole('tab', { name: '조치 전 사진' });
    const afterTab = screen.getByRole('tab', { name: '조치 사진' });
    expect(beforeTab.getAttribute('aria-selected')).toBe('true');
    expect(afterTab.getAttribute('aria-selected')).toBe('false');

    // 기본 탭에서는 원본 이미지(mockDefects id=1의 imageUrl)가 노출된다.
    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/901/thumbnail');
  });

  it('"조치 사진" 탭 클릭 시 활성 탭이 전환되고 afterPhotoUrl 이미지가 노출된다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<Defect> = { success: true, data: mockDefectWithActionResult() };
        return HttpResponse.json(body);
      }),
    );

    renderModal(1);
    await screen.findByText('철근 노출');

    const afterTab = screen.getByRole('tab', { name: '조치 사진' });
    fireEvent.click(afterTab);

    expect(afterTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '조치 전 사진' }).getAttribute('aria-selected')).toBe('false');

    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/999/thumbnail');
  });
});
