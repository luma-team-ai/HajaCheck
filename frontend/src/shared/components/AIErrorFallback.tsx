// AI 실패 공통 폴백 UI — React_코드_컨벤션.md §6
// 표준 문구 + 재시도 버튼. AI 실패가 화면의 비-AI 기능을 막지 않도록 호출부에서 이 컴포넌트만 교체 렌더링할 것.
import './ai-common.css';

type Props = {
  message?: string;
  onRetry: () => void;
};

const DEFAULT_MESSAGE = 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.';

export function AIErrorFallback({ message = DEFAULT_MESSAGE, onRetry }: Props) {
  return (
    <div className="ai-error-fallback" role="alert">
      <span className="ai-error-message">{message}</span>
      <button type="button" className="ai-error-retry-btn" onClick={onRetry}>
        다시 시도
      </button>
    </div>
  );
}
