// 지도 뷰 — Kakao Map에 시설물 마커(하자 최고 등급별 색상) 표시 + 검색/필터 목록 패널
// PRD_hajaCheck_v0.37.md 92행, 171행: 업로드 시 수집한 EXIF GPS 활용
// AppShellRoute(공용 앱 셸) 안에서 렌더링되므로 셸(사이드바/헤더) 마크업은 포함하지 않는다(HAJA-150, #129 재오픈)
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { mapApi } from './api/mapApi';
import { FacilityListPanel } from './components/FacilityListPanel';
import { MapControls } from './components/MapControls';
import { MapLegend } from './components/MapLegend';
import { SelectedFacilityPopup } from './components/SelectedFacilityPopup';
import { DEFAULT_MAP_CENTER, DEFAULT_MAP_LEVEL, ERROR_TEXT_COLOR, MAX_MAP_LEVEL, MIN_MAP_LEVEL } from './constants';
import { createFacilityMarker, isValidCoordinate } from './lib/createFacilityMarker';
import { filterFacilities } from './lib/filterFacilities';
import { KakaoMapKeyMissingError, loadKakaoMapSdk } from './lib/loadKakaoMapSdk';

export default function MapPage() {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<KakaoMap | null>(null);
  const markersRef = useRef<KakaoMarker[]>([]);

  const [sdkStatus, setSdkStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [sdkError, setSdkError] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('전체');
  const [selectedFacilityId, setSelectedFacilityId] = useState<number | null>(null);

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
        mapInstanceRef.current = new window.kakao.maps.Map(mapContainerRef.current, {
          center,
          level: DEFAULT_MAP_LEVEL,
        });
        setSdkStatus('ready');
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
    const validFacilities = filteredFacilities.filter((facility) => {
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
  }, [filteredFacilities, sdkStatus]);

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
        map.setCenter(center);
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
        <MapControls onZoomIn={handleZoomIn} onZoomOut={handleZoomOut} onMyLocation={handleMyLocation} />
        <MapLegend />
        {selectedFacility && (
          <SelectedFacilityPopup
            facility={selectedFacility}
            onViewDetail={() => {
              // 시설물 상세 라우트 미구현(features/facility) — 버튼 자리만 배치, 구현 시 연결
            }}
            onGoToInspectionResult={() => {
              // 결과접수 라우트 미구현 — 버튼 자리만 배치, 구현 시 연결
            }}
          />
        )}
      </div>
    </div>
  );
}
