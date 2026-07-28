// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AdminUser } from '../types';
import { SkillChangeModal } from './SkillChangeModal';

afterEach(cleanup);

const user: AdminUser = {
  id: 1,
  name: '상담원',
  email: 'counselor@haja.com',
  role: 'COUNSELOR',
  plan: null,
  companyId: null,
  companyName: null,
  joinedAt: '2026-01-01T00:00:00',
  lastAccessAt: null,
  status: 'ACTIVE',
};

describe('SkillChangeModal', () => {
  it('보유 스킬이 1개면 축소 경고를 표시하지 않는다', () => {
    render(
      <SkillChangeModal
        user={user}
        currentSkill="USAGE"
        currentSkills={['USAGE']}
        isLoadingCurrentSkill={false}
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        isSubmitting={false}
      />,
    );

    expect(screen.queryByRole('alert')).toBeNull();
  });

  // 다중 스킬 상담원을 라디오(단일 선택)로 저장하면 나머지 배정이 경고 없이 사라진다는
  // PR머신 2차 검토 P2 지적 — 저장 전에 현재 전체 목록과 축소 사실을 명시해야 한다.
  it('보유 스킬이 2개 이상이면 저장 시 하나로 교체된다는 경고를 표시한다', () => {
    render(
      <SkillChangeModal
        user={user}
        currentSkill="USAGE"
        currentSkills={['USAGE', 'ANALYSIS_RESULT']}
        isLoadingCurrentSkill={false}
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        isSubmitting={false}
      />,
    );

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('현재 배정된 스킬이 2개입니다');
    expect(alert.textContent).toContain('이용 방법');
    expect(alert.textContent).toContain('분석 결과');
  });

  it('조회 중에는 경고를 표시하지 않는다', () => {
    render(
      <SkillChangeModal
        user={user}
        currentSkill={null}
        currentSkills={['USAGE', 'ANALYSIS_RESULT']}
        isLoadingCurrentSkill
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        isSubmitting={false}
      />,
    );

    expect(screen.queryByRole('alert')).toBeNull();
  });
});
