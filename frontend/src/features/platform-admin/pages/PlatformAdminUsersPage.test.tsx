// @vitest-environment jsdom
// PlatformAdminUsersPage 통합 테스트 — features/admin/pages/AdminUsersPage.test.tsx(#405)를
// 그대로 옮긴 것(#577). 실제 usePlatformAdminUsers 훅 + MSW platformAdminUserHandlers를 통해
// 목록 렌더·필터 조회·페이지네이션·행 액션 안내를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { platformAdminUserHandlers } from '../api/platformAdminUserApi.handlers';
import { STATS_CARD_TEST_ID } from '../components/AdminUserStatsCard';
import { PlatformAdminUsersPage } from './PlatformAdminUsersPage';

const server = setupServer(...platformAdminUserHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <PlatformAdminUsersPage />
    </QueryClientProvider>,
  );
}

describe('PlatformAdminUsersPage (통합 테스트)', () => {
  it('목록을 불러와 사용자·이메일·배지·날짜를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('김지수')).toBeTruthy();
    expect(screen.getByText('jisoo.kim@example.com')).toBeTruthy();
    expect(screen.getByText('2023-10-12 00:00:00')).toBeTruthy();
    expect(screen.getAllByText('관리자').length).toBeGreaterThan(0);
  });

  it('접속 이력이 없는 사용자는 최근 접속 셀을 "-"로 표시한다', async () => {
    renderPage();

    await screen.findByText('김지수');
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));

    expect(await screen.findByText('서도윤')).toBeTruthy();
    expect(screen.getAllByText('-').length).toBeGreaterThan(0);
  });

  it('첫 페이지에는 페이지 크기(10)만큼만 표시한다', async () => {
    renderPage();

    await screen.findByText('김지수');
    expect(screen.queryByText('한예린')).toBeNull();
  });

  it('상태 필터를 걸면 해당 상태의 사용자만 조회한다', async () => {
    renderPage();

    await screen.findByText('김지수');
    fireEvent.change(screen.getByLabelText('상태 필터'), { target: { value: 'SUSPENDED' } });

    await waitFor(() => {
      expect(screen.queryByText('김지수')).toBeNull();
    });
    expect(screen.getByText('이하늘')).toBeTruthy();
  });

  it('검색 결과가 없으면 빈 안내를 보여준다', async () => {
    renderPage();

    await screen.findByText('김지수');
    fireEvent.change(screen.getByLabelText('이름·이메일 검색'), {
      target: { value: '존재하지않는사용자' },
    });

    expect(await screen.findByText('조건에 맞는 사용자가 없습니다')).toBeTruthy();
  });

  it('조회 실패 시 에러 메시지와 다시 시도 버튼을 노출한다', async () => {
    server.use(
      http.get('/api/platform-admin/users', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });

  it('조회 전에는 통계 카드가 각 지표를 0으로 표시한다', () => {
    renderPage();

    const card = within(screen.getByTestId(STATS_CARD_TEST_ID));
    for (const label of ['전체 회원', '활성', '정지', '이번 주 신규']) {
      expect(card.getByText(label).nextElementSibling?.textContent).toContain('0');
    }
  });

  it('조회 실패 시 통계 카드는 0이 아니라 "-"로 표시한다', async () => {
    server.use(
      http.get('/api/platform-admin/users', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    await screen.findByRole('alert');
    const card = within(screen.getByTestId(STATS_CARD_TEST_ID));
    for (const label of ['전체 회원', '활성', '정지', '이번 주 신규']) {
      expect(card.getByText(label).nextElementSibling?.textContent).toBe('-');
    }
  });

  it('역할 변경 메뉴는 역할 변경 모달을 열고, 저장하면 실제로 역할이 바뀐다', async () => {
    renderPage();

    await screen.findByText('김지수');
    fireEvent.click(screen.getByRole('button', { name: '김지수 관리 메뉴' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '역할 변경' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('역할 변경')).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('radio', { name: /점검자/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '변경 내용 저장' }));

    expect(await screen.findByRole('status')).toBeTruthy();
    expect(screen.getByText(/역할이 점검자\(으\)로 변경되었습니다/)).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('상태 변경 메뉴는 상태 변경 모달을 열고, 저장하면 실제로 상태가 바뀐다', async () => {
    renderPage();

    await screen.findByText('김지수');
    fireEvent.click(screen.getByRole('button', { name: '김지수 관리 메뉴' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '상태 변경' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('상태 변경')).toBeTruthy();
    const saveButton = within(dialog).getByRole('button', {
      name: '변경 내용 저장',
    }) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);

    fireEvent.click(within(dialog).getByRole('radio', { name: /정지/ }));
    fireEvent.click(saveButton);

    expect(await screen.findByRole('status')).toBeTruthy();
    expect(screen.getByText(/상태가 정지\(으\)로 변경되었습니다/)).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
