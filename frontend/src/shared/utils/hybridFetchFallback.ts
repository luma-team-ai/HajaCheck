import { shouldEnableMocking } from './shouldEnableMocking';

export interface HybridFallbackOptions<T> {
  fetcher: () => Promise<T>;
  fallback: T | (() => T);
  /** PROD 환경이거나 MSW 목이 꺼진 상태(VITE_ENABLE_MSW=false)인지 판별하기 위한 환경 객체 (기본값: import.meta.env) */
  env?: { DEV: boolean; VITE_ENABLE_MSW?: string };
  /** 응답이 빈 배열([])일 때 목 데이터로 폴백할지 여부 (기본값: true) */
  fallbackOnEmptyArray?: boolean;
  /** 페이지 응답처럼 배열을 감싼 응답에서 목 폴백이 필요한 빈 상태를 판정한다. */
  isEmpty?: (result: T) => boolean;
}

/**
 * 실 서버 요청(fetcher)을 1차로 수행하고, 실패(NETWORK_ERROR, 404) 또는
 * 실 DB 레코드 부재(빈 배열 data: []) 시 DEV 모드에서 MSW 목 데이터(fallback)로
 * 유연하게 전환하는 하이브리드 폴백 어댑터.
 *
 * ⚠️ 안전가드:
 * 1. PROD 빌드(!env.DEV) 및 MSW OFF(VITE_ENABLE_MSW=false) 환경에서는 어떠한 이유로도 목 데이터를 반환하지 않는다 (#213 가드, #868 P2 픽스).
 * 2. 인증/세션 및 CUD 쓰기 요청에는 적용하지 않는다 (#302 가드).
 */
export async function hybridFetchFallback<T>(
  options: HybridFallbackOptions<T>,
): Promise<T> {
  const {
    fetcher,
    fallback,
    env = import.meta.env,
    fallbackOnEmptyArray = true,
    isEmpty,
  } = options;

  // 1. PROD 모드이거나 VITE_ENABLE_MSW=false 로 목이 꺼진 개발 환경에서는 무조건 실 서버 호출 결과만 전파한다.
  if (!shouldEnableMocking(env)) {
    return await fetcher();
  }

  const resolveFallback = (): T =>
    typeof fallback === 'function' ? (fallback as () => T)() : fallback;

  try {
    const result = await fetcher();

    // 2. 실 서버 응답이 성공(200)했으나 데이터가 빈 배열인 경우,
    //    공유 Dev DB 레코드 0건 제약으로 통계 차트가 비는 것을 방지하기 위해 목 데이터로 폴백
    const emptyArray = fallbackOnEmptyArray && Array.isArray(result) && result.length === 0;
    if (emptyArray || isEmpty?.(result)) {
      return resolveFallback();
    }

    return result;
  } catch (err: unknown) {
    const code = (err as { code?: string } | null)?.code;
    const responseStatus =
      (err as { response?: { status?: number } } | null)?.response?.status ??
      (err as { status?: number } | null)?.status;

    // 3. 네트워크 에러, 404 Not Found, 502/503 게이트웨이 에러 시 목 데이터로 폴백
    const isFallbackEligible =
      code === 'NETWORK_ERROR' ||
      responseStatus === 404 ||
      responseStatus === 502 ||
      responseStatus === 503;

    if (isFallbackEligible) {
      return resolveFallback();
    }

    throw err;
  }
}
