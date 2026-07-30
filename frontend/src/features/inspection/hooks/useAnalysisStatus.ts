import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';

const POLL_INTERVAL_MS = 2000;

const TERMINAL_STAGES: ReadonlySet<string> = new Set(['done', 'failed']);

// 폴링을 멈춰야 하는지 판단하는 순수 함수로 분리했다(코드 리뷰 P2) — refetchInterval 콜백 안에
// 인라인으로 두면 실제 폴링 타이밍을 흉내내야만 테스트할 수 있어 훅 테스트가 느리고 불안정해진다.
export function isAnalysisPollingTerminal(stage: string | undefined): boolean {
  return stage !== undefined && TERMINAL_STAGES.has(stage);
}

// AI 분석 실행/상태(dev-05-04) — 진행 중일 때만 폴링하고 종료(done|failed)되면 멈춘다. 실패건이
// 있어도 잡 자체는 계속 진행 중일 수 있어(이미지 1장 실패는 나머지에 영향 없음) failedCount는 폴링
// 중단 조건이 아니다 — stage가 'failed'인 경우(코드 리뷰 P2, 이미지 전체 실패로 워커가 롤백한
// 경우)만 종료로 본다. 예전엔 'done'만 봐서 실패 잡이 영원히 폴링되며 "진행 중 0%"로 보였다.
export function useAnalysisStatus(inspectionId: number | null) {
  return useQuery({
    queryKey: ['inspection', 'analysis-status', inspectionId] as const,
    queryFn: () => inspectionApi.getAnalysisStatus(inspectionId as number).then((res) => res.data),
    enabled: inspectionId !== null,
    refetchInterval: (query) => (isAnalysisPollingTerminal(query.state.data?.stage) ? false : POLL_INTERVAL_MS),
  });
}
