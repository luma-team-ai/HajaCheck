// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppLayout } from './AppLayout';

afterEach(cleanup);

describe('AppLayout', () => {
  it('breadcrumb·children을 렌더링하고 SideNavBar·ChatbotButton을 함께 배치한다', () => {
    render(
      <MemoryRouter>
        <AppLayout breadcrumb={[{ label: '홈' }, { label: '마이페이지 상세' }]}>
          <p>페이지 콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    expect(screen.getByText('마이페이지 상세')).not.toBeNull();
    expect(screen.getByText('페이지 콘텐츠')).not.toBeNull();
    expect(screen.getByText('시설물 관리')).not.toBeNull(); // SideNavBar 기본 메뉴
    expect(screen.getByLabelText('챗봇 열기')).not.toBeNull();
  });

  it('activeHref 미지정 시 현재 URL(useLocation) 기준으로 SideNavBar 활성 항목을 계산한다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AppLayout breadcrumb={[]}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    // 대시보드 그룹이 자동으로 펼쳐지고, 서브메뉴("전체 시설물 현황")가 활성 표시된다
    expect(screen.getByText('전체 시설물 현황').closest('a')?.getAttribute('aria-current')).toBe(
      'page',
    );
  });

  it('activeHref를 명시하면 useLocation 대신 그 값을 우선 사용한다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AppLayout breadcrumb={[]} activeHref="/mypage/plan">
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    // 대시보드 그룹이 아니라 마이페이지 그룹이 activeHref 기준으로 펼쳐진다
    expect(screen.queryByText('전체 시설물 현황')).toBeNull();
    expect(screen.getByText('내 플랜').closest('a')?.getAttribute('aria-current')).toBe('page');
  });

  it('isRouteImplemented를 SideNavBar까지 그대로 전달한다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AppLayout breadcrumb={[]} isRouteImplemented={(href) => href === '/dashboard'}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByText('통계'));

    expect(screen.getByRole('status').textContent).toBe('아직 구현되지 않은 페이지입니다');
  });

  it('알림·프로필 클릭 핸들러를 Header까지 전달한다', () => {
    const onNotificationClick = vi.fn();
    const onProfileClick = vi.fn();
    render(
      <MemoryRouter>
        <AppLayout
          breadcrumb={[]}
          onNotificationClick={onNotificationClick}
          onProfileClick={onProfileClick}
        >
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByLabelText('알림'));
    fireEvent.click(screen.getByLabelText('내 프로필'));

    expect(onNotificationClick).toHaveBeenCalledTimes(1);
    expect(onProfileClick).toHaveBeenCalledTimes(1);
  });

  it('isAdmin=true면 관리자 메뉴와 사이드바 하단 프로필이 함께 노출된다(HAJA-167, #184)', () => {
    render(
      <MemoryRouter>
        <AppLayout breadcrumb={[]} isAdmin user={{ name: '김관리' }}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    expect(screen.getByText('관리자 페이지')).not.toBeNull();
    expect(screen.getByText('김관리')).not.toBeNull();
  });

  it('isAdmin이 아니면 user가 있어도 관리자 메뉴와 사이드바 프로필 둘 다 노출되지 않는다(HAJA-167, #184)', () => {
    render(
      <MemoryRouter>
        <AppLayout breadcrumb={[]} user={{ name: '김일반' }}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    expect(screen.queryByText('관리자 페이지')).toBeNull();
    expect(screen.queryByText('김일반')).toBeNull();
  });
});
