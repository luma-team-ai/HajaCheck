import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { useFacilityDefectExplain } from '../hooks/useFacilityDefectExplain';

type Props = {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
  // 시설물 조회(useFacility)가 진행 중인 동안엔 useFacilityDefectExplain의 조회 자체가 대기하므로
  // (enabled=false) react-query의 isLoading은 false로 유지된다 — 이 값을 함께 받아 로딩 인디케이터를
  // 표시해야 그 사이 패널이 빈 화면으로 비지 않는다.
  isFacilityLoading?: boolean;
};

// AI 설명 패널 — AI 실패가 페이지의 비-AI 기능(하자 이미지, 상태, 등급 등)을 막지 않도록
// 이 패널 내부에서만 로딩/에러를 처리한다(React_코드_컨벤션.md §6, defect/components/DefectExplainPanel.tsx와 동일 관용구).
export function FacilityDefectAiExplainPanel({
  defectId,
  defectType,
  grade,
  location,
  facilityType,
  isFacilityLoading = false,
}: Props) {
  const { data, isLoading, isError, refetch } = useFacilityDefectExplain({
    defectId,
    defectType,
    grade,
    location,
    facilityType,
    isFacilityLoading,
  });

  const handleRetry = () => {
    refetch();
  };

  const showLoading = isFacilityLoading || isLoading;

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

      {showLoading && <AILoadingIndicator message="AI가 하자 원인과 권장 조치를 분석하고 있습니다..." />}
      {isError && <AIErrorFallback onRetry={handleRetry} />}
      {!showLoading && !isError && data && (
        <div className="flex flex-col gap-3 text-sm leading-6 text-text-default">
          <p className="m-0">
            <span className="font-semibold">예상 원인</span> {data.cause}
          </p>
          <p className="m-0">
            <span className="font-semibold">⚠ 방치 시 위험</span> {data.risk}
          </p>
          <p className="m-0">
            <span className="font-semibold">조치 권고</span> {data.action}
          </p>
        </div>
      )}
    </section>
  );
}