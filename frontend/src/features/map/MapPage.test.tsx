// @vitest-environment jsdom
// MapPage 상호작용 테스트 — 줌 클램프, 내 위치 이동(성공/실패), 필터-선택 상태 동기화(P2, 2026-07-16)
// Kakao Maps SDK는 실제 스크립트 로드 없이 최소 스텁으로 대체하고, loadKakaoMapSdk/mapApi는 모듈 목으로 우회한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FacilityLocation } from './types';

vi.mock('../../shared/lib/kakaoMap/loadKakaoMapSdk', () => ({
  loadKakaoMapSdk: vi.fn(() => Promise.resolve()),
  KakaoMapKeyMissingError: class KakaoMapKeyMissingError extends Error {},
}));

const mockFacilities: FacilityLocation[] = [
  {
    id: 1,
    name: '한강대교 북단',
    address: '서울 용산구 이촌동 302-14',
    category: '교량',
    latitude: 37.5145,
    longitude: 126.9631,
    highestGrade: 'E',
    warningCount: 12,
    cautionCount: 5,
    thumbnailUrl: null,
  },
  {
    id: 2,
    name: '남산1호터널',
    address: '서울 중구 예장동',
    category: '터널',
    latitude: 37.5559,
    longitude: 126.9939,
    highestGrade: 'C',
    warningCount: 3,
    cautionCount: 1,
    thumbnailUrl: null,
  },
];

vi.mock('./api/mapApi', () => ({
  mapApi: {
    getFacilityLocations: vi.fn(() => Promise.resolve(mockFacilities)),
  },
}));

// MapPage를 동적 import하여 위 vi.mock이 먼저 적용된 뒤 로드되도록 한다
let MapPage: typeof import('./MapPage').default;

/**
 * jsdom에는 ResizeObserver가 구현돼 있지 않으므로 최소 스텁으로 대체한다.
 * MapPage가 지도 컨테이너 레이아웃 확정 시점(100ms 고정 setTimeout 대신)을 감지하는 데 사용한다.
 */
class MockResizeObserver {
  static instances: MockResizeObserver[] = [];
  callback: ResizeObserverCallback;
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    MockResizeObserver.instances.push(this);
  }

  /** 테스트에서 컨테이너 크기가 확정된 상황을 흉내내기 위한 헬퍼 */
  triggerResize(width: number, height: number) {
    this.callback(
      [{ contentRect: { width, height } } as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    );
  }
}

/** 지도 인스턴스 상태를 추적하는 최소 Kakao Maps 스텁 (window.kakao.maps) */
function stubKakaoMaps() {
  let currentLevel = 7;
  const mapInstance = {
    setCenter: vi.fn(),
    panTo: vi.fn(),
    setLevel: vi.fn((level: number) => {
      currentLevel = level;
    }),
    getLevel: vi.fn(() => currentLevel),
    relayout: vi.fn(),
    setMapTypeId: vi.fn(),
  };

  (window as unknown as { kakao: unknown }).kakao = {
    maps: {
      load: (cb: () => void) => cb(),
      MapTypeId: { ROADMAP: 0, HYBRID: 2 },
      LatLng: vi.fn(function LatLng(this: Record<string, unknown>, lat: number, lng: number) {
        this.lat = lat;
        this.lng = lng;
      }),
      Map: vi.fn(function Map() {
        return mapInstance;
      }),
      Marker: vi.fn(function Marker(this: Record<string, unknown>, options: Record<string, unknown>) {
        Object.assign(this, options);
        this.setMap = vi.fn();
        this.getPosition = vi.fn();
      }),
      MarkerImage: vi.fn(function MarkerImage() {}),
      Size: vi.fn(function Size() {}),
      Point: vi.fn(function Point(this: Record<string, unknown>, x: number, y: number) {
        this.x = x;
        this.y = y;
      }),
      // CustomOverlay는 실 SDK처럼 content DOM을 지도 레이어에 붙이는 것을 흉내내
      // React Portal로 렌더링된 SelectedFacilityPopup을 RTL이 document에서 찾을 수 있게 한다.
      CustomOverlay: vi.fn(function CustomOverlay(
        this: { content: HTMLElement; setMap: (map: unknown) => void },
        options: { content: HTMLElement },
      ) {
        this.content = options.content;
        this.setMap = (map: unknown) => {
          if (map) {
            document.body.appendChild(this.content);
          } else if (this.content.parentNode) {
            this.content.parentNode.removeChild(this.content);
          }
        };
      }),
      event: { addListener: vi.fn() },
    },
  };

  return { mapInstance };
}

function renderMapPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MapPage />
    </QueryClientProvider>,
  );
}

describe('MapPage', () => {
  beforeEach(async () => {
    vi.resetModules();
    stubKakaoMaps();
    MockResizeObserver.instances = [];
    vi.stubGlobal('ResizeObserver', MockResizeObserver);
    ({ default: MapPage } = await import('./MapPage'));
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('컨테이너 레이아웃 크기가 확정되면(ResizeObserver) relayout과 setCenter를 호출한다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as {
      relayout: ReturnType<typeof vi.fn>;
      setCenter: ReturnType<typeof vi.fn>;
    };

    expect(MockResizeObserver.instances).toHaveLength(1);
    const observer = MockResizeObserver.instances[0];

    // 크기 0(레이아웃 계산 전)은 무시해야 한다
    observer.triggerResize(0, 0);
    expect(mapInstance.relayout).not.toHaveBeenCalled();

    // 유효한 크기가 관측되면 relayout/setCenter를 호출한다
    observer.triggerResize(800, 600);
    expect(mapInstance.relayout).toHaveBeenCalled();
    expect(mapInstance.setCenter).toHaveBeenCalled();
  });

  it('최초 레이아웃 확정 이후 재리사이즈에서는 relayout만 호출하고 setCenter로 중심을 되돌리지 않는다(회귀 방지, code-reviewer P1)', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as {
      relayout: ReturnType<typeof vi.fn>;
      setCenter: ReturnType<typeof vi.fn>;
    };

    const observer = MockResizeObserver.instances[0];

    // 1차 리사이즈: 최초 유효 레이아웃 확정 — setCenter가 정확히 1번 호출된다
    observer.triggerResize(800, 600);
    expect(mapInstance.setCenter).toHaveBeenCalledTimes(1);

    // 사용자가 다른 위치로 지도를 팬/줌했다고 가정(예: 시설물 선택에 따른 panTo, 확대/축소 등) —
    // 이 시점부터는 지도의 실제 중심이 초기 DEFAULT_MAP_CENTER가 아니다.

    // 2차 리사이즈(예: 브라우저 창 크기 변경): relayout은 다시 호출되어야 하지만
    // setCenter는 추가로 호출되어 사용자가 옮긴 중심을 초기값으로 되돌려서는 안 된다.
    observer.triggerResize(1024, 768);
    expect(mapInstance.relayout).toHaveBeenCalledTimes(2);
    expect(mapInstance.setCenter).toHaveBeenCalledTimes(1);

    // 3차 리사이즈에도 동일하게 setCenter는 여전히 최초 1회에서 늘어나지 않는다
    observer.triggerResize(1200, 900);
    expect(mapInstance.relayout).toHaveBeenCalledTimes(3);
    expect(mapInstance.setCenter).toHaveBeenCalledTimes(1);
  });

  it('언마운트 시 ResizeObserver를 disconnect한다', async () => {
    const { unmount } = renderMapPage();
    await screen.findByText('한강대교 북단');

    const observer = MockResizeObserver.instances[0];
    unmount();

    expect(observer.disconnect).toHaveBeenCalled();
  });

  // 회귀 방지(#570): 페이지 루트가 h-full(퍼센트 높이)만 쓰면, AppShellRoute 공용 셸이
  // min-h-screen이라 시설물 목록이 길 때 조상 체인에 확정 높이가 없어 문서 전체가 뷰포트보다
  // 커지고, 그 안의 absolute 배치 요소(범례 등)가 뷰포트 밖으로 밀려난다. 루트는 실측 px 높이로
  // 고정되어야 한다.
  it('페이지 루트 높이가 뷰포트 실측값(px)으로 고정되어, 문서가 뷰포트보다 커지지 않는다(회귀 방지, #570)', async () => {
    const { container } = renderMapPage();
    await screen.findByText('한강대교 북단');

    const root = container.firstElementChild as HTMLElement;
    expect(root.style.height).not.toBe('');
    expect(root.style.height).not.toBe('100%');
    expect(root.style.height.endsWith('px')).toBe(true);
  });

  it('확대 버튼 클릭 시 레벨이 감소하고 MIN_MAP_LEVEL(1) 아래로 내려가지 않는다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const zoomIn = screen.getByRole('button', { name: '지도 확대' });
    // 시작 레벨 7에서 1까지 총 6번, 그 이후로는 1에 고정되어야 함
    for (let i = 0; i < 8; i += 1) {
      fireEvent.click(zoomIn);
    }

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as { getLevel: () => number };
    expect(mapInstance.getLevel()).toBe(1);
  });

  it('축소 버튼 클릭 시 레벨이 증가하고 MAX_MAP_LEVEL(14) 위로 올라가지 않는다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const zoomOut = screen.getByRole('button', { name: '지도 축소' });
    for (let i = 0; i < 10; i += 1) {
      fireEvent.click(zoomOut);
    }

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as { getLevel: () => number };
    expect(mapInstance.getLevel()).toBe(14);
  });

  it('내 위치 이동 성공 시 geolocation 좌표로 지도 중심을 이동한다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const getCurrentPosition = vi.fn((success: PositionCallback) => {
      success({
        coords: { latitude: 37.1, longitude: 127.1 },
      } as GeolocationPosition);
    });
    vi.stubGlobal('navigator', {
      ...navigator,
      geolocation: { getCurrentPosition },
    });

    fireEvent.click(screen.getByRole('button', { name: '내 위치로 이동' }));

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as { panTo: ReturnType<typeof vi.fn> };
    expect(getCurrentPosition).toHaveBeenCalled();
    expect(mapInstance.panTo).toHaveBeenCalled();
  });

  it('내 위치 이동 실패(권한 거부 등) 시 예외 없이 조용히 무시한다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const getCurrentPosition = vi.fn((_success: PositionCallback, error?: PositionErrorCallback) => {
      error?.({ code: 1, message: 'denied' } as GeolocationPositionError);
    });
    vi.stubGlobal('navigator', {
      ...navigator,
      geolocation: { getCurrentPosition },
    });

    expect(() => {
      fireEvent.click(screen.getByRole('button', { name: '내 위치로 이동' }));
    }).not.toThrow();
    expect(getCurrentPosition).toHaveBeenCalled();
  });

  it('선택 팝업(SelectedFacilityPopup)은 GradeBadge를 사용하지 않고 등급 문자만 단독 렌더링한다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    fireEvent.click(screen.getByText('한강대교 북단'));

    const popupContainer = screen.getByText('상세 보기').closest('.z-10') as HTMLElement;
    expect(popupContainer).not.toBeNull();

    // popupContainer 내부에서 "E" 등급 배지는 존재하지만, GradeBadge 형식의 "등급 E"는 렌더링되지 않아야 함
    const gradeBadge = within(popupContainer).getByText('E');
    expect(gradeBadge).toBeTruthy();
    expect(within(popupContainer).queryByText('등급 E')).toBeNull();
  });

  it('검색어로 선택된 시설물이 필터링되면 선택 상태가 해제되고 팝업이 사라진다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    fireEvent.click(screen.getByText('한강대교 북단'));
    expect(await screen.findByText('상세 보기')).not.toBeNull();

    fireEvent.change(screen.getByPlaceholderText('지역·시설물 검색'), {
      target: { value: '남산' },
    });

    expect(screen.queryByText('상세 보기')).toBeNull();
    expect(screen.queryByText('한강대교 북단')).toBeNull();
  });

  it('위성 버튼 클릭 시 지도 타입이 HYBRID로 전환되고 aria-pressed가 반영된다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as { setMapTypeId: ReturnType<typeof vi.fn> };

    const satelliteButton = screen.getByRole('button', { name: '위성' });
    const roadmapButton = screen.getByRole('button', { name: '지도' });
    expect(roadmapButton.getAttribute('aria-pressed')).toBe('true');
    expect(satelliteButton.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(satelliteButton);

    expect(mapInstance.setMapTypeId).toHaveBeenCalledWith(window.kakao.maps.MapTypeId.HYBRID);
    expect(satelliteButton.getAttribute('aria-pressed')).toBe('true');
    expect(roadmapButton.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(roadmapButton);
    expect(mapInstance.setMapTypeId).toHaveBeenCalledWith(window.kakao.maps.MapTypeId.ROADMAP);
  });

  it('목록에서 시설물을 선택하면 지도가 panTo로 부드럽게 이동한다', async () => {
    renderMapPage();
    await screen.findByText('한강대교 북단');

    const map = window.kakao.maps.Map as unknown as ReturnType<typeof vi.fn>;
    const mapInstance = map.mock.results[0].value as { panTo: ReturnType<typeof vi.fn> };

    fireEvent.click(screen.getByText('한강대교 북단'));

    expect(await screen.findByText('상세 보기')).not.toBeNull();
    expect(mapInstance.panTo).toHaveBeenCalled();
  });
});
