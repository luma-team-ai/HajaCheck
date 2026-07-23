// @vitest-environment jsdom
// DefectActionBoard 렌더링 스모크 테스트 — 컬럼 라벨(STEP_LABEL)·카드 배치가 상태별로 올바른지
// 확인한다. 실제 포인터/키보드 드래그 시뮬레이션은 jsdom에서 신뢰하기 어려워(dnd-kit 레이아웃 측정)
// 드롭 오케스트레이션 자체는 useDefectActionBoard.test.tsx에서 handleDragEnd를 직접 호출해 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { defectHandlers } from '../api/defectApi.handlers';
import { DefectActionBoard } from './DefectActionBoard';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderBoard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DefectActionBoard filters={{}} />
    </QueryClientProvider>,
  );
}

describe('DefectActionBoard', () => {
  it('DefectStatus 5단계를 STEP_LABEL 표기로 컬럼 렌더링한다', async () => {
    renderBoard();

    expect(await screen.findByLabelText('신규 컬럼')).not.toBeNull();
    expect(screen.getByLabelText('검수확정 컬럼')).not.toBeNull();
    expect(screen.getByLabelText('조치대기 컬럼')).not.toBeNull();
    expect(screen.getByLabelText('조치중 컬럼')).not.toBeNull();
    expect(screen.getByLabelText('조치완료 컬럼')).not.toBeNull();
  });

  it('각 하자를 현재 상태에 해당하는 컬럼에 카드로 배치한다', async () => {
    renderBoard();

    // mockDefects: id 1(철근 노출)은 ACTION_PENDING(조치대기), id 2(균열)·3(박리·박락)은 DETECTED(신규).
    const detectedColumn = await screen.findByLabelText('신규 컬럼');
    expect(within(detectedColumn).getByText('균열')).not.toBeNull();
    expect(within(detectedColumn).getByText('박리·박락')).not.toBeNull();

    const actionPendingColumn = screen.getByLabelText('조치대기 컬럼');
    expect(within(actionPendingColumn).getByText('철근 노출')).not.toBeNull();
  });

  it('빈 컬럼에는 안내 문구를 표시한다', async () => {
    renderBoard();

    const resolvedColumn = await screen.findByLabelText('조치완료 컬럼');
    expect(within(resolvedColumn).getByText('하자 없음')).not.toBeNull();
  });
});
