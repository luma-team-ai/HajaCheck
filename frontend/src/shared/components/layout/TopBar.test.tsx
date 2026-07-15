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
import { TopBar } from './TopBar';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
};

const server = setupServer(
  http.post('/api/auth/logout', () => {
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

function renderTopBar() {
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TopBar', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
  });

  it('로그인 사용자가 없으면 기존 제네릭 아이콘(SVG)을 그대로 표시한다', () => {
    renderTopBar();

    expect(screen.getByLabelText('내 프로필')).not.toBeNull();
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('로그인 사용자가 있으면 아바타에 이니셜을 표시하고, 클릭 시 이름/이메일 메뉴가 열린다', () => {
    useAuthStore.setState({ user: mockUser });
    renderTopBar();

    const avatarBtn = screen.getByLabelText('하자체크 담당자 프로필 메뉴');
    expect(avatarBtn.textContent).toBe('하');

    fireEvent.click(avatarBtn);

    expect(screen.getByRole('menu')).not.toBeNull();
    expect(screen.getByText('하자체크 담당자')).not.toBeNull();
    expect(screen.getByText('hajacheck@example.com')).not.toBeNull();
  });

  it('아바타 메뉴의 로그아웃 클릭 시 logout이 트리거되고 메뉴가 닫힌다', async () => {
    useAuthStore.setState({ user: mockUser });
    renderTopBar();

    fireEvent.click(screen.getByLabelText('하자체크 담당자 프로필 메뉴'));
    fireEvent.click(screen.getByRole('menuitem', { name: '로그아웃' }));

    expect(screen.queryByRole('menu')).toBeNull();

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('메뉴가 열린 상태에서 바깥을 클릭하면 메뉴가 닫힌다', () => {
    useAuthStore.setState({ user: mockUser });
    renderTopBar();

    fireEvent.click(screen.getByLabelText('하자체크 담당자 프로필 메뉴'));
    expect(screen.getByRole('menu')).not.toBeNull();

    fireEvent.mouseDown(document.body);

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('메뉴가 열린 상태에서 Escape 키를 누르면 메뉴가 닫힌다', () => {
    useAuthStore.setState({ user: mockUser });
    renderTopBar();

    fireEvent.click(screen.getByLabelText('하자체크 담당자 프로필 메뉴'));
    expect(screen.getByRole('menu')).not.toBeNull();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('메뉴가 열리면 첫 menuitem(로그아웃)으로 포커스가 이동한다', () => {
    useAuthStore.setState({ user: mockUser });
    renderTopBar();

    fireEvent.click(screen.getByLabelText('하자체크 담당자 프로필 메뉴'));

    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '로그아웃' }));
  });
});
