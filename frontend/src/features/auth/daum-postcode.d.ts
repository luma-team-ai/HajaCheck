// 다음(카카오) 우편번호 서비스 — 외부 스크립트가 window.daum 전역에 주입하는 타입
// 참고: //t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js
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
