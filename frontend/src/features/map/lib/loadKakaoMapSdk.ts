// Kakao Maps SDK 동적 로더 — autoload=false, 중복 로드 방지(싱글턴 Promise)
// 키는 import.meta.env.VITE_KAKAO_MAP_APP_KEY 로만 주입 (하드코딩 금지, React_코드_컨벤션.md §10)

const SCRIPT_ID = 'kakao-map-sdk';

let loadPromise: Promise<void> | null = null;

export class KakaoMapKeyMissingError extends Error {
  constructor() {
    super('VITE_KAKAO_MAP_APP_KEY 환경변수가 설정되지 않았습니다.');
    this.name = 'KakaoMapKeyMissingError';
  }
}

/**
 * Kakao Maps SDK를 1회만 로드하고, 이후 호출은 동일 Promise를 재사용한다.
 * 이미 window.kakao.maps 가 로드돼 있으면 즉시 resolve.
 */
export function loadKakaoMapSdk(): Promise<void> {
  if (loadPromise) {
    return loadPromise;
  }

  const appKey = import.meta.env.VITE_KAKAO_MAP_APP_KEY as string | undefined;
  if (!appKey) {
    return Promise.reject(new KakaoMapKeyMissingError());
  }

  if (window.kakao?.maps) {
    loadPromise = Promise.resolve();
    return loadPromise;
  }

  loadPromise = new Promise<void>((resolve, reject) => {
    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      existing.addEventListener('load', () => window.kakao.maps.load(() => resolve()));
      existing.addEventListener('error', () => reject(new Error('Kakao Maps SDK 로드에 실패했습니다.')));
      return;
    }

    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${appKey}&autoload=false`;
    script.async = true;
    script.onload = () => window.kakao.maps.load(() => resolve());
    script.onerror = () => {
      script.remove(); // 실패한 태그를 제거해야 재진입 시 새 script로 재시도 가능 (잔존 시 영구 pending)
      loadPromise = null; // 실패 시 재시도 가능하도록 리셋
      reject(new Error('Kakao Maps SDK 로드에 실패했습니다.'));
    };
    document.head.appendChild(script);
  });

  return loadPromise;
}
