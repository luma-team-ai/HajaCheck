import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { useDefectExplain } from '../hooks/useDefectExplain';

type Props = {
  defect_type: string;
  severity_grade: string;
  location: string;
  facility_type: string;
};

// AI 설명 패널 — AI 실패가 페이지의 비-AI 기능(하자 이미지, 상태, 등급 등)을 막지 않도록
// 이 패널 내부에서만 로딩/에러를 처리한다.
export function DefectExplainPanel({
  defect_type,
  severity_grade,
  location,
  facility_type,
}: Props) {
  const { data, isLoading, isError, refetch } = useDefectExplain({
    defect_type,
    severity_grade,
    location,
    facility_type,
  });

  const handleRetry = () => {
    refetch();
  };

  return (
    <section className="defect-explain-panel">
      <div className="defect-explain-header">
        <span className="ai-icon" aria-hidden="true">
          ✨
        </span>
        <span className="ai-badge">AI 생성</span>
        <h3>AI 설명</h3>
      </div>

      {isLoading && <AILoadingIndicator message="AI가 하자 원인과 조치 방안을 분석하고 있습니다..." />}
      {isError && <AIErrorFallback onRetry={handleRetry} />}
      {!isLoading && !isError && data && (
        <div className="defect-explain-content">
          <div className="defect-explain-section">
            <h4>추정 원인</h4>
            <p>{data.cause}</p>
          </div>
          <div className="defect-explain-section">
            <h4>방치 시 위험</h4>
            <p>{data.risk}</p>
          </div>
          <div className="defect-explain-section">
            <h4>조치 방안</h4>
            <p>{data.action}</p>
          </div>
        </div>
      )}
    </section>
  );
}
