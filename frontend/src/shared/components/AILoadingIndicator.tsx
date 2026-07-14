// AI 응답 대기 공통 UI — React_코드_컨벤션.md §6 (스켈레톤/스피너 통일)
import './ai-common.css';

type Props = {
  message?: string;
};

const DEFAULT_MESSAGE = 'AI가 분석하고 있습니다...';

export function AILoadingIndicator({ message = DEFAULT_MESSAGE }: Props) {
  return (
    <div className="ai-loading-indicator" role="status" aria-live="polite">
      <span className="ai-loading-spinner" aria-hidden="true" />
      <span className="ai-loading-message">{message}</span>
    </div>
  );
}
