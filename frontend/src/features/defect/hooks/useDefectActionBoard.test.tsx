// @vitest-environment jsdom
// useDefectActionBoard — 조치 보드 드롭 오케스트레이션(HAJA-349/#630). dnd-kit의 실제 포인터/키보드
// 드래그를 jsdom에서 재현하는 대신, DndContext가 그대로 호출할 handleDragEnd를 직접 호출해 정방향/
// 역행/실패 롤백 로직을 검증한다(DragEndEvent는 active.id/over.id만 읽으므로 최소 셰이프로 구성).
import type { DragEndEvent } from '@dnd-kit/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { mockDefects } from '../mocks/defect.mock';
import type { Defect } from '../types';
import { resolveDropKind, useDefectActionBoard } from './useDefectActionBoard';

const server = setupServer(...defectHandlers);
// PATCH 핸들러가 mockDefects를 in-place로 변경한다 — 테스트 간 오염 방지를 위해 스냅샷 복원한다
// (DefectDetailPage.test.tsx와 동일 패턴).
const mockDefectsSnapshot = JSON.parse(JSON.stringify(mockDefects)) as typeof mockDefects;

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  mockDefectsSnapshot.forEach((snapshot, index) => {
    Object.assign(mockDefects[index], snapshot);
  });
});
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function dragEndEvent(activeId: number, overId: string | null): DragEndEvent {
  return {
    active: { id: activeId },
    over: overId ? { id: overId } : null,
  } as unknown as DragEndEvent;
}

describe('resolveDropKind', () => {
  it('같은 컬럼에 놓으면 noop이다', () => {
    expect(resolveDropKind('DETECTED', 'DETECTED')).toBe('noop');
  });

  it('정방향 1단계 이동이면 forward다', () => {
    expect(resolveDropKind('DETECTED', 'CONFIRMED')).toBe('forward');
    expect(resolveDropKind('ACTION_PENDING', 'IN_PROGRESS')).toBe('forward');
  });

  it('역행 이동이면 reason-required다', () => {
    expect(resolveDropKind('ACTION_PENDING', 'DETECTED')).toBe('reason-required');
  });

  it('건너뛰기 이동이면 reason-required다', () => {
    expect(resolveDropKind('DETECTED', 'IN_PROGRESS')).toBe('reason-required');
  });

  it('RESOLVED에서의 모든 이동은 reason-required다(백엔드가 결국 409로 거부)', () => {
    expect(resolveDropKind('RESOLVED', 'IN_PROGRESS')).toBe('reason-required');
  });
});

describe('useDefectActionBoard', () => {
  it('필터에 해당하는 하자를 상태별 컬럼으로 묶어 반환한다', async () => {
    const { result } = renderHook(() => useDefectActionBoard({}), { wrapper });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    const detected = result.current.columns.find((column) => column.status === 'DETECTED');
    const actionPending = result.current.columns.find((column) => column.status === 'ACTION_PENDING');
    expect(detected?.defects.map((defect) => defect.id).sort()).toEqual([2, 3]);
    expect(actionPending?.defects.map((defect) => defect.id)).toEqual([1]);
  });

  it('정방향 1단계 드롭은 사유 없이 즉시 상태 전이를 호출한다', async () => {
    const { result } = renderHook(() => useDefectActionBoard({}), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // id=2는 DETECTED — 다음 단계 CONFIRMED로 드롭.
    act(() => {
      result.current.handleDragEnd(dragEndEvent(2, 'CONFIRMED'));
    });

    // 모달을 띄우지 않고 바로 전이되어야 한다.
    expect(result.current.reasonRequest).toBeNull();

    await waitFor(() => {
      const confirmed = result.current.columns.find((column) => column.status === 'CONFIRMED');
      expect(confirmed?.defects.some((defect) => defect.id === 2)).toBe(true);
    });
  });

  it('역행 드롭은 사유 모달을 띄우고, 사유 제출 후에만 상태 전이를 호출한다', async () => {
    const { result } = renderHook(() => useDefectActionBoard({}), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // id=1은 ACTION_PENDING — 이전 단계 DETECTED로 드롭(역행).
    act(() => {
      result.current.handleDragEnd(dragEndEvent(1, 'DETECTED'));
    });

    expect(result.current.reasonRequest).not.toBeNull();
    expect(result.current.reasonRequest?.defect.id).toBe(1);
    expect(result.current.reasonRequest?.targetStatus).toBe('DETECTED');

    // 사유 제출 전에는 카드가 그대로여야 한다(아직 API를 호출하지 않음).
    const actionPendingBefore = result.current.columns.find((column) => column.status === 'ACTION_PENDING');
    expect(actionPendingBefore?.defects.some((defect) => defect.id === 1)).toBe(true);

    act(() => {
      result.current.submitReason('점검자 재확인 요청으로 되돌림');
    });

    expect(result.current.reasonRequest).toBeNull();

    await waitFor(() => {
      const detected = result.current.columns.find((column) => column.status === 'DETECTED');
      expect(detected?.defects.some((defect) => defect.id === 1)).toBe(true);
    });
  });

  it('드롭 API가 실패하면(409) 카드를 원래 컬럼으로 롤백하고 에러 메시지를 노출한다', async () => {
    server.use(
      http.patch('/api/defects/:id/status', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_STATE_TRANSITION', message: '현재 상태에서는 처리할 수 없는 요청입니다.' },
        };
        return HttpResponse.json(failure, { status: 409 });
      }),
    );

    const { result } = renderHook(() => useDefectActionBoard({}), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // id=2는 DETECTED — 정방향 드롭이라 즉시 낙관적으로 이동했다가 실패로 롤백되어야 한다.
    act(() => {
      result.current.handleDragEnd(dragEndEvent(2, 'CONFIRMED'));
    });

    await waitFor(() => {
      expect(result.current.dropError).toBe('현재 상태에서는 처리할 수 없는 요청입니다.');
    });

    const detected = result.current.columns.find((column) => column.status === 'DETECTED');
    const confirmed = result.current.columns.find((column) => column.status === 'CONFIRMED');
    expect(detected?.defects.some((defect: Defect) => defect.id === 2)).toBe(true);
    expect(confirmed?.defects.some((defect: Defect) => defect.id === 2)).toBe(false);
  });

  it('같은 컬럼에 드롭하면 아무 것도 호출하지 않는다', async () => {
    const { result } = renderHook(() => useDefectActionBoard({}), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    act(() => {
      result.current.handleDragEnd(dragEndEvent(2, 'DETECTED'));
    });

    expect(result.current.reasonRequest).toBeNull();
    expect(result.current.dropError).toBeNull();
  });
});
