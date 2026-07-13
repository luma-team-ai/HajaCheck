// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SideNavBar } from './SideNavBar';

afterEach(cleanup);

describe('SideNavBar', () => {
  it('기본 메뉴 항목을 렌더링하고 activeHref에 해당하는 링크를 표시한다', () => {
    render(<SideNavBar activeHref="/defects" />);

    expect(screen.getByText('시설물 관리')).not.toBeNull();
    expect(screen.getByText('하자 관리').closest('a')?.getAttribute('aria-current')).toBe('page');
  });

  it('대시보드 그룹을 클릭하면 하위 항목이 펼쳐진다', () => {
    render(<SideNavBar />);

    expect(screen.queryByText('전체 시설물 현황')).toBeNull();

    fireEvent.click(screen.getByText('대시보드'));

    expect(screen.getByText('전체 시설물 현황')).not.toBeNull();
  });

  it('마이페이지 그룹을 클릭하면 하위 항목(내 정보 등)이 펼쳐진다', () => {
    render(<SideNavBar />);

    expect(screen.queryByText('내 정보')).toBeNull();

    fireEvent.click(screen.getByText('마이페이지'));

    expect(screen.getByText('내 정보')).not.toBeNull();
    expect(screen.getByText('내 플랜')).not.toBeNull();
  });

  it('isAdmin=true면 관리자 페이지 그룹과 ADMIN 배지가 표시되고, 펼치면 플랜·쿼터 관리가 보인다', () => {
    render(<SideNavBar isAdmin />);

    expect(screen.getByText('ADMIN')).not.toBeNull();
    expect(screen.getByText('관리자 페이지')).not.toBeNull();

    fireEvent.click(screen.getByText('관리자 페이지'));

    expect(screen.getByText('플랜·쿼터 관리')).not.toBeNull();
  });

  it('isAdmin이 아니면 관리자 페이지 그룹이 표시되지 않는다', () => {
    render(<SideNavBar />);

    expect(screen.queryByText('관리자 페이지')).toBeNull();
  });

  it('user 정보가 있으면 이름/플랜을 표시하고, 로그아웃 클릭 시 onLogout이 호출된다', () => {
    const handleLogout = vi.fn();
    render(<SideNavBar user={{ name: '김관리', plan: 'Standard' }} onLogout={handleLogout} />);

    expect(screen.getByText('김관리')).not.toBeNull();
    expect(screen.getByText('Standard')).not.toBeNull();

    fireEvent.click(screen.getByText('로그아웃'));
    expect(handleLogout).toHaveBeenCalledTimes(1);
  });

  it('접기 버튼 클릭 시 onCollapseToggle이 호출된다', () => {
    const handleToggle = vi.fn();
    render(<SideNavBar onCollapseToggle={handleToggle} />);

    fireEvent.click(screen.getByLabelText('사이드바 접기'));

    expect(handleToggle).toHaveBeenCalledTimes(1);
  });
});
