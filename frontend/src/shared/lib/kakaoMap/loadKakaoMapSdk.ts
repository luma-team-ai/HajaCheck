// Kakao Maps SDK 동적 로더 — autoload=false, 중복 로드 방지(싱글턴 Promise)
// 키는 import.meta.env.VITE_KAKAO_MAP_APP_KEY 로만 주입 (하드코딩 금지, React_코드_컨벤션.md §10)

const SCRIPT_ID = 'kakao-map-sdk';

// SDK 스크립트가 load/error 어느 이벤트도 발화하지 않는 경우(사내망·광고 차단 확장·프록시가
// 응답을 삼키는 등)를 대비한 상한. 이 값을 넘기면 Promise를 영구 pending으로 두지 않고
// KakaoMapSdkTimeoutError로 reject해 호출부가 실패 경로를 탈 수 있게 한다(#1590 P2).
//
// 산정 근거(#1590 리뷰 P3): 이 타이머는 armTimeout(=script 삽입 직후)부터 maps.load() 콜백까지,
// 즉 「sdk.js 다운로드 + libraries=services 번들 추가 다운로드 + 파싱·초기화」 전체를 덮는다.
// 오탐(정상인데 잘림) 비용이 지연 비용보다 크다 — 잘리면 사용자는 배너만 보고 시설물이 좌표
// null로 등록돼 지도 데이터 품질이 조용히 나빠진다. 그래서 모바일 저속 회선을 감안해 넉넉히 잡는다.
export const KAKAO_MAP_SDK_LOAD_TIMEOUT_MS = 15000;

let loadPromise: Promise<void> | null = null;

// SDK가 "실제로 사용 가능한" 상태인지 — maps.load() 콜백이 끝난 뒤에만 true가 된다(#1590 리뷰 P3).
// autoload=false에서는 스크립트가 실행되는 순간 window.kakao.maps 네임스페이스는 생기지만
// services(Geocoder 등)는 maps.load() 콜백 이후에야 채워진다. 그래서 `window.kakao?.maps` 존재를
// 단축 경로(즉시 resolve) 판정에 쓰면, 타임아웃으로 loadPromise가 리셋된 뒤 스크립트가 뒤늦게
// 실행된 시점에 재호출이 조기 resolve돼 services.Geocoder에서 TypeError가 난다(#835 레이스 재발).
let sdkReady = false;

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
 * 이미 maps.load()까지 끝난 상태(sdkReady)면 즉시 resolve.
 * KAKAO_MAP_SDK_LOAD_TIMEOUT_MS 안에 load/error 어느 쪽도 발화하지 않으면
 * KakaoMapSdkTimeoutError로 reject한다(영구 pending 금지, #1590).
 */
export function loadKakaoMapSdk(): Promise<void> {
  // 1. 이미 진행 중인 로드(pending loadPromise)가 존재하면 항상 그 promise를 공유한다 (#835 P2 픽스).
  //    script.onload발화 ~ kakao.maps.load() 콜백 완료 찰나의 동시 호출 시 레이스 조기 resolve 방지.
  if (loadPromise) {
    return loadPromise;
  }

  // 2. maps.load()까지 끝나 SDK를 바로 쓸 수 있는 경우(네임스페이스 존재만으로는 부족 — 위 sdkReady 주석)
  if (sdkReady) {
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
    // maps.load() 콜백이 실행된 시점 = services까지 초기화 완료 → 이때만 sdkReady를 세운다.
    const settleResolve = () => {
      sdkReady = true;
      window.clearTimeout(timer);
      resolve();
    };
    const settleReject = (error: Error) => {
      window.clearTimeout(timer);
      reject(error);
    };

    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      // 여기서도 판정 기준은 sdkReady다 — 네임스페이스만 보고 즉시 resolve하면 maps.load() 진행
      // 중인 찰나에 services 없이 통과한다(#835 레이스). load 이벤트를 이미 놓쳤고 sdkReady도
      // false면 아래 리스너가 못 받을 수 있으나, 그 경우는 타임아웃이 걷어내고 재시도로 복구된다.
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
