// @vitest-environment jsdom
// #302 회귀 방지 — 목 모드가 화면에 드러나는지 고정한다. 이 배지가 없던 탓에 MSW가 실 세션을
// 가로채 로그인이 깨졌을 때 원인을 찾는 데 오래 걸렸다(서버 로그엔 단서가 없어 백엔드를 뒤졌다).
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MockModeBadge } from './MockModeBadge';

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe('MockModeBadge', () => {
  it('MSW가 켜지는 조건(DEV + VITE_ENABLE_MSW 미설정)이면 배지를 노출한다', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_ENABLE_MSW', undefined);

    render(<MockModeBadge />);

    // jest-dom 매처(toHaveTextContent)는 이 프로젝트에 setup되어 있지 않아 기본 매처로 검증
    expect(screen.getByRole('status').textContent).toContain('MSW 목 모드');
  });

  it('VITE_ENABLE_MSW=false(실 백엔드 연동)면 배지를 노출하지 않는다', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_ENABLE_MSW', 'false');

    render(<MockModeBadge />);

    expect(screen.queryByRole('status')).toBeNull();
  });

  // 프로덕션 노출은 사용자에게 보이는 사고라 반드시 막는다.
  it('프로덕션 빌드(!DEV)에서는 VITE_ENABLE_MSW 값과 무관하게 노출하지 않는다', () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_ENABLE_MSW', 'true');

    render(<MockModeBadge />);

    expect(screen.queryByRole('status')).toBeNull();
  });
});
