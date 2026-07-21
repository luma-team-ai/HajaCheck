import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { useFacilityDefectExplain } from '../hooks/useFacilityDefectExplain';

type Props = {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
};

// AI 설명 패널 — AI 실패가 페이지의 비-AI 기능(하자 이미지, 상태, 등급 등)을 막지 않도록
// 이 패널 내부에서만 로딩/에러를 처리한다(React_코드_컨벤션.md §6, defect/components/DefectExplainPanel.tsx와 동일 관용구).
export function FacilityDefectAiExplainPanel({ defectId, defectType, grade, location }: Props) {
  const { data, isLoading, isError, refetch } = useFacilityDefectExplain({
    defectId,
    defectType,
    grade,
    location,
  });

  const handleRetry = () => {
    refetch();
  };

  return (
    <section className="dashboard-card">
      <div className="dashboard-card-header">
        <div className="flex items-center gap-1.5">
          <span aria-hidden="true">✨</span>
          <h3 className="dashboard-card-title m-0!">AI 설명</h3>
        </div>
        <span className="rounded-full bg-[#4a5cff] px-2 py-0.5 text-[11px] font-bold text-white">
          AI 생성 · Standard
        </span>
      </div>

      {isLoading && <AILoadingIndicator message="AI가 하자 원인과 권장 조치를 분석하고 있습니다..." />}
      {isError && <AIErrorFallback onRetry={handleRetry} />}
      {!isLoading && !isError && data && (
        <p className="m-0 text-sm leading-6 text-text-default">
          {data.diagnosis} {data.recommendedAction}
        </p>
      )}
    </section>
  );
}