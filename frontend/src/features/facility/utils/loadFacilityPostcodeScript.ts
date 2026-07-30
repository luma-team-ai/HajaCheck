// 다음(카카오) 우편번호 서비스 스크립트 동적 로드 — auth/utils/loadDaumPostcodeScript.ts와 동일 로직.
// auth 폴더는 다른 팀원 소유라 직접 import하지 않고 facility 전용으로 복제한다(#629).
// window.daum?.Postcode 존재 여부를 먼저 확인하므로, auth 쪽이 이미 스크립트를 로드해둔 경우
// 중복 <script> 삽입 없이 즉시 resolve된다.
const DAUM_POSTCODE_SCRIPT_SRC =
  '//t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js';

let loadPromise: Promise<void> | null = null;

export function loadFacilityPostcodeScript(): Promise<void> {
  if (typeof window !== 'undefined' && window.daum?.Postcode) {
    return Promise.resolve();
  }
  if (loadPromise) return loadPromise;

  loadPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = DAUM_POSTCODE_SCRIPT_SRC;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      loadPromise = null; // 실패 시 재시도 가능하도록 초기화
      reject(new Error('다음 우편번호 스크립트를 불러오지 못했습니다.'));
    };
    document.head.appendChild(script);
  });

  return loadPromise;
}
