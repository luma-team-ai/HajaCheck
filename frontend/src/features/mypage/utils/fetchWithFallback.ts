// 대시보드(features/dashboard/utils/fetchWithFallback.ts)와 목적은 같다 — 백엔드 미배포 시 예제 데이터로
// 폴백해 화면 렌더를 보장한다. 다만 판정 기준은 다르다: 마이페이지 계약(contract.md)의 404는
// PLAN_NOT_FOUND(활성 구독 없음)라는 정상 도메인 상태를 뜻할 수 있어, status===404를 그대로 폴백 조건으로
// 쓰면 실제 "구독 없음" 응답까지 예제 데이터로 가려버린다.
// 그래서 이 유틸은 shared/api/axios.ts 인터셉터가 붙이는 기본 코드 'NETWORK_ERROR'(응답 바디가 없는 진짜
// 네트워크 오류·백엔드 미기동)일 때만 폴백하고, PLAN_NOT_FOUND·PLAN_FORBIDDEN 등 계약에 정의된 도메인 에러는
// 그대로 던져 훅 호출부가 error.code 기반으로 분기하게 한다.
export async function fetchWithFallback<T>(fetcher: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fetcher();
  } catch (err) {
    const code = (err as { code?: string } | null)?.code;
    if (code === 'NETWORK_ERROR') {
      return fallback;
    }
    throw err;
  }
}
