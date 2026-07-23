// Kakao Maps JavaScript SDK 최소 타입 선언 (이 feature 폴더 내부 전용, 외부 타입 패키지 설치 금지)
// 참고: https://apis.map.kakao.com/web/documentation/

export {};

declare global {
  interface Window {
    kakao: KakaoNamespace;
  }

  const kakao: KakaoNamespace;

  interface KakaoNamespace {
    maps: KakaoMapsNamespace;
  }

  interface KakaoMapsNamespace {
    load(callback: () => void): void;
    LatLng: new (lat: number, lng: number) => KakaoLatLng;
    Map: new (container: HTMLElement, options: KakaoMapOptions) => KakaoMap;
    Marker: new (options: KakaoMarkerOptions) => KakaoMarker;
    MarkerImage: new (
      src: string,
      size: KakaoSize,
      options?: { offset?: KakaoPoint },
    ) => KakaoMarkerImage;
    InfoWindow: new (options: KakaoInfoWindowOptions) => KakaoInfoWindow;
    CustomOverlay: new (options: KakaoCustomOverlayOptions) => KakaoCustomOverlay;
    Size: new (width: number, height: number) => KakaoSize;
    Point: new (x: number, y: number) => KakaoPoint;
    MapTypeId: KakaoMapTypeIdMap;
    event: {
      addListener: (
        target: KakaoMarker,
        type: string,
        handler: () => void,
      ) => void;
    };
    services: KakaoMapsServicesNamespace;
  }

  // libraries=services 로드 시에만 존재 — 주소↔좌표 변환(Geocoder) 전용 (#618)
  interface KakaoMapsServicesNamespace {
    Geocoder: new () => KakaoGeocoder;
    Status: KakaoGeocoderStatusMap;
  }

  interface KakaoGeocoder {
    addressSearch(
      address: string,
      callback: (result: KakaoGeocoderAddressResult[], status: KakaoGeocoderStatus) => void,
    ): void;
  }

  interface KakaoGeocoderAddressResult {
    // Kakao Geocoder 응답은 좌표를 문자열로 반환한다(x=경도, y=위도)
    x: string;
    y: string;
    address_name: string;
  }

  type KakaoGeocoderStatus = string & { readonly __brand: 'KakaoGeocoderStatus' };

  interface KakaoGeocoderStatusMap {
    OK: KakaoGeocoderStatus;
    ZERO_RESULT: KakaoGeocoderStatus;
    ERROR: KakaoGeocoderStatus;
  }

  interface KakaoLatLng {
    getLat(): number;
    getLng(): number;
  }

  interface KakaoSize {
    width: number;
    height: number;
  }

  interface KakaoPoint {
    x: number;
    y: number;
  }

  interface KakaoMapOptions {
    center: KakaoLatLng;
    level?: number;
  }

  interface KakaoMap {
    setCenter(latlng: KakaoLatLng): void;
    panTo(latlng: KakaoLatLng): void;
    setLevel(level: number): void;
    getLevel(): number;
    relayout(): void;
    setMapTypeId(mapTypeId: KakaoMapTypeId): void;
  }

  // 지도 타입 식별자 — ROADMAP(기본 지도) / HYBRID(위성+지명, 위성뷰 토글에서 사용)
  type KakaoMapTypeId = number & { readonly __brand: 'KakaoMapTypeId' };

  interface KakaoMapTypeIdMap {
    ROADMAP: KakaoMapTypeId;
    HYBRID: KakaoMapTypeId;
  }

  type KakaoMarkerImage = unknown;

  interface KakaoMarkerOptions {
    position: KakaoLatLng;
    map?: KakaoMap;
    title?: string;
    image?: KakaoMarkerImage;
  }

  interface KakaoMarker {
    setMap(map: KakaoMap | null): void;
    getPosition(): KakaoLatLng;
    setImage(image: KakaoMarkerImage): void;
    setZIndex(zIndex: number): void;
  }

  interface KakaoInfoWindowOptions {
    content: string | HTMLElement;
    removable?: boolean;
  }

  interface KakaoInfoWindow {
    open(map: KakaoMap, marker: KakaoMarker): void;
    close(): void;
    setContent(content: string | HTMLElement): void;
  }

  interface KakaoCustomOverlayOptions {
    position: KakaoLatLng;
    content: HTMLElement;
    yAnchor?: number;
    zIndex?: number;
  }

  interface KakaoCustomOverlay {
    setMap(map: KakaoMap | null): void;
  }
}
