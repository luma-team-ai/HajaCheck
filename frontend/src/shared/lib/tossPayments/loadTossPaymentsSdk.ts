// 토스페이먼츠 결제 SDK 로더(#989, HAJA-490) — shared/lib/kakaoMap/loadKakaoMapSdk.ts와 동일 패턴
// (싱글턴 Promise, 키 미설정 시 전용 에러 클래스로 명확히 실패). 클라이언트 키는
// import.meta.env.VITE_TOSS_CLIENT_KEY 로만 주입한다(하드코딩 금지).
import { loadTossPayments, type TossPaymentsSDK } from '@tosspayments/tosspayments-sdk';

export class TossClientKeyMissingError extends Error {
  constructor() {
    super('VITE_TOSS_CLIENT_KEY 환경변수가 설정되지 않았습니다.');
    this.name = 'TossClientKeyMissingError';
  }
}

let sdkPromise: Promise<TossPaymentsSDK> | null = null;

/**
 * 토스페이먼츠 SDK를 1회만 로드하고, 이후 호출은 동일 Promise를 재사용한다.
 * 클라이언트 키가 없으면 결제창이 조용히 뜨지 않는 대신 TossClientKeyMissingError로 즉시 실패한다
 * (KakaoMapKeyMissingError 선례와 동일 — "결제 진입점에서 명확히 실패"시켜야 함).
 */
export function loadTossPaymentsSdk(): Promise<TossPaymentsSDK> {
  if (sdkPromise) {
    return sdkPromise;
  }

  const clientKey = import.meta.env.VITE_TOSS_CLIENT_KEY;
  if (!clientKey) {
    return Promise.reject(new TossClientKeyMissingError());
  }

  sdkPromise = loadTossPayments(clientKey).catch((err) => {
    sdkPromise = null; // 실패 시 재시도 가능하도록 리셋(KakaoMapKeyMissingError 로더와 동일)
    throw err;
  });

  return sdkPromise;
}
