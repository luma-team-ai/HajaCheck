// 지도 뷰 — Kakao Map에 시설물 마커(하자 최고 등급별 색상) 표시
// PRD_hajaCheck_v0.37.md 92행, 171행: 업로드 시 수집한 EXIF GPS 활용
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { mapApi } from './api';
import { DEFAULT_MAP_CENTER, DEFAULT_MAP_LEVEL, GRADE_COLOR, GRADE_LABEL } from './constants';
import { createFacilityMarker, buildInfoWindowContent } from './lib/createFacilityMarker';
import { KakaoMapKeyMissingError, loadKakaoMapSdk } from './lib/loadKakaoMapSdk';
import type { FacilityLocation } from './types';

export default function MapPage() {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<KakaoMap | null>(null);
  const infoWindowRef = useRef<KakaoInfoWindow | null>(null);
  const markersRef = useRef<KakaoMarker[]>([]);

  const [sdkStatus, setSdkStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [sdkError, setSdkError] = useState<string | null>(null);

  const {
    data: facilities,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['map', 'facilities'],
    queryFn: mapApi.getFacilityLocations,
  });

  // Kakao Maps SDK 로드 + 지도 인스턴스 생성 (최초 1회)
  useEffect(() => {
    let cancelled = false;

    loadKakaoMapSdk()
      .then(() => {
        if (cancelled || !mapContainerRef.current) return;
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
    };
  }, []);

  // 시설물 데이터가 준비되면 마커 렌더링 (재렌더 시 기존 마커 정리 후 재생성)
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (sdkStatus !== 'ready' || !map || !facilities) return;

    markersRef.current.forEach((marker) => marker.setMap(null));
    infoWindowRef.current?.close();

    markersRef.current = facilities.map((facility) =>
      createFacilityMarker(map, facility, (selected: FacilityLocation) => {
        infoWindowRef.current?.close();
        infoWindowRef.current = new window.kakao.maps.InfoWindow({
          content: buildInfoWindowContent(selected),
          removable: true,
        });
        const marker = markersRef.current.find((m) => m.getPosition().getLat() === selected.latitude);
        if (marker) {
          infoWindowRef.current.open(map, marker);
        }
      }),
    );

    return () => {
      markersRef.current.forEach((marker) => marker.setMap(null));
    };
  }, [facilities, sdkStatus]);

  if (sdkStatus === 'error') {
    return <div style={{ padding: 24, color: '#B91C1C' }}>{sdkError}</div>;
  }

  if (isError) {
    return <div style={{ padding: 24, color: '#B91C1C' }}>시설물 위치를 불러오지 못했습니다.</div>;
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '12px 16px' }}>
        <h1 style={{ fontSize: 18, margin: 0 }}>지도 뷰</h1>
        {(isLoading || sdkStatus === 'loading') && <span>불러오는 중...</span>}
        {!isLoading && facilities?.length === 0 && <span>등록된 시설물 위치가 없습니다.</span>}
        <MapLegend />
      </div>
      <div ref={mapContainerRef} style={{ width: '100%', height: '640px' }} />
    </div>
  );
}

function MapLegend() {
  return (
    <div style={{ display: 'flex', gap: 12, marginLeft: 'auto', fontSize: 13 }}>
      {(Object.keys(GRADE_LABEL) as Array<keyof typeof GRADE_LABEL>).map((grade) => (
        <span key={grade} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span
            style={{
              display: 'inline-block',
              width: 10,
              height: 10,
              borderRadius: '50%',
              backgroundColor: GRADE_COLOR[grade],
            }}
          />
          {GRADE_LABEL[grade]}
        </span>
      ))}
    </div>
  );
}
