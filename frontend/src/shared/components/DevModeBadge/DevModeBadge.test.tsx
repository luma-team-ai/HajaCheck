// @vitest-environment jsdom
// #302/#311 회귀 방지 — "지금 뭘 보고 있는지"가 화면에 드러나는지 고정한다.
// 이 배지가 없던 탓에 ① MSW가 실 세션을 가로채 로그인이 깨졌을 때 원인 추적에 오래 걸렸고
// ② 80이 dist(사진)인지 vite(거울)인지 화면만 봐선 알 수 없었다.
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DevModeBadge } from './DevModeBadge';

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe('DevModeBadge', () => {
  it('dev 빌드 + MSW 꺼짐이면 "DEV"만 표시한다(vite 거울 모드)', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_ENABLE_MSW', 'false');

    render(<DevModeBadge />);

    // jest-dom 매처는 이 프로젝트에 setup되어 있지 않아 기본 매처로 검증
    const badge = screen.getByRole('status');
    expect(badge.textContent).toContain('DEV');
    expect(badge.textContent).not.toContain('MSW');
  });

  it('dev 빌드 + MSW 켜짐이면 목 모드를 함께 경고한다', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_ENABLE_MSW', undefined);

    render(<DevModeBadge />);

    expect(screen.getByRole('status').textContent).toContain('MSW');
  });

  // 프로덕션 빌드에서 dist(사진)와 실제 운영은 산출물이 동일해 클라이언트에서 구분할 수 없다.
  // 회색 배지라도 렌더하면 운영 사이트에 노출되므로, "배지 부재"가 곧 dist 신호다.
  // 사용자에게 보이는 사고라 반드시 막는다.
  it('프로덕션 빌드(!DEV)에서는 MSW 값과 무관하게 렌더하지 않는다', () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_ENABLE_MSW', 'true');

    render(<DevModeBadge />);

    expect(screen.queryByRole('status')).toBeNull();
  });
});
