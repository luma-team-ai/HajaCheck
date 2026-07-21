// @vitest-environment jsdom
// NotificationCenter 통합 테스트 — 실제 useNotifications/useMarkNotificationsAsRead 훅 +
// MSW notificationHandlers를 통해 벨 토글·목 데이터 렌더·필터 전환·모두읽음을 검증한다.
// 벨 버튼 자체는 shared Header(이은석 소유)에 있지만, 그 토글 배선(open 상태를 부모가 들고
// onNotificationClick으로 내려주는 방식)은 AppShellRoute와 동일한 구조라 여기서는 그 최소 재현으로
// 검증한다(AppShellRoute.test.tsx가 실제 Header까지 포함한 배선을 별도로 검증).
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
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

  // react-reviewer P1-2: PATCH가 실패하면 onMutate의 낙관적 갱신(전부 읽음 처리)이 onError에서
  // 원복되어야 한다. 이전에는 mutationFn이 Promise.allSettled를 써서 개별 PATCH가 다 실패해도 항상
  // fulfilled 처리돼 onError가 호출되지 않고(데드코드), 낙관적 갱신이 영구 고정되는 버그가 있었다.
  it('읽음 처리 PATCH가 실패하면 낙관적으로 반영했던 미읽음 수가 원복된다(react-reviewer P1-2)', async () => {
    // PATCH 응답을 게이트로 걸어(resolvePatch 호출 전까지 대기) 낙관적 갱신(0)과 실패 후 롤백(3)을
    // 서로 다른 시점으로 강제 분리한다 — 게이트 없이 즉시 실패시키면 onMutate의 setQueryData(0)와
    // onError의 setQueryData(3)가 React 18 배칭으로 같은 커밋에 묶여 중간 "0" 프레임이 관측되지 않고,
    // 그러면 waitFor의 첫 동기 체크가 클릭 직후의 "3"(아직 안 바뀐 값)을 그대로 통과시켜버려
    // 롤백을 실제로 검증하지 못하는 거짓 통과가 생긴다.
    let resolvePatch!: () => void;
    const patchGate = new Promise<void>((resolve) => {
      resolvePatch = resolve;
    });
    server.use(
      http.patch('/api/notifications/:id/read', async () => {
        await patchGate;
        return HttpResponse.json({ success: false }, { status: 500 });
      }),
    );

    renderHarness();
    fireEvent.click(screen.getByRole('button', { name: '벨' }));
    await screen.findByText('미읽음 3');

    fireEvent.click(screen.getByRole('button', { name: '모두 읽음' }));

    // 1) PATCH가 아직 게이트에 막혀 있는 동안 onMutate의 낙관적 갱신(0)이 실제로 반영되는지 확인.
    expect(await screen.findByText('미읽음 0')).toBeTruthy();

    // 2) 게이트를 풀어 PATCH를 실패시키고, onError가 원래 값(3)으로 되돌리는지 확인.
    resolvePatch();
    await waitFor(() => {
      expect(screen.getByText('미읽음 3')).toBeTruthy();
    });
  });

  // PR머신 P1: BE NotificationType이 constants.ts의 4종 밖의 값을 내려주면(예: enum이 FE 배포보다
  // 먼저 확장된 경우) NOTIFICATION_TYPE_META 조회가 undefined가 되어 렌더링이 크래시했다 — 레포 전체에
  // ErrorBoundary가 없어 AppShellRoute(로그인 후 전체 공통 셸)까지 죽을 수 있었다.
  it('알 수 없는 notification.type이 섞여 있어도 크래시 없이 나머지 알림을 정상 렌더링한다(PR머신 P1)', async () => {
    server.use(
      http.get('/api/notifications', () =>
        HttpResponse.json({
          success: true,
          data: [
            { id: 1, type: 'ANALYSIS_DONE', payload: null, isRead: false, createdAt: new Date().toISOString() },
            { id: 99, type: 'UNKNOWN_TYPE', payload: null, isRead: false, createdAt: new Date().toISOString() },
          ],
        }),
      ),
    );

    renderHarness();
    fireEvent.click(screen.getByRole('button', { name: '벨' }));

    // 알려진 항목은 정상 렌더링되고, 폴백 메타("새 알림")로 표시된 미지의 항목도 함께 렌더링된다 —
    // 둘 다 미읽음이라 unreadCount(2)에도 반영된다.
    expect(await screen.findByText('AI 분석 완료')).toBeTruthy();
    expect(screen.getByText('새 알림')).toBeTruthy();
    expect(screen.getByText('미읽음 2')).toBeTruthy();
  });
});
