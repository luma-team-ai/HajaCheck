import './AILoadingIndicator.css';

interface AILoadingIndicatorProps {
  label?: string;
}

export function AILoadingIndicator({ label = 'AI 분석 중입니다...' }: AILoadingIndicatorProps) {
  return (
    <div className="ai-loading" role="status" aria-live="polite">
      <span className="ai-loading-spinner" aria-hidden="true" />
      <div className="ai-loading-skeleton">
        <span className="ai-loading-bar" />
        <span className="ai-loading-bar ai-loading-bar--short" />
      </div>
      <p className="ai-loading-label">{label}</p>
    </div>
  );
}
