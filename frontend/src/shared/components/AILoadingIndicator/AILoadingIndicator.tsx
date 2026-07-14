import './AILoadingIndicator.css';

interface AILoadingIndicatorProps {
  message?: string;
}

// prop 이름은 dev의 기존 사용부(AiBriefingCard: <AILoadingIndicator message="..." />)와
// 호환되도록 message로 맞춤(과거 flat 버전과 동일 API)
export function AILoadingIndicator({ message = 'AI 분석 중입니다...' }: AILoadingIndicatorProps) {
  return (
    <div className="ai-loading" role="status" aria-live="polite">
      <span className="ai-loading-spinner" aria-hidden="true" />
      <div className="ai-loading-skeleton">
        <span className="ai-loading-bar" />
        <span className="ai-loading-bar ai-loading-bar--short" />
      </div>
      <p className="ai-loading-label">{message}</p>
    </div>
  );
}
