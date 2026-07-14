// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { TopNavigation } from './TopNavigation';

afterEach(cleanup);

describe('TopNavigation', () => {
  it('기본 메뉴 항목과 로그인 링크를 렌더링한다', () => {
    render(<TopNavigation />, { wrapper: MemoryRouter });

    expect(screen.getByText('대시보드')).not.toBeNull();
    expect(screen.getByText('AI 분석')).not.toBeNull();
    expect(screen.getByText('로그인').closest('a')?.getAttribute('href')).toBe('/login');
  });

  it('navItems prop으로 메뉴 항목을 교체할 수 있다', () => {
    render(<TopNavigation navItems={[{ label: '커스텀 메뉴', href: '/custom' }]} />, {
      wrapper: MemoryRouter,
    });

    expect(screen.getByText('커스텀 메뉴')).not.toBeNull();
    expect(screen.queryByText('대시보드')).toBeNull();
  });
});
