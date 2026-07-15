// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SideNavBar } from './SideNavBar';

afterEach(cleanup);

describe('SideNavBar', () => {
  it('기본 메뉴 항목을 렌더링하고, activeHref가 하위 항목이면 해당 그룹이 자동으로 펼쳐진다', () => {
    render(<SideNavBar activeHref="/defects/detail" />, { wrapper: MemoryRouter });

    expect(screen.getByText('시설물 관리')).not.toBeNull();
    expect(screen.getByText('하자 상세').closest('a')?.getAttribute('aria-current')).toBe('page');
  });

  it('대시보드 그룹을 클릭하면 하위 항목이 펼쳐진다', () => {
    render(<SideNavBar />, { wrapper: MemoryRouter });

    expect(screen.queryByText('전체 시설물 현황')).toBeNull();

    fireEvent.click(screen.getByText('대시보드'));

    expect(screen.getByText('전체 시설물 현황')).not.toBeNull();
  });

  it('시설물 관리 그룹을 클릭하면 하위 항목(시설물 목록/등록 등)이 펼쳐진다', () => {
    render(<SideNavBar />, { wrapper: MemoryRouter });

    expect(screen.queryByText('지도 뷰')).toBeNull();

    fireEvent.click(screen.getByText('시설물 관리'));

    expect(screen.getByText('시설물 목록/등록')).not.toBeNull();
    expect(screen.getByText('점검 주기 설정')).not.toBeNull();
    expect(screen.getByText('지도 뷰')).not.toBeNull();
  });

  it('통계와 설정은 하위 메뉴 없는 단일 링크로 렌더링된다', () => {
    render(<SideNavBar />, { wrapper: MemoryRouter });

    expect(screen.getByText('통계').closest('a')).not.toBeNull();
    expect(screen.getByText('설정').closest('a')).not.toBeNull();
  });

  it('마이페이지 그룹을 클릭하면 하위 항목(내 정보 등)이 펼쳐진다', () => {
    render(<SideNavBar />, { wrapper: MemoryRouter });

    expect(screen.queryByText('내 정보')).toBeNull();

    fireEvent.click(screen.getByText('마이페이지'));

    expect(screen.getByText('내 정보')).not.toBeNull();
    expect(screen.getByText('내 플랜')).not.toBeNull();
  });

  it('isAdmin=true면 관리자 페이지 그룹과 ADMIN 배지가 표시되고, 펼치면 플랜·쿼터 관리가 보인다', () => {
    render(<SideNavBar isAdmin />, { wrapper: MemoryRouter });

    expect(screen.getByText('ADMIN')).not.toBeNull();
    expect(screen.getByText('관리자 페이지')).not.toBeNull();

    fireEvent.click(screen.getByText('관리자 페이지'));

    expect(screen.getByText('플랜·쿼터 관리')).not.toBeNull();
  });

  it('isAdmin=true + activeHref가 다른 그룹의 하위 항목이어도, 수동으로 펼친 그룹이 스냅백되지 않는다', () => {
    render(<SideNavBar isAdmin activeHref="/defects/detail" />, { wrapper: MemoryRouter });

    // 마운트 시 activeHref(하자 관리)의 그룹이 자동으로 펼쳐진 상태
    expect(screen.getByText('하자 상세')).not.toBeNull();

    // 다른 그룹(대시보드)을 수동으로 펼치면, activeHref는 그대로여도 대시보드가 계속 펼쳐져 있어야 한다
    fireEvent.click(screen.getByText('대시보드'));

    expect(screen.getByText('전체 시설물 현황')).not.toBeNull();
  });

  it('isAdmin이 아니면 관리자 페이지 그룹이 표시되지 않는다', () => {
    render(<SideNavBar />, { wrapper: MemoryRouter });

    expect(screen.queryByText('관리자 페이지')).toBeNull();
  });

  it('user 정보가 있으면 이름/플랜을 표시하고, 로그아웃 클릭 시 onLogout이 호출된다', () => {
    const handleLogout = vi.fn();
    render(<SideNavBar user={{ name: '김관리', plan: 'Standard' }} onLogout={handleLogout} />, {
      wrapper: MemoryRouter,
    });

    expect(screen.getByText('김관리')).not.toBeNull();
    expect(screen.getByText('Standard')).not.toBeNull();

    fireEvent.click(screen.getByText('로그아웃'));
    expect(handleLogout).toHaveBeenCalledTimes(1);
  });

  it('접기 버튼 클릭 시 실제로 접혀서 라벨 텍스트가 사라지고, onCollapseToggle(true)이 호출된다', () => {
    const handleToggle = vi.fn();
    render(<SideNavBar onCollapseToggle={handleToggle} />, { wrapper: MemoryRouter });

    expect(screen.getByText('대시보드')).not.toBeNull();

    fireEvent.click(screen.getByLabelText('사이드바 접기'));

    expect(handleToggle).toHaveBeenCalledWith(true);
    expect(screen.queryByText('대시보드')).toBeNull();
    expect(screen.getByLabelText('사이드바 펼치기')).not.toBeNull();
  });

  it('다시 펼치기 버튼을 클릭하면 라벨이 복귀하고 onCollapseToggle(false)이 호출된다', () => {
    const handleToggle = vi.fn();
    render(<SideNavBar onCollapseToggle={handleToggle} />, { wrapper: MemoryRouter });

    fireEvent.click(screen.getByLabelText('사이드바 접기'));
    fireEvent.click(screen.getByLabelText('사이드바 펼치기'));

    expect(handleToggle).toHaveBeenLastCalledWith(false);
    expect(screen.getByText('대시보드')).not.toBeNull();
  });

  it('defaultCollapsed=true면 접힌 상태로 시작한다', () => {
    render(<SideNavBar defaultCollapsed />, { wrapper: MemoryRouter });

    expect(screen.queryByText('대시보드')).toBeNull();
    expect(screen.getByLabelText('사이드바 펼치기')).not.toBeNull();
  });
});
