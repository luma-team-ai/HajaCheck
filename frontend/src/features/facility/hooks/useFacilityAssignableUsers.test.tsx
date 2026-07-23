// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
// 개별 핸들러가 아니라 실제 앱이 등록하는 전체 handlers 배열을 그대로 써야 한다 — 등록 순서
// 회귀(PR머신 P1: GET /api/facilities/:id 캐치올이 GET /api/facilities/assignable-users보다
// 앞에 있으면 리터럴 경로가 :id='assignable-users'로 먼저 매치되어 항상 404)를 이 테스트가 잡는다.
import { handlers } from '../../../mocks/handlers';
import { mockFacilityAssignableUsers } from '../mocks/facilityAssignee.mock';
import { useFacilityAssignableUsers } from './useFacilityAssignableUsers';

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderAssignableUsersHook() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useFacilityAssignableUsers(), { wrapper });
}

describe('useFacilityAssignableUsers', () => {
  // 회귀고정 — src/mocks/handlers.ts의 등록 순서가 다시 깨지면(facilityAssigneeHandlers가
  // GET /api/facilities/:id 캐치올보다 뒤로 밀리면) 이 테스트가 404로 실패한다(PR머신 P1, #629).
  it('전체 handlers 등록 순서에서 담당자 목록을 정상 조회한다', async () => {
    const { result } = renderAssignableUsersHook();

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(mockFacilityAssignableUsers);
  });
});
