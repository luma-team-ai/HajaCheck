// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../../features/auth/store/authStore';
import type { User } from '../../../features/auth/types';
import type { ApiResponse } from '../../api/types';
import { Sidebar } from './Sidebar';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
};

let logoutCallCount = 0;

const server = setupServer(
  http.post('/api/auth/logout', () => {
    logoutCallCount += 1;
    const success: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(success);
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
});
afterAll(() => server.close());

function renderSidebar() {
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Sidebar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { queryClient };
}

describe('Sidebar', () => {
  beforeEach(() => {
    logoutCallCount = 0;
    useAuthStore.setState({ user: mockUser });
  });

  it('로그아웃 버튼 클릭 시 logout API가 호출되고 authStore가 정리된다', async () => {
    renderSidebar();

    fireEvent.click(screen.getByText('로그아웃'));

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(logoutCallCount).toBe(1);
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('접힌 상태에서는 로그아웃 버튼에 title 툴팁이 붙는다', () => {
    renderSidebar();

    expect(document.querySelector('.sidebar-logout-btn')?.getAttribute('title')).toBeNull();

    fireEvent.click(screen.getByLabelText('사이드바 접기'));

    expect(document.querySelector('.sidebar-logout-btn')?.getAttribute('title')).toBe('로그아웃');
  });
});
