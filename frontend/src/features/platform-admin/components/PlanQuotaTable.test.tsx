// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PlanQuotaUser } from '../planQuota.types';
import { PlanQuotaTable } from './PlanQuotaTable';

afterEach(cleanup);

function userRow(overrides: Partial<PlanQuotaUser>): PlanQuotaUser {
  return {
    id: 1,
    name: '김민준',
    email: 'test@haja.test',
    companyId: 10,
    companyName: '하자건설',
    plan: 'STANDARD',
    quotaUsed: 10,
    quotaLimit: 100,
    remainingDays: 30,
    status: 'ACTIVE',
    ...overrides,
  };
}

function renderTable(users: PlanQuotaUser[]) {
  render(<PlanQuotaTable users={users} isLoading={false} isError={false} onRetry={vi.fn()} />);
}

describe('PlanQuotaTable — 남은 기간 컬럼', () => {
  // #1104/HAJA-525 — V27로 결제 주기가 실체화되면서 remainingDays=null 의 의미가
  // "만료" 하나에서 "만료 또는 무기한(FREE)" 둘로 늘어났다. 만료 판정 축이 remainingDays 로
  // 남아 있으면 FREE 회사가 "만료됨(빨강) + 상태 활성"으로 모순 표시된다.
  it('FREE(무기한, remainingDays=null·status=ACTIVE)는 만료됨이 아니라 무기한으로 표시한다', () => {
    renderTable([userRow({ plan: 'FREE', remainingDays: null, status: 'ACTIVE' })]);

    expect(screen.getByText('무기한')).toBeTruthy();
    expect(screen.queryByText('만료됨')).toBeNull();
  });

  it('만료(status=EXPIRED)는 remainingDays 와 무관하게 만료됨으로 표시한다', () => {
    renderTable([userRow({ remainingDays: null, status: 'EXPIRED' })]);

    expect(screen.getByText('만료됨')).toBeTruthy();
    expect(screen.queryByText('무기한')).toBeNull();
  });

  it('유효한 유료 구독은 남은 일수를 그대로 표시한다', () => {
    renderTable([userRow({ remainingDays: 12, status: 'WARNING' })]);

    expect(screen.getByText('12일')).toBeTruthy();
    expect(screen.queryByText('만료됨')).toBeNull();
  });
});
