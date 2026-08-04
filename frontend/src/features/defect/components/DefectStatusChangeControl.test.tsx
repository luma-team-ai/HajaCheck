// @vitest-environment jsdom
// "다른 상태로 변경"(HAJA-349/#630 사유 UI 재통합) — 실 훅 + MSW로 PATCH /api/defects/:id/status
// 왕복까지 검증한다(이 프로젝트 관례 — feature 훅을 직접 mock하는 기존 사례 없음, DefectDetailModal.test.tsx 참고).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { mockDefects } from '../mocks/defect.mock';
import type { Defect } from '../types';
import { DefectStatusChangeControl } from './DefectStatusChangeControl';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderControl(defect: Defect) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DefectStatusChangeControl defect={defect} />
    </QueryClientProvider>,
  );
}

describe('DefectStatusChangeControl', () => {
  it('CONFIRMED에서는 역행(DETECTED)/건너뛰기(RESOLVED) 옵션만 보여준다', () => {
    renderControl({ ...mockDefects[0], status: 'CONFIRMED' });

    const select = screen.getByLabelText('다른 상태로 변경') as HTMLSelectElement;
    expect(Array.from(select.options).map((option) => option.value)).toEqual(['', 'DETECTED', 'RESOLVED']);
  });

  it('IN_PROGRESS에서는 역행(DETECTED/CONFIRMED) 옵션만 보여준다', () => {
    renderControl({ ...mockDefects[0], status: 'IN_PROGRESS' });

    const select = screen.getByLabelText('다른 상태로 변경') as HTMLSelectElement;
    expect(Array.from(select.options).map((option) => option.value)).toEqual(['', 'DETECTED', 'CONFIRMED']);
  });

  it('RESOLVED(종료 상태)에서는 컨트롤 자체를 렌더링하지 않는다', () => {
    renderControl({ ...mockDefects[0], status: 'RESOLVED' });
    expect(screen.queryByLabelText('다른 상태로 변경')).toBeNull();
  });

  it('DETECTED(등급 미확정)에서는 컨트롤 자체를 렌더링하지 않는다', () => {
    renderControl({ ...mockDefects[0], status: 'DETECTED' });
    expect(screen.queryByLabelText('다른 상태로 변경')).toBeNull();
  });

  it('대상 선택 시 사유 모달이 열리고, 사유를 제출하면 PATCH /status를 호출해 상태가 반영된다', async () => {
    const defect: Defect = { ...mockDefects[0], id: 501, status: 'CONFIRMED' };
    server.use(
      http.patch('/api/defects/:id/status', async ({ request }) => {
        const body = (await request.json()) as { status: string; reason?: string };
        expect(body.status).toBe('RESOLVED');
        expect(body.reason).toBe('시공사 조기 완료 확인');
        const response: ApiResponse<Defect> = { success: true, data: { ...defect, status: 'RESOLVED' } };
        return HttpResponse.json(response);
      }),
    );

    renderControl(defect);

    fireEvent.change(screen.getByLabelText('다른 상태로 변경'), { target: { value: 'RESOLVED' } });

    expect(await screen.findByRole('dialog', { name: '상태 변경 사유 입력' })).not.toBeNull();

    fireEvent.change(screen.getByLabelText('사유'), { target: { value: '시공사 조기 완료 확인' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '상태 변경 사유 입력' })).toBeNull());
  });

  it('취소를 누르면 모달이 닫히고 API를 호출하지 않는다', async () => {
    let called = false;
    server.use(
      http.patch('/api/defects/:id/status', () => {
        called = true;
        return HttpResponse.json({ success: true, data: mockDefects[0] } satisfies ApiResponse<Defect>);
      }),
    );

    renderControl({ ...mockDefects[0], status: 'CONFIRMED' });
    fireEvent.change(screen.getByLabelText('다른 상태로 변경'), { target: { value: 'DETECTED' } });

    await screen.findByRole('dialog', { name: '상태 변경 사유 입력' });
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByRole('dialog', { name: '상태 변경 사유 입력' })).toBeNull();
    expect(called).toBe(false);
  });

  it('실패 시 모달을 닫지 않고 모달 내부(오버레이에 가리지 않는 위치)에 오류를 보여준다', async () => {
    server.use(
      http.patch('/api/defects/:id/status', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_INPUT', message: '상태를 되돌리거나 건너뛰려면 사유가 필요합니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderControl({ ...mockDefects[0], status: 'CONFIRMED' });
    fireEvent.change(screen.getByLabelText('다른 상태로 변경'), { target: { value: 'DETECTED' } });

    const dialog = await screen.findByRole('dialog', { name: '상태 변경 사유 입력' });
    fireEvent.change(screen.getByLabelText('사유'), { target: { value: '오탐 재확인 필요' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    // Modal이 document.body에 portal되는 풀스크린 오버레이라, 다이얼로그 바깥에 렌더링된 오류는
    // 실제로는 사용자 눈에 보이지 않는다 — within(dialog)로 반드시 다이얼로그 내부를 검증한다.
    expect(
      await within(dialog).findByText('상태 변경에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
    ).not.toBeNull();
    expect(screen.getByRole('dialog', { name: '상태 변경 사유 입력' })).not.toBeNull();
  });

  it('제출 중에는 확인 버튼이 비활성화되고 "처리 중..."으로 바뀐다', async () => {
    let resolveRequest: (() => void) | undefined;
    server.use(
      http.patch('/api/defects/:id/status', async () => {
        await new Promise<void>((resolve) => {
          resolveRequest = resolve;
        });
        const response: ApiResponse<Defect> = {
          success: true,
          data: { ...mockDefects[0], status: 'DETECTED' },
        };
        return HttpResponse.json(response);
      }),
    );

    renderControl({ ...mockDefects[0], status: 'CONFIRMED' });
    fireEvent.change(screen.getByLabelText('다른 상태로 변경'), { target: { value: 'DETECTED' } });

    await screen.findByRole('dialog', { name: '상태 변경 사유 입력' });
    fireEvent.change(screen.getByLabelText('사유'), { target: { value: '오탐 재확인 필요' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    const pendingButton = (await screen.findByRole('button', { name: '처리 중...' })) as HTMLButtonElement;
    expect(pendingButton.disabled).toBe(true);

    resolveRequest?.();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '상태 변경 사유 입력' })).toBeNull());
  });
});
