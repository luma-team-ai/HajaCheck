// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SideNavBar } from './SideNavBar';

afterEach(cleanup);

describe('SideNavBar', () => {
  it('기본 메뉴 항목을 렌더링하고, activeHref가 하위 항목이면 해당 그룹이 자동으로 펼쳐진다', () => {
    render(<SideNavBar activeHref="/facilities/map" />, { wrapper: MemoryRouter });

    expect(screen.getByText('시설물 관리')).not.toBeNull();
    expect(screen.getByText('지도 뷰').closest('a')?.getAttribute('aria-current')).toBe('page');
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
    render(<SideNavBar isAdmin activeHref="/facilities/map" />, { wrapper: MemoryRouter });

    // 마운트 시 activeHref(시설물 관리)의 그룹이 자동으로 펼쳐진 상태
    expect(screen.getByText('지도 뷰')).not.toBeNull();

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

  describe('isRouteImplemented — 미구현 라우트 내비게이션 차단(HAJA-186, #217 후속)', () => {
    function LocationProbe() {
      const location = useLocation();
      return <div data-testid="location-probe">{location.pathname}</div>;
    }

    function renderFromDashboard(isRouteImplemented: (href: string) => boolean) {
      return render(
        <MemoryRouter initialEntries={['/dashboard']}>
          <SideNavBar activeHref="/dashboard" isRouteImplemented={isRouteImplemented} />
          <LocationProbe />
        </MemoryRouter>,
      );
    }

    it('구현된 라우트(href)를 클릭하면 정상적으로 이동한다', () => {
      renderFromDashboard((href) => href === '/mypage/plan');

      fireEvent.click(screen.getByText('마이페이지'));
      fireEvent.click(screen.getByText('내 플랜'));

      expect(screen.getByTestId('location-probe').textContent).toBe('/mypage/plan');
    });

    it('미구현 라우트(href)를 클릭하면 이동하지 않고 안내 메시지가 표시된다', () => {
      renderFromDashboard((href) => href === '/mypage/plan');

      fireEvent.click(screen.getByText('마이페이지'));
      fireEvent.click(screen.getByText('내 정보'));

      expect(screen.getByTestId('location-probe').textContent).toBe('/dashboard');
      expect(screen.getByRole('status').textContent).toBe('아직 구현되지 않은 페이지입니다');
    });

    it('안내 메시지는 일정 시간 뒤 자동으로 사라진다', () => {
      vi.useFakeTimers();
      try {
        renderFromDashboard(() => false);

        fireEvent.click(screen.getByText('통계'));
        expect(screen.getByRole('status')).not.toBeNull();

        act(() => {
          vi.advanceTimersByTime(3000);
        });

        expect(screen.queryByRole('status')).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    });

    it('isRouteImplemented를 지정하지 않으면 기존처럼 모든 링크가 정상 이동한다(하위 호환)', () => {
      render(
        <MemoryRouter initialEntries={['/dashboard']}>
          <SideNavBar activeHref="/dashboard" />
          <LocationProbe />
        </MemoryRouter>,
      );

      fireEvent.click(screen.getByText('마이페이지'));
      fireEvent.click(screen.getByText('내 정보'));

      expect(screen.getByTestId('location-probe').textContent).toBe('/mypage/profile');
      expect(screen.queryByRole('status')).toBeNull();
    });
  });

  describe('접힌 상태 hover 펼침(HAJA-167, #184)', () => {
    // mouseenter/mouseleave 핸들러는 SideNavBar 최상위 wrapper(<div>)에 달려 있다.
    // 접기/펼치기 토글 버튼은 항상 렌더링되므로 그 버튼의 aside 조상 → 그 부모(wrapper)를 찾는다.
    function getSideNavWrapper() {
      const toggleButton = screen.getByLabelText(/사이드바 (펼치기|접기)/);
      const aside = toggleButton.closest('aside');
      if (!aside || !aside.parentElement) {
        throw new Error('SideNavBar wrapper element not found');
      }
      return aside.parentElement;
    }

    it('접힌 상태에서 마우스를 올리면 라벨이 보이고, 벗어나면 다시 숨는다', () => {
      render(<SideNavBar defaultCollapsed />, { wrapper: MemoryRouter });

      expect(screen.queryByText('대시보드')).toBeNull();

      fireEvent.mouseEnter(getSideNavWrapper());
      expect(screen.getByText('대시보드')).not.toBeNull();

      fireEvent.mouseLeave(getSideNavWrapper());
      expect(screen.queryByText('대시보드')).toBeNull();
    });

    it('hover로 시각적으로 펼쳐져도 실제 collapsed 상태는 바뀌지 않아 onCollapseToggle이 호출되지 않는다', () => {
      const handleToggle = vi.fn();
      render(<SideNavBar defaultCollapsed onCollapseToggle={handleToggle} />, {
        wrapper: MemoryRouter,
      });

      fireEvent.mouseEnter(getSideNavWrapper());

      // 토글 버튼은 여전히 "펼치기"로 표시된다 — 실제로는 접힌 상태 그대로이기 때문
      expect(screen.getByLabelText('사이드바 펼치기')).not.toBeNull();
      expect(handleToggle).not.toHaveBeenCalled();

      fireEvent.mouseLeave(getSideNavWrapper());
      expect(handleToggle).not.toHaveBeenCalled();
    });

    it('접기 버튼은 hover 상태와 무관하게 항상 정상 동작한다', () => {
      const handleToggle = vi.fn();
      render(<SideNavBar onCollapseToggle={handleToggle} />, { wrapper: MemoryRouter });

      fireEvent.mouseEnter(getSideNavWrapper());
      fireEvent.click(screen.getByLabelText('사이드바 접기'));

      expect(handleToggle).toHaveBeenCalledWith(true);
    });

    it('마우스가 여전히 사이드바 위에 있어도 접기 버튼을 누르면 즉시 시각적으로 접힌다(hover 중 토글 회귀 방지)', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });

      // 클릭하려면 이미 사이드바 위에 마우스가 있어야 하므로 hoverExpanded는 true인 상태
      fireEvent.mouseEnter(getSideNavWrapper());
      expect(screen.getByText('대시보드')).not.toBeNull();

      // mouseLeave 없이(=여전히 hover 중) 접기 버튼을 눌러도 즉시 접힌 모습으로 바뀌어야 한다.
      // setHoverExpanded(false) 리셋이 없으면 hoverExpanded가 true로 남아 라벨이 계속 보인다.
      fireEvent.click(screen.getByLabelText('사이드바 접기'));

      expect(screen.queryByText('대시보드')).toBeNull();
      expect(screen.getByLabelText('사이드바 펼치기')).not.toBeNull();
    });

    it('접힌 상태에서 hover로 펼친 뒤 하위 메뉴를 클릭하면 실제로 이동한다', () => {
      function LocationProbe() {
        const location = useLocation();
        return <div data-testid="location-probe">{location.pathname}</div>;
      }

      render(
        <MemoryRouter initialEntries={['/facilities']}>
          <SideNavBar defaultCollapsed />
          <LocationProbe />
        </MemoryRouter>,
      );

      fireEvent.mouseEnter(getSideNavWrapper());

      fireEvent.click(screen.getByText('대시보드'));
      fireEvent.click(screen.getByText('전체 시설물 현황'));

      expect(screen.getByTestId('location-probe').textContent).toBe('/dashboard');
    });
  });

  describe('펼쳐진 상태 드래그 리사이즈(HAJA-167, #184)', () => {
    function getResizeHandle() {
      return screen.getByRole('separator', { name: '사이드바 너비 조절' });
    }

    function getAsideWidth() {
      return getResizeHandle().closest('aside')?.style.width;
    }

    it('기본 폭은 244px이고, 오른쪽으로 드래그하면 폭이 늘어난다', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });

      expect(getAsideWidth()).toBe('244px');

      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: 300 });

      expect(getAsideWidth()).toBe('304px');

      fireEvent.mouseUp(window);
    });

    // DEFAULT_WIDTH(244)가 곧 MIN_WIDTH라, 기본 상태에서 바로 왼쪽으로 드래그하면 즉시 하한에 clamp된다
    // (아래 'MIN_WIDTH 밑으로 내려가지 않도록' 테스트가 그 케이스를 다룬다). 이 테스트는 먼저 넓힌
    // 뒤 다시 왼쪽으로 줄이는, MIN_WIDTH보다 넓은 상태에서의 축소 동작을 확인한다(#499).
    it('넓힌 상태에서 왼쪽으로 드래그하면 폭이 줄어든다', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });

      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: 300 });
      fireEvent.mouseUp(window);

      fireEvent.mouseDown(getResizeHandle(), { clientX: 300 });
      fireEvent.mouseMove(window, { clientX: 270 });

      expect(getAsideWidth()).toBe('274px');

      fireEvent.mouseUp(window);
    });

    it('MAX_WIDTH(320px)를 넘어가지 않도록 clamp된다', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });

      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: 1000 });

      expect(getAsideWidth()).toBe('320px');

      fireEvent.mouseUp(window);
    });

    it('MIN_WIDTH(244px) 밑으로 내려가지 않도록 clamp된다', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });

      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: -1000 });

      expect(getAsideWidth()).toBe('244px');

      fireEvent.mouseUp(window);
    });

    it('mouseup 이후에는 mousemove가 더 이상 폭에 반영되지 않는다', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });

      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: 260 });
      expect(getAsideWidth()).toBe('264px');

      fireEvent.mouseUp(window);
      fireEvent.mouseMove(window, { clientX: 500 });

      expect(getAsideWidth()).toBe('264px');
    });

    it('드래그로 조절된 폭이 바뀔 때마다 onWidthChange가 호출된다', () => {
      const handleWidthChange = vi.fn();
      render(<SideNavBar onWidthChange={handleWidthChange} />, { wrapper: MemoryRouter });

      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: 280 });
      fireEvent.mouseUp(window);

      expect(handleWidthChange).toHaveBeenCalledWith(284);
    });

    it('접힌 상태에서는 드래그 핸들이 렌더링되지 않는다', () => {
      render(<SideNavBar defaultCollapsed />, { wrapper: MemoryRouter });

      expect(screen.queryByRole('separator', { name: '사이드바 너비 조절' })).toBeNull();
    });

    it('첫 번째 드래그의 mouseup 없이 두 번째 mousedown이 발생해도 이전 mousemove 리스너가 남지 않는다(PR머신 리뷰 P2 회귀 방지)', () => {
      const handleWidthChange = vi.fn();
      render(<SideNavBar onWidthChange={handleWidthChange} />, { wrapper: MemoryRouter });

      // 첫 번째 드래그를 시작만 하고 mouseup 없이 방치(브라우저 창 밖에서 놓친 상황 재현)
      fireEvent.mouseDown(getResizeHandle(), { clientX: 240 });
      fireEvent.mouseMove(window, { clientX: 260 });
      expect(handleWidthChange).toHaveBeenCalledTimes(1);

      // 두 번째 드래그 시작 — 이전 리스너가 정리되지 않았다면 이후 mousemove마다
      // 두 개의 핸들러가 중복으로 onWidthChange를 호출하게 된다
      fireEvent.mouseDown(getResizeHandle(), { clientX: 260 });
      handleWidthChange.mockClear();
      fireEvent.mouseMove(window, { clientX: 300 });

      expect(handleWidthChange).toHaveBeenCalledTimes(1);
      expect(handleWidthChange).toHaveBeenCalledWith(304);

      fireEvent.mouseUp(window);
    });

    it('리사이즈 핸들에 포커스 가능하고 ArrowRight/ArrowLeft로 폭이 조절된다(키보드 접근성, PR머신 리뷰 P2)', () => {
      const handleWidthChange = vi.fn();
      render(<SideNavBar onWidthChange={handleWidthChange} />, { wrapper: MemoryRouter });

      const handle = getResizeHandle();
      expect(handle.getAttribute('tabindex')).toBe('0');
      expect(handle.getAttribute('aria-valuenow')).toBe('244');
      expect(handle.getAttribute('aria-valuemin')).toBe('244');
      expect(handle.getAttribute('aria-valuemax')).toBe('320');

      fireEvent.keyDown(handle, { key: 'ArrowRight' });
      expect(getAsideWidth()).toBe('260px');
      expect(handleWidthChange).toHaveBeenCalledWith(260);

      fireEvent.keyDown(handle, { key: 'ArrowLeft' });
      fireEvent.keyDown(handle, { key: 'ArrowLeft' });
      expect(getAsideWidth()).toBe('244px');
    });

    it('키보드 ArrowRight를 반복해도 MAX_WIDTH(320px)를 넘지 않도록 clamp된다', () => {
      render(<SideNavBar />, { wrapper: MemoryRouter });
      const handle = getResizeHandle();

      for (let i = 0; i < 10; i += 1) {
        fireEvent.keyDown(handle, { key: 'ArrowRight' });
      }

      expect(getAsideWidth()).toBe('320px');
    });
  });
});
