// 지도 뷰 — Kakao Map에 시설물 마커(하자 최고 등급별 색상) 표시 + 검색/필터 목록 패널
// PRD_hajaCheck_v0.37.md 92행, 171행: 업로드 시 수집한 EXIF GPS 활용
// AppShellRoute(공용 앱 셸) 안에서 렌더링되므로 셸(사이드바/헤더) 마크업은 포함하지 않는다(HAJA-150, #129 재오픈)
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { mapApi } from './api/mapApi';
import { FacilityListPanel } from './components/FacilityListPanel';
import { MapControls, type MapDisplayType } from './components/MapControls';
import { MapLegend } from './components/MapLegend';
import { SelectedFacilityPopup } from './components/SelectedFacilityPopup';
import { DEFAULT_MAP_CENTER, DEFAULT_MAP_LEVEL, ERROR_TEXT_COLOR, MAX_MAP_LEVEL, MIN_MAP_LEVEL } from './constants';
import { useDebouncedValue } from './hooks/useDebouncedValue';
import { createFacilityMarker, isValidCoordinate } from './lib/createFacilityMarker';
import { filterFacilities } from './lib/filterFacilities';
import { KakaoMapKeyMissingError, loadKakaoMapSdk } from './lib/loadKakaoMapSdk';

// 검색어 타이핑마다 지도 마커를 전량 재생성(setMap(null) 후 재생성)하면 입력이 잦을수록
// 불필요한 DOM/SVG 마커 생성 비용이 커진다. 목록 패널 필터링은 즉시 반영하되, 마커 재생성에
// 쓰이는 검색어만 디바운스해 타이핑이 끝난 뒤에 한 번만 재생성되도록 한다(P2, 2026-07-16).
const MARKER_SEARCH_DEBOUNCE_MS = 250;

