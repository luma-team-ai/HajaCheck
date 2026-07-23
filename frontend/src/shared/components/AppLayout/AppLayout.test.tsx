// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppLayout } from './AppLayout';

afterEach(cleanup);

describe('AppLayout', () => {
  it('breadcrumb·children을 렌더링하고 SideNavBar·퀵상담 FAB를 함께 배치한다', () => {
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
    expect(screen.getByLabelText('고객지원 챗봇 열기')).not.toBeNull();
  });

  // 퀵상담 FAB(BottomNavBarFab) 클릭 시 FloatingPopup이 뜨고, 링크·상담원 연결 클릭 시
  // 실제 구현된 지원 페이지(/support/ai-assistant)로 이동한다(#499 — 겹침 버그 수정 + 디자인 반영).
  it('퀵상담 FAB를 클릭하면 빠른 링크 팝업이 뜨고, 링크 클릭 시 팝업이 닫힌다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AppLayout breadcrumb={[]}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    expect(screen.queryByText('HajaCheck 도우미')).toBeNull();

    fireEvent.click(screen.getByLabelText('고객지원 챗봇 열기'));

    expect(screen.getByText('HajaCheck 도우미')).not.toBeNull();
    expect(screen.getByText('서비스 이용 방법')).not.toBeNull();
    expect(screen.getByText('분석 결과 문의')).not.toBeNull();
    expect(screen.getByText('요금·기타')).not.toBeNull();

    fireEvent.click(screen.getByText('서비스 이용 방법'));

    expect(screen.queryByText('HajaCheck 도우미')).toBeNull();
  });

  // 이슈 #546(PR머신 P2): FloatingPopup은 useOutsideDismiss로 document mousedown에서 바깥 클릭을
  // 감지해 onClose를 부른다. FAB는 그 컨테이너 바깥이라, 팝업이 열린 상태에서 FAB를 다시 클릭하면
  // 실제 브라우저 이벤트 순서(mousedown→click)상 mousedown이 먼저 팝업을 닫고, 뒤이은 click이
  // 다시 토글해 재오픈해버린다 — AppShellRoute.test.tsx:182-197과 동일하게 실제 이벤트 시퀀스를
  // 그대로 재현해 검증한다(fireEvent.click만 두 번 호출하면 이 경합이 재현되지 않아 통과해버림).
  it('팝업이 열린 상태에서 FAB를 다시 클릭하면(mousedown→click) 닫힌 채로 유지된다', () => {
    render(
      <MemoryRouter>
        <AppLayout breadcrumb={[]}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    const fab = screen.getByLabelText('고객지원 챗봇 열기');
    fireEvent.click(fab);
    expect(screen.getByText('HajaCheck 도우미')).not.toBeNull();

    fireEvent.mouseDown(fab);
    fireEvent.click(fab);

    expect(screen.queryByText('HajaCheck 도우미')).toBeNull();
  });

  // PR머신 P2(위 가드의 부작용 방지) — 팝업 밖 다른 요소를 클릭해 닫은 직후 FAB를 클릭하면
  // (과잉 차단 없이) 정상적으로 다시 열려야 한다. AppShellRoute.test.tsx:202-219와 동일한 형태.
  it('팝업 밖 다른 요소를 클릭해 닫힌 뒤 FAB를 클릭하면 팝업이 다시 열린다', () => {
    render(
      <MemoryRouter>
        <AppLayout breadcrumb={[]}>
          <p>콘텐츠</p>
        </AppLayout>
      </MemoryRouter>,
    );

    const fab = screen.getByLabelText('고객지원 챗봇 열기');
    fireEvent.click(fab);
    expect(screen.getByText('HajaCheck 도우미')).not.toBeNull();

    const pageContent = screen.getByText('콘텐츠');
    fireEvent.mouseDown(pageContent);
    fireEvent.click(pageContent);
    expect(screen.queryByText('HajaCheck 도우미')).toBeNull();

    fireEvent.click(fab);
    expect(screen.getByText('HajaCheck 도우미')).not.toBeNull();
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
