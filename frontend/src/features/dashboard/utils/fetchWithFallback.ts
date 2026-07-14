// TODO(HAJA-17): 백엔드 대시보드 API 구현 완료 후 이 폴백 유틸을 제거
// 백엔드 미구현 시 예제 데이터 폴백 — 대시보드 4종 데이터 위젯 전용(HAJA-17)
// 실 API 호출이 404(백엔드 미구현)로 실패하면 mocks/dashboard.mock.ts의 예제 데이터로 대체해
// 백엔드 미구현 상태에서도 위젯이 항상 렌더되도록 한다.
// 404 외의 에러(네트워크 오류·5xx 등)는 실제 장애이므로 예제 데이터로 가리지 않고 로깅 후 그대로 던져
// react-query의 isError 상태(카드 컴포넌트의 에러 표시)로 이어지게 한다.
// AI 브리핑(useAiBriefing)에는 적용하지 않음 — 그쪽은 React_코드_컨벤션.md §6 에러 UI(재시도)로 처리한다.
export async function fetchWithFallback<T>(fetcher: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fetcher();
  } catch (err) {
    const status = (err as { status?: number } | null)?.status;
    if (status === 404) {
      return fallback;
    }
    console.error('[dashboard] API 호출 실패 — 예제 데이터로 대체하지 않고 에러를 전파합니다.', err);
    throw err;
  }
}
