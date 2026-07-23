// 지도 뷰 — Kakao Map에 시설물 마커(하자 최고 등급별 색상) 표시 + 검색/필터 목록 패널
// PRD_hajaCheck_v0.37.md 92행, 171행: 업로드 시 수집한 EXIF GPS 활용
// AppShellRoute(공용 앱 셸) 안에서 렌더링되므로 셸(사이드바/헤더) 마크업은 포함하지 않는다(HAJA-150, #129 재오픈)
import { useQuery } from '@tanstack/react-query';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { mapApi } from './api/mapApi';
import { FacilityListPanel } from './components/FacilityListPanel';
import { MapControls, type MapDisplayType } from './components/MapControls';
import { MapLegend } from './components/MapLegend';
import { SelectedFacilityPopup } from './components/SelectedFacilityPopup';
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue';
import { DEFAULT_MAP_CENTER, DEFAULT_MAP_LEVEL, ERROR_TEXT_COLOR, MAX_MAP_LEVEL, MIN_MAP_LEVEL } from './constants';
import { createFacilityMarker, isValidCoordinate, updateFacilityMarkerImage } from './lib/createFacilityMarker';
import { filterFacilities } from './lib/filterFacilities';
import {
  KakaoMapKeyMissingError,
  loadKakaoMapSdk,
} from '../../shared/lib/kakaoMap/loadKakaoMapSdk';

// 검색어 타이핑마다 지도 마커를 전량 재생성(setMap(null) 후 재생성)하면 입력이 잦을수록
// 불필요한 DOM/SVG 마커 생성 비용이 커진다. 목록 패널 필터링은 즉시 반영하되, 마커 재생성에
// 쓰이는 검색어만 디바운스해 타이핑이 끝난 뒤에 한 번만 재생성되도록 한다(P2, 2026-07-16).
const MARKER_SEARCH_DEBOUNCE_MS = 250;

