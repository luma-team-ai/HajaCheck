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
    event: {
      addListener: (
        target: KakaoMarker,
        type: string,
        handler: () => void,
      ) => void;
    };
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
    setLevel(level: number): void;
    getLevel(): number;
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
  }

  interface KakaoCustomOverlay {
    setMap(map: KakaoMap | null): void;
  }
}
