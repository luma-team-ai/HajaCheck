import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

// AI 실패가 대시보드의 비-AI 위젯을 막지 않아야 함(React_코드_컨벤션.md §6) — 이 훅의 에러는
// AiBriefingCard 내부에서만 폴백 처리하고 다른 위젯 훅과 독립적으로 동작한다.
export function useAiBriefing() {
  return useQuery({
    queryKey: ['dashboard', 'ai-briefing'],
    queryFn: () => dashboardApi.getBriefing().then((res) => res.data),
    retry: 1,
  });
}