export default function MapPage() {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<KakaoMap | null>(null);
  const markersRef = useRef<KakaoMarker[]>([]);
  const overlayRef = useRef<KakaoCustomOverlay | null>(null);

  const [overlayContainer] = useState(() => document.createElement('div'));

  const [sdkStatus, setSdkStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [sdkError, setSdkError] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('전체');
  const [selectedFacilityId, setSelectedFacilityId] = useState<number | null>(null);
  const [mapType, setMapType] = useState<MapDisplayType>('roadmap');

  const {
    data: facilities,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['map', 'facilities'],
    queryFn: mapApi.getFacilityLocations,
  });

  const filteredFacilities = useMemo(
    () => filterFacilities(facilities, searchQuery, selectedCategory),
    [facilities, searchQuery, selectedCategory]
  );

  // 마커 재생성 전용 디바운스 검색어 — 목록 패널(filteredFacilities)은 즉시 반영되지만
  // 마커는 이 값이 안정된 뒤에만 재계산된다.
  const debouncedSearchQuery = useDebouncedValue(searchQuery, MARKER_SEARCH_DEBOUNCE_MS);
  const markerFacilities = useMemo(
    () => filterFacilities(facilities, debouncedSearchQuery, selectedCategory),
    [facilities, debouncedSearchQuery, selectedCategory]
  );

  const selectedFacility = useMemo(
    () => filteredFacilities.find((facility) => facility.id === selectedFacilityId) ?? null,
    [filteredFacilities, selectedFacilityId]
  );

  // 필터 적용으로 선택된 시설물이 결과에서 사라지면 선택 상태를 해제합니다.
  useEffect(() => {
    if (selectedFacilityId !== null && !filteredFacilities.some((f) => f.id === selectedFacilityId)) {
      setSelectedFacilityId(null);
    }
  }, [filteredFacilities, selectedFacilityId]);

  // Kakao Maps SDK 로드 + 지도 인스턴스 생성 (최초 1회)
  useEffect(() => {
    let cancelled = false;

    loadKakaoMapSdk()
      .then(() => {
        // mapInstanceRef.current 가드: StrictMode 재마운트 등으로 재진입해도 같은 컨테이너에 지도를 중복 생성하지 않음
        if (cancelled || !mapContainerRef.current || mapInstanceRef.current) return;
        const center = new window.kakao.maps.LatLng(
          DEFAULT_MAP_CENTER.latitude,
          DEFAULT_MAP_CENTER.longitude,
        );
        const map = new window.kakao.maps.Map(mapContainerRef.current, {
          center,
          level: DEFAULT_MAP_LEVEL,
        });
        mapInstanceRef.current = map;
        setSdkStatus('ready');

        // 초기 렌더링 시 flex 레이아웃 계산 타이밍 불일치로 인한 타일 미로드/오버레이 깨짐 방지.
        // 100ms는 실측 기반 값 — 로컬/CI 크롬에서 flex 자식(지도 컨테이너) 레이아웃 계산이
        // 마운트 직후 프레임 내에 끝나지 않아 relayout() 호출 없이는 초기 타일이 잘리는 현상을
        // 재현했고, 60ms 이하에서는 간헐적으로 재현되어 여유를 둔 100ms로 고정함(2026-07-16).
        // ResizeObserver로 컨테이너 크기 변화를 감지해 대체하는 방안도 검토했으나, 이 문제는
        // "최초 1회, 마운트 직후"에만 발생하는 타이밍 이슈라 상시 관찰자를 두는 것은 과설계로 판단.
        setTimeout(() => {
          if (!cancelled && mapInstanceRef.current) {
            mapInstanceRef.current.relayout();
            mapInstanceRef.current.setCenter(center);
          }
        }, 100);
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const message =
          error instanceof KakaoMapKeyMissingError
            ? 'Kakao Map API 키가 설정되지 않았습니다. frontend/.env.local 에 VITE_KAKAO_MAP_APP_KEY 값을 설정해 주세요.'
            : 'Kakao Map을 불러오는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
        setSdkError(message);
        setSdkStatus('error');
      });

    return () => {
      cancelled = true;
      markersRef.current.forEach((marker) => marker.setMap(null));
      markersRef.current = [];
      mapInstanceRef.current = null;
    };
  }, []);

  // 필터링된 시설물 목록이 준비되면 마커 렌더링 (재렌더 시 기존 마커 정리 후 재생성)
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (sdkStatus !== 'ready' || !map) return;

    markersRef.current.forEach((marker) => marker.setMap(null));

    // 좌표 런타임 검증 — 실 API 연동 시 서버가 null/NaN/범위밖 좌표를 줄 수 있으므로
    // 마커 생성 전에 걸러내고, 걸러진 항목은 warn으로 남겨 추적 가능하게 한다
    const validFacilities = markerFacilities.filter((facility) => {
      const valid = isValidCoordinate(facility.latitude, facility.longitude);
      if (!valid) {
        console.warn(
          `[MapPage] 유효하지 않은 좌표를 가진 시설물을 건너뜁니다: id=${facility.id}, latitude=${facility.latitude}, longitude=${facility.longitude}`,
        );
      }
      return valid;
    });

    markersRef.current = validFacilities.map((facility) =>
      createFacilityMarker(map, facility, (selected) => setSelectedFacilityId(selected.id)),
    );

    return () => {
      markersRef.current.forEach((marker) => marker.setMap(null));
      markersRef.current = [];
    };
  }, [markerFacilities, sdkStatus]);

  // 지도/위성 토글 상태를 실제 카카오맵 인스턴스에 반영
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (sdkStatus !== 'ready' || !map) return;
    const typeId =
      mapType === 'hybrid' ? window.kakao.maps.MapTypeId.HYBRID : window.kakao.maps.MapTypeId.ROADMAP;
    map.setMapTypeId(typeId);
  }, [mapType, sdkStatus]);

  // 선택된 시설물 변경 시 카카오맵 CustomOverlay 동기화 + 지도 시점을 부드럽게 이동(panTo)
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (sdkStatus !== 'ready' || !map) return;

    if (overlayRef.current) {
      overlayRef.current.setMap(null);
      overlayRef.current = null;
    }

    if (!selectedFacility) return;

    const position = new window.kakao.maps.LatLng(
      selectedFacility.latitude,
      selectedFacility.longitude
    );

    map.panTo(position);

    const overlay = new window.kakao.maps.CustomOverlay({
      position,
      content: overlayContainer,
      yAnchor: 1.18, // 마커 살짝 위에 배치하기 위한 Anchor 값
    });

    overlay.setMap(map);
    overlayRef.current = overlay;

    return () => {
      if (overlayRef.current) {
        overlayRef.current.setMap(null);
        overlayRef.current = null;
      }
    };
  }, [selectedFacility, sdkStatus, overlayContainer]);

  const handleChangeMapType = (nextType: MapDisplayType) => {
    setMapType(nextType);
  };

  const handleZoomIn = () => {
    const map = mapInstanceRef.current;
    if (!map) return;
    map.setLevel(Math.max(MIN_MAP_LEVEL, map.getLevel() - 1));
  };

  const handleZoomOut = () => {
    const map = mapInstanceRef.current;
    if (!map) return;
    map.setLevel(Math.min(MAX_MAP_LEVEL, map.getLevel() + 1));
  };

  const handleMyLocation = () => {
    const map = mapInstanceRef.current;
    if (!map || !navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const center = new window.kakao.maps.LatLng(
          position.coords.latitude,
          position.coords.longitude,
        );
        map.panTo(center);
      },
      () => {
        // 위치 권한 거부/실패 시 조용히 무시 — 지도 기본 중심을 그대로 유지
      },
    );
  };

  if (sdkStatus === 'error') {
    return <div style={{ padding: 24, color: ERROR_TEXT_COLOR }}>{sdkError}</div>;
  }

  return (
    <div className="flex h-full w-full overflow-hidden">
      <FacilityListPanel
        facilities={filteredFacilities}
        isLoading={isLoading || sdkStatus === 'loading'}
        isError={isError}
        searchQuery={searchQuery}
        onSearchQueryChange={setSearchQuery}
        selectedCategory={selectedCategory}
        onSelectCategory={setSelectedCategory}
        selectedFacilityId={selectedFacilityId}
        onSelectFacility={setSelectedFacilityId}
      />
      <div className="relative flex-1 overflow-hidden bg-white">
        <div ref={mapContainerRef} className="h-full w-full" />
        <MapControls
          mapType={mapType}
          onChangeMapType={handleChangeMapType}
          onZoomIn={handleZoomIn}
          onZoomOut={handleZoomOut}
          onMyLocation={handleMyLocation}
        />
        <MapLegend />
        {selectedFacility && createPortal(
          <SelectedFacilityPopup
            facility={selectedFacility}
            onViewDetail={() => {
              // 시설물 상세 라우트 미구현(features/facility) — 버튼 자리만 배치, 구현 시 연결
            }}
            onGoToInspectionResult={() => {
              // 결과접수 라우트 미구현 — 버튼 자리만 배치, 구현 시 연결
            }}
          />,
          overlayContainer
        )}
      </div>
    </div>
  );
}
