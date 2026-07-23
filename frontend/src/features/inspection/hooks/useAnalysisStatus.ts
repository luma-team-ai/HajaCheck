import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';

const POLL_INTERVAL_MS = 2000;

// AI 분석 실행/상태(dev-05-04) — 진행 중일 때만 폴링하고 완료(done)되면 멈춘다. 실패건이 있어도
// 잡 자체는 계속 진행 중일 수 있어(이미지 1장 실패는 나머지에 영향 없음) stage==='done'만으로 멈춤을
// 판단한다(failedCount는 폴링 중단 조건이 아님).
export function useAnalysisStatus(inspectionId: number | null) {
  return useQuery({
    queryKey: ['inspection', 'analysis-status', inspectionId] as const,
    queryFn: () => inspectionApi.getAnalysisStatus(inspectionId as number).then((res) => res.data),
    enabled: inspectionId !== null,
    refetchInterval: (query) => (query.state.data?.stage === 'done' ? false : POLL_INTERVAL_MS),
  });
}
