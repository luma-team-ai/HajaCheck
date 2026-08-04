// Kakao Maps SDK 동적 로더 — autoload=false, 중복 로드 방지(싱글턴 Promise)
// 키는 import.meta.env.VITE_KAKAO_MAP_APP_KEY 로만 주입 (하드코딩 금지, React_코드_컨벤션.md §10)

const SCRIPT_ID = 'kakao-map-sdk';

// SDK 스크립트가 load/error 어느 이벤트도 발화하지 않는 경우(사내망·광고 차단 확장·프록시가
// 응답을 삼키는 등)를 대비한 상한. 이 값을 넘기면 Promise를 영구 pending으로 두지 않고
// KakaoMapSdkTimeoutError로 reject해 호출부가 실패 경로를 탈 수 있게 한다(#1590 P2).
export const KAKAO_MAP_SDK_LOAD_TIMEOUT_MS = 8000;

let loadPromise: Promise<void> | null = null;

export class KakaoMapKeyMissingError extends Error {
  constructor() {
    super('VITE_KAKAO_MAP_APP_KEY 환경변수가 설정되지 않았습니다.');
    this.name = 'KakaoMapKeyMissingError';
  }
}

/** 스크립트가 제한 시간 안에 load/error 어느 쪽도 발화하지 않은 경우(#1590) */
export class KakaoMapSdkTimeoutError extends Error {
  constructor(timeoutMs: number = KAKAO_MAP_SDK_LOAD_TIMEOUT_MS) {
    super(`Kakao Maps SDK 로드가 ${timeoutMs}ms 안에 완료되지 않았습니다.`);
    this.name = 'KakaoMapSdkTimeoutError';
  }
}

/**
 * Kakao Maps SDK를 1회만 로드하고, 이후 호출은 동일 Promise를 재사용한다.
 * 이미 window.kakao.maps 가 로드돼 있으면 즉시 resolve.
 * KAKAO_MAP_SDK_LOAD_TIMEOUT_MS 안에 load/error 어느 쪽도 발화하지 않으면
 * KakaoMapSdkTimeoutError로 reject한다(영구 pending 금지, #1590).
 */
export function loadKakaoMapSdk(): Promise<void> {
  // 1. 이미 진행 중인 로드(pending loadPromise)가 존재하면 항상 그 promise를 공유한다 (#835 P2 픽스).
  //    script.onload발화 ~ kakao.maps.load() 콜백 완료 찰나의 동시 호출 시 레이스 조기 resolve 방지.
  if (loadPromise) {
    return loadPromise;
  }

  // 2. 스크립트가 완전히 로드되어 window.kakao.maps 네임스페이스가 존재하는 경우
  if (window.kakao?.maps) {
    loadPromise = Promise.resolve();
    return loadPromise;
  }

  const appKey = import.meta.env.VITE_KAKAO_MAP_APP_KEY as string | undefined;
  if (!appKey) {
    return Promise.reject(new KakaoMapKeyMissingError());
  }

  loadPromise = new Promise<void>((resolve, reject) => {
    // 타임아웃 타이머 — settle(resolve/reject) 시 반드시 해제한다. 만료 시엔 script 태그를 제거해
    // 다음 호출이 새 script로 재시도할 수 있게 한다(잔존 시 재진입도 같은 hang을 반복).
    let timer = 0;
    const armTimeout = (script: HTMLScriptElement) => {
      timer = window.setTimeout(() => {
        script.remove();
        reject(new KakaoMapSdkTimeoutError());
      }, KAKAO_MAP_SDK_LOAD_TIMEOUT_MS);
    };
    const settleResolve = () => {
      window.clearTimeout(timer);
      resolve();
    };
    const settleReject = (error: Error) => {
      window.clearTimeout(timer);
      reject(error);
    };

    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      // load 이벤트가 리스너 등록 이전에 이미 발화됐을 수 있으므로 kakao.maps 존재 여부를 먼저 확인
      if (window.kakao?.maps) {
        resolve();
        return;
      }
      armTimeout(existing);
      existing.addEventListener('load', () => window.kakao.maps.load(() => settleResolve()));
      existing.addEventListener('error', () => {
        existing.remove();
        loadPromise = null; // 실패 시 재시도 가능하도록 리셋 (new script 경로와 동일 처리)
        settleReject(new Error('Kakao Maps SDK 로드에 실패했습니다.'));
      });
      return;
    }

    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    // libraries=services: Geocoder(주소↔좌표 변환) 사용을 위해 필요 (#618)
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(appKey)}&autoload=false&libraries=services`;
    script.async = true;
    script.onload = () => window.kakao.maps.load(() => settleResolve());
    script.onerror = () => {
      script.remove(); // 실패한 태그를 제거해야 재진입 시 새 script로 재시도 가능 (잔존 시 영구 pending)
      loadPromise = null; // 실패 시 재시도 가능하도록 리셋
      settleReject(new Error('Kakao Maps SDK 로드에 실패했습니다.'));
    };
    document.head.appendChild(script);
    armTimeout(script);
  }).catch((err) => {
    loadPromise = null;
    throw err;
  });

  return loadPromise;
}