export default function MapPage() {
  const navigate = useNavigate();
  const rootRef = useRef<HTMLDivElement>(null);
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<KakaoMap | null>(null);
  const markersRef = useRef<KakaoMarker[]>([]);
  const overlayRef = useRef<KakaoCustomOverlay | null>(null);
  const resizeObserverRef = useRef<ResizeObserver | null>(null);
  const hasCenteredRef = useRef(false);

  // 이 페이지 루트의 뷰포트 기준 실측 높이 — AppShellRoute의 공용 셸(min-h-screen, shared라 여기서
  // 수정하지 않음)이 자식 콘텐츠(긴 시설물 목록)에 따라 뷰포트보다 커질 수 있고, 그러면 셸의
  // <main overflow-y-auto>가 내부 스크롤하는 대신 문서 전체가 스크롤된다(#570). h-full(퍼센트
  // 높이)은 조상 체인에 확정된 높이가 없으면 콘텐츠 크기에 그대로 끌려가 이 문제를 못 막는다.
  // 대신 루트의 실제 화면상 top 위치(헤더 높이만큼, 이 페이지 콘텐츠 크기와 무관하게 안정적)를
  // 측정해 뷰포트 나머지 높이를 px로 명시하면, 이 페이지 자신이 뷰포트에 갇혀 문서가 더 이상
  // 커지지 않는다 — 그 안의 FacilityListPanel(자체 overflow-y-auto 보유)이 내부 스크롤을 맡고,
  // MapControls/MapLegend는 원래의 단순한 absolute 배치로도 항상 화면 안에 머문다.
  const [rootHeight, setRootHeight] = useState<number | null>(null);

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
        // 과거에는 100ms 고정 setTimeout으로 우회했으나(실측 기반이라도 매직넘버 워크어라운드,
        // P3, PR #265/#130 리뷰), 실제로 필요한 건 "지도 컨테이너의 레이아웃(크기)이 확정된 시점"이므로
        // ResizeObserver로 컨테이너 크기 변화를 직접 감지해 그 시점에 relayout()을 호출한다.
        // 크기가 0이면 아직 flex 레이아웃 계산 전이므로 건너뛰고, 유효한 크기가 처음 관측될 때만
        // relayout을 실행한다(이후 실제 리사이즈에도 relayout을 다시 호출하는 것은 카카오맵 SDK가
        // 컨테이너 크기 변경에 자동 대응하지 못하는 문제를 함께 방지하는 부가 효과).
        // setCenter(center)는 "최초 유효 레이아웃" 시점 딱 1회만 호출한다 — 이후 리사이즈(예: 사용자가
        // 지도를 팬/줌한 뒤 브라우저 창 크기가 바뀌는 경우)마다 호출하면 사용자가 옮긴 현재 중심을
        // 매번 초기 DEFAULT_MAP_CENTER로 되돌려버리는 회귀가 생긴다(code-reviewer P1, #335).
        const container = mapContainerRef.current;
        const observer = new ResizeObserver((entries) => {
          if (cancelled || !mapInstanceRef.current) return;
          const entry = entries[0];
          if (!entry) return;
          const { width, height } = entry.contentRect;
          if (width === 0 || height === 0) return;
          mapInstanceRef.current.relayout();
          if (!hasCenteredRef.current) {
            hasCenteredRef.current = true;
            mapInstanceRef.current.setCenter(center);
          }
        });
        observer.observe(container);
        resizeObserverRef.current = observer;
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const message =
          error instanceof KakaoMapKeyMissingError
            ? 'Kakao Map API 키가 설정되지 않았습니다. VITE_KAKAO_MAP_APP_KEY 를 설정하세요 (도커: 루트 .env / 네이티브: frontend/.env.local).'
            : 'Kakao Map을 불러오는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
        setSdkError(message);
        setSdkStatus('error');
      });

    return () => {
      cancelled = true;
      resizeObserverRef.current?.disconnect();
      resizeObserverRef.current = null;
      hasCenteredRef.current = false;
      markersRef.current.forEach((marker) => marker.setMap(null));
      markersRef.current = [];
      mapInstanceRef.current = null;
    };
  }, []);

  // 루트의 뷰포트 기준 실측 높이를 px로 고정 — 위 rootHeight 선언부 주석 참고(#570).
  // useLayoutEffect: 브라우저 페인트 전에 동기 적용해, 문서가 잠깐이라도 뷰포트보다 커지는
  // 순간(FOUC 성격의 flash)을 최소화한다. 루트 자신의 top은 이 페이지의 콘텐츠 크기와
  // 무관하게 안정적이므로(위에 있는 헤더 높이로만 결정) 초기 측정도 신뢰할 수 있다.
  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root) return;

    const updateHeight = () => {
      const top = root.getBoundingClientRect().top;
      setRootHeight(window.innerHeight - top);
    };

    updateHeight();
    window.addEventListener('resize', updateHeight);
    return () => window.removeEventListener('resize', updateHeight);
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
      createFacilityMarker(
        map,
        facility,
        (selected) => setSelectedFacilityId(selected.id),
        facility.id === selectedFacilityId,
      ),
    );

    return () => {
      markersRef.current.forEach((marker) => marker.setMap(null));
      markersRef.current = [];
    };
  }, [markerFacilities, sdkStatus]);

  // 선택된 시설물 변경 시 마커 이미지(미선택: 원형 도트, 선택: 피그마 원형+삼각형 핀) 및 z-index 동기화
  useEffect(() => {
    if (sdkStatus !== 'ready') return;
    markerFacilities.forEach((facility, index) => {
      const marker = markersRef.current[index];
      if (!marker) return;
      const isSelected = facility.id === selectedFacilityId;
      updateFacilityMarkerImage(marker, isSelected, facility.highestGrade);
    });
  }, [selectedFacilityId, markerFacilities, sdkStatus]);

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
      yAnchor: 1.32, // 선택 핀(높이 42px)과 오버레이 팝업 카드의 Y축 위치가 겹치지 않도록 여유 이격
      zIndex: 300, // 카카오맵 오버레이 레이어 스태킹 최상위(300) 지정해 어떤 마커 핀(z-index 20 이하)에도 팝업이 가려지지 않도록 보장
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
    <div
      ref={rootRef}
      className="flex w-full overflow-hidden"
      style={{ height: rootHeight ?? '100%' }}
    >
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
              navigate(`/facilities/${selectedFacility.id}`);
            }}
            onGoToInspectionResult={() => {
              // 결과 검수 라우트(/inspections/:id/viewer)는 inspectionId가 필요하나 FacilityLocation/
              // GET /api/facilities 응답 어디에도 해당 필드가 없음 — 백엔드 API 확장 선행 필요, 별도 이슈로 분리(#570 범위 밖)
            }}
          />,
          overlayContainer
        )}
      </div>
    </div>
  );
}
