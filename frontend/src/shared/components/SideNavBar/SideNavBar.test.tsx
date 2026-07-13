// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SideNavBar } from './SideNavBar';

afterEach(cleanup);

describe('SideNavBar', () => {
  it('기본 메뉴 항목을 렌더링하고 activeHref에 해당하는 링크를 표시한다', () => {
    render(<SideNavBar activeHref="/defects" />);

    expect(screen.getByText('대시보드')).not.toBeNull();
    expect(screen.getByText('하자 관리').getAttribute('aria-current')).toBe('page');
  });

  it('user 정보가 있으면 이름/플랜과 로그아웃 버튼을 표시한다', () => {
    const handleLogout = vi.fn();
    render(<SideNavBar user={{ name: '김관리', plan: 'Standard' }} onLogout={handleLogout} />);

    expect(screen.getByText('김관리')).not.toBeNull();
    expect(screen.getByText('Standard')).not.toBeNull();
    expect(screen.getByText('로그아웃')).not.toBeNull();
  });
});
