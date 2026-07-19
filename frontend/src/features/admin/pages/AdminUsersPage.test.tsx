// @vitest-environment jsdom
// AdminUsersPage 통합 테스트 — 실제 useAdminUsers 훅 + MSW adminHandlers를 통해
// 목록 렌더·필터 조회·페이지네이션·행 액션 안내를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { adminHandlers } from '../api/adminApi.handlers';
import { STATS_CARD_TEST_ID } from '../components/AdminUserStatsCard';
import { AdminUsersPage } from './AdminUsersPage';

const server = setupServer(...adminHandlers);

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
      <AdminUsersPage />
    </QueryClientProvider>,
  );
}

describe('AdminUsersPage (통합 테스트)', () => {
  it('목록을 불러와 사용자·이메일·배지·날짜를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('김지수')).toBeTruthy();
    expect(screen.getByText('jisoo.kim@example.com')).toBeTruthy();
    // 가입일 포맷(YYYY.MM.DD)
    expect(screen.getByText('2023.10.12')).toBeTruthy();
    // 역할·플랜 배지 라벨
    expect(screen.getAllByText('관리자').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Enterprise').length).toBeGreaterThan(0);
  });

  // DB users 테이블에는 초대 대기(PENDING) 상태가 없다 — user_status_type = ACTIVE/SUSPENDED뿐이고
  // name/created_at도 NOT NULL이라 "대기 중" 표시는 성립하지 않는다(#378). 대신 실제로 비어 있을 수
  // 있는 값(활성 구독 없음 plan=null, 미접속 last_login_at=null)이 "-"로 표시되는지 확인한다.
  it('활성 구독이 없거나 접속 이력이 없는 사용자는 해당 셀을 "-"로 표시한다', async () => {
    renderPage();

    // 배시온(id 5, plan=null)은 1페이지에 있다 — 미접속 사례(서도윤 id 14)는 2페이지라 여기선 제외
    expect(await screen.findByText('배시온')).toBeTruthy();
    expect(screen.getAllByText('-').length).toBeGreaterThan(0);
  });

  it('첫 페이지에는 페이지 크기(10)만큼만 표시한다', async () => {
    renderPage();

    await screen.findByText('김지수');
    // 11번째 사용자는 2페이지에 있어야 한다
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
      http.get('/api/admin/users', () =>
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

  // 통계 카드는 data가 없어도 사라지지 않는다 — 카드가 통째로 없어지면 "미구현"으로 오해된다.
  it('조회 전에는 통계 카드가 각 지표를 0으로 표시한다', () => {
    renderPage();

    // "활성"·"정지"는 상태 필터 옵션에도 있어 카드 범위로 좁혀 조회한다
    const card = within(screen.getByTestId(STATS_CARD_TEST_ID));
    // 지표별 라벨↔값 쌍을 확인한다 — 0의 개수만 세면 지표가 늘 때 의도와 무관하게 깨진다
    for (const label of ['전체 회원', '활성', '정지', '이번 주 신규']) {
      // getByText(label) → <dt>, 그 다음 형제가 값을 담은 <dd>
      expect(card.getByText(label).nextElementSibling?.textContent).toContain('0');
    }
  });

  it('조회 실패 시 통계 카드는 0이 아니라 "-"로 표시한다', async () => {
    server.use(
      http.get('/api/admin/users', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    await screen.findByRole('alert');
    const card = within(screen.getByTestId(STATS_CARD_TEST_ID));
    // "회원 0명"은 사실 주장이라, 집계를 못 가져온 상태와 구분돼야 한다
    for (const label of ['전체 회원', '활성', '정지', '이번 주 신규']) {
      expect(card.getByText(label).nextElementSibling?.textContent).toBe('-');
    }
  });

  it('행 액션 메뉴는 아직 준비 중임을 안내한다', async () => {
    renderPage();

    await screen.findByText('김지수');
    fireEvent.click(screen.getByRole('button', { name: '김지수 관리 메뉴' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '권한 변경' }));

    expect(await screen.findByRole('status')).toBeTruthy();
    expect(screen.getByText(/권한 변경은 준비 중입니다/)).toBeTruthy();
  });
});
