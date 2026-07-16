// @vitest-environment jsdom
// MapPage 상호작용 테스트 — 줌 클램프, 내 위치 이동(성공/실패), 필터-선택 상태 동기화(P2, 2026-07-16)
// Kakao Maps SDK는 실제 스크립트 로드 없이 최소 스텁으로 대체하고, loadKakaoMapSdk/mapApi는 모듈 목으로 우회한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FacilityLocation } from './types';

vi.mock('./lib/loadKakaoMapSdk', () => ({
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

/** 지도 인스턴스 상태를 추적하는 최소 Kakao Maps 스텁 (window.kakao.maps) */
function stubKakaoMaps() {
  let currentLevel = 7;
  const mapInstance = {
    setCenter: vi.fn(),
    setLevel: vi.fn((level: number) => {
      currentLevel = level;
    }),
    getLevel: vi.fn(() => currentLevel),
    relayout: vi.fn(),
  };

  (window as unknown as { kakao: unknown }).kakao = {
    maps: {
      load: (cb: () => void) => cb(),
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
    ({ default: MapPage } = await import('./MapPage'));
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
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
    const mapInstance = map.mock.results[0].value as { setCenter: ReturnType<typeof vi.fn> };
    expect(getCurrentPosition).toHaveBeenCalled();
    expect(mapInstance.setCenter).toHaveBeenCalled();
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
});
