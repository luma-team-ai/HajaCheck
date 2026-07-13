import type { ReactNode } from 'react';
import { Button } from '../Button/Button';
import './ErrorFallback.css';

interface ErrorFallbackProps {
  message?: string;
  onRetry?: () => void;
  retryLabel?: string;
  children?: ReactNode;
}

export function ErrorFallback({
  message = '오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  onRetry,
  retryLabel = '다시 시도',
  children,
}: ErrorFallbackProps) {
  return (
    <div className="error-fallback" role="alert">
      <p className="error-fallback-message">{message}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          {retryLabel}
        </Button>
      )}
      {children}
    </div>
  );
}
