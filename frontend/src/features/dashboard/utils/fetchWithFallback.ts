// 백엔드 미구현 시 예제 데이터 폴백 — 대시보드 4종 데이터 위젯 전용(HAJA-17)
// 실 API 호출이 실패(404·네트워크 오류 등)하면 mocks/dashboard.mock.ts의 예제 데이터로 대체해
// 백엔드 미구현 상태에서도 위젯이 항상 렌더되도록 한다.
// AI 브리핑(useAiBriefing)에는 적용하지 않음 — 그쪽은 React_코드_컨벤션.md §6 에러 UI(재시도)로 처리한다.
export async function fetchWithFallback<T>(fetcher: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fetcher();
  } catch {
    return fallback;
  }
}
