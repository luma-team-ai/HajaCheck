// 다음(카카오) 우편번호 서비스 — 외부 스크립트가 window.daum 전역에 주입하는 타입
// 참고: //t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js
// auth/daum-postcode.d.ts와 동일 내용 — auth 폴더는 다른 팀원 소유라 직접 import하지 않고
// facility 전용으로 복제한다(#629). 두 declare global 블록은 구조가 동일해 병합(merge)된다.
export interface DaumPostcodeResult {
  roadAddress: string;
  jibunAddress: string;
  zonecode: string;
}

export interface DaumPostcodeOptions {
  oncomplete: (data: DaumPostcodeResult) => void;
}

declare global {
  interface Window {
    daum?: {
      Postcode: new (options: DaumPostcodeOptions) => { open: () => void };
    };
  }
}
