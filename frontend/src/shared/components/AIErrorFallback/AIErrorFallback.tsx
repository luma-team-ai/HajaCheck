import { ErrorFallback } from '../ErrorFallback/ErrorFallback';

const AI_ERROR_MESSAGE = 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.';

interface AIErrorFallbackProps {
  onRetry?: () => void;
}

export function AIErrorFallback({ onRetry }: AIErrorFallbackProps) {
  return <ErrorFallback message={AI_ERROR_MESSAGE} onRetry={onRetry} retryLabel="다시 시도" />;
}
