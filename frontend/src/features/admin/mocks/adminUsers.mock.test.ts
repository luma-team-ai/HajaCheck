import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// #398 — lastAccessAt은 모듈 로드 시각에 한 번만 굳는 값이 아니라, 읽을 때마다 "지금으로부터
// n만큼 전"으로 재계산돼야 한다. 시스템 시계를 앞으로 돌려도 formatRelativeAccess 라벨이
// 그대로 유지되는지로 검증한다(모듈을 매 테스트 다시 import해 캐시 없이 확인).
describe('mockAdminUsers lastAccessAt', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('실시간이 흘러도 접근 시점의 Date.now() 기준으로 재계산된다', async () => {
    const { mockAdminUsers } = await import('./adminUsers.mock');
    const HOUR = 60 * 60 * 1000;

    const firstReading = new Date(mockAdminUsers[0].lastAccessAt as string).getTime();
    const before = Date.now();
    expect(before - firstReading).toBeCloseTo(2 * HOUR, -2);

    vi.useFakeTimers();
    vi.setSystemTime(before + 5 * HOUR);

    const secondReading = new Date(mockAdminUsers[0].lastAccessAt as string).getTime();
    // 시스템 시계가 5시간 흘러도 "지금으로부터 2시간 전"이라는 오프셋 자체는 그대로 유지된다
    expect(Date.now() - secondReading).toBeCloseTo(2 * HOUR, -2);
  });

  it('미접속 사용자(lastAccessOffsetMs=null)는 계속 null이다', async () => {
    const { mockAdminUsers } = await import('./adminUsers.mock');
    const neverLoggedIn = mockAdminUsers.find((user) => user.id === 14);

    expect(neverLoggedIn?.lastAccessAt).toBeNull();
  });
});
