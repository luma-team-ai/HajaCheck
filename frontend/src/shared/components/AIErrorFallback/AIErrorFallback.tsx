import { ErrorFallback } from '../ErrorFallback/ErrorFallback';

const AI_ERROR_MESSAGE = 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.';

interface AIErrorFallbackProps {
  // AI 폴백 규약(React_코드_컨벤션.md §6) — 표준 문구는 반드시 재시도 버튼과 함께 노출되어야 하므로 필수
  onRetry: () => void;
}

export function AIErrorFallback({ onRetry }: AIErrorFallbackProps) {
  return <ErrorFallback message={AI_ERROR_MESSAGE} onRetry={onRetry} retryLabel="다시 시도" />;
}
