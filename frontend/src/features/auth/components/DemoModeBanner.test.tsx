// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DemoModeBanner } from './DemoModeBanner';

afterEach(() => cleanup());

describe('DemoModeBanner', () => {
  it('visible=true면 데모 모드 안내 문구를 렌더한다', () => {
    render(<DemoModeBanner visible />);

    expect(screen.getByRole('status').textContent).toBe('데모 모드 — 데이터는 매일 초기화됩니다');
  });

  it('visible=false면 아무것도 렌더하지 않는다', () => {
    render(<DemoModeBanner visible={false} />);

    expect(screen.queryByRole('status')).toBeNull();
  });
});
