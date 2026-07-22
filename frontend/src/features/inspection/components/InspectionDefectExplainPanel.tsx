import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { useInspectionDefectExplain } from '../hooks/useInspectionDefectExplain';

type Props = {
  defectType: string;
  grade: string;
  facilityType: string;
};

// ponytail: defect feature 패턴과 동일. AI 실패가 뷰어의 비-AI 기능(이미지·메타데이터)을
// 막지 않도록 이 패널 내부에서만 로딩/에러를 처리한다.
export function InspectionDefectExplainPanel({ defectType, grade, facilityType }: Props) {
  const { data, isLoading, isError, refetch } = useInspectionDefectExplain({
    defectType,
    grade,
    facilityType,
  });

  if (isLoading) {
    return <AILoadingIndicator message="AI가 하자 원인과 조치 방안을 분석하고 있습니다..." />;
  }

  if (isError) {
    return <AIErrorFallback onRetry={() => void refetch()} />;
  }

  if (!data) {
    return null;
  }

  return (
    <div className="rounded-xl border border-warning-soft-border bg-warning-soft-bg p-4 text-sm text-warning-soft-fg">
      <div className="mb-4">
        <h4 className="mb-2 font-semibold text-text-default">예상 원인</h4>
        <p>{data.cause}</p>
      </div>
      <div className="mb-4">
        <h4 className="mb-2 font-semibold text-warning-soft-fg">⚠ 방치 시 위험</h4>
        <p>{data.risk}</p>
      </div>
      <div>
        <h4 className="mb-2 font-semibold text-text-default">조치 계획</h4>
        <p>{data.action}</p>
      </div>
    </div>
  );
}
