// @vitest-environment jsdom
// NotificationCenter 통합 테스트 — 실제 useNotifications/useMarkNotificationsAsRead 훅 +
// MSW notificationHandlers를 통해 벨 토글·목 데이터 렌더·필터 전환·모두읽음을 검증한다.
// 벨 버튼 자체는 shared Header(이은석 소유)에 있지만, 그 토글 배선(open 상태를 부모가 들고
// onNotificationClick으로 내려주는 방식)은 AppShellRoute와 동일한 구조라 여기서는 그 최소 재현으로
// 검증한다(AppShellRoute.test.tsx가 실제 Header까지 포함한 배선을 별도로 검증).
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { notificationHandlers } from '../api/notificationApi.handlers';
import { NotificationCenter } from './NotificationCenter';

const server = setupServer(...notificationHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen((prev) => !prev)}>
        벨
      </button>
      <NotificationCenter open={open} onClose={() => setOpen(false)} enabled />
    </>
  );
}

function renderHarness() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>,
  );
}

describe('NotificationCenter', () => {
  it('벨을 클릭하면 드롭다운이 열리고 목 데이터를 렌더링한다', async () => {
    renderHarness();

    expect(screen.queryByRole('menu', { name: '알림' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '벨' }));

    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();
    // 목 데이터 5건 중 분석 완료(ANALYSIS_DONE) 2건이 같은 고정 제목을 쓴다(constants.ts 주석 참고)
    expect((await screen.findAllByText('AI 분석 완료')).length).toBe(2);
    expect(screen.getByText('검수 대기 알림')).toBeTruthy();
    // 목 데이터 5건 중 미읽음 3건(id 1,2,3)
    expect(screen.getByText('미읽음 3')).toBeTruthy();
  });

  it('카테고리 필터를 클릭하면 해당 카테고리 항목만 보인다', async () => {
    renderHarness();
    fireEvent.click(screen.getByRole('button', { name: '벨' }));
    await screen.findByText('검수 대기 알림');

    fireEvent.click(screen.getByRole('button', { name: '검수' }));

    expect(screen.getByText('검수 대기 알림')).toBeTruthy();
    expect(screen.queryByText('AI 분석 완료')).toBeNull();
  });

  it('모두 읽음을 클릭하면 미읽음 수가 0이 된다', async () => {
    renderHarness();
    fireEvent.click(screen.getByRole('button', { name: '벨' }));
    await screen.findByText('미읽음 3');

    fireEvent.click(screen.getByRole('button', { name: '모두 읽음' }));

    expect(await screen.findByText('미읽음 0')).toBeTruthy();
  });
});
