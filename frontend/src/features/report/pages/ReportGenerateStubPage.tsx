import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { useInspectionResult } from '../../inspection/hooks/useInspectionResult';
import { reportApi } from '../api/reportApi';
import type { ReportDetailResponse } from '../api/reportApi';

export function ReportGenerateStubPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const inspectionId = Number(id);

  const [report, setReport] = useState<ReportDetailResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const { data: inspectionData, isLoading: isInspectionLoading } = useInspectionResult(inspectionId);

  useEffect(() => {
    const controller = new AbortController();

    const generateReport = async () => {
      setIsLoading(true);
      setError(null);
      try {
        const response = await reportApi.generateReportDraft(inspectionId);
        if (!controller.signal.aborted) {
          setReport(response.data);
        }
      } catch (err) {
        if (!controller.signal.aborted) {
          const message = err instanceof Error ? err.message : '보고서 생성에 실패했습니다.';
          setError(message);
        }
      } finally {
        if (!controller.signal.aborted) {
          setIsLoading(false);
        }
      }
    };

    if (Number.isInteger(inspectionId) && inspectionId > 0) {
      generateReport();
    }

    return () => {
      controller.abort();
    };
  }, [inspectionId]);

  const handleBackToViewer = () => {
    navigate(`/inspections/${inspectionId}/viewer`);
  };

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

  if (isLoading || isInspectionLoading) {
    return <AILoadingIndicator message="보고서를 생성 중입니다..." />;
  }

  if (error) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <AIErrorFallback onRetry={() => window.location.reload()} />
        <Button onClick={handleBackToViewer} variant="secondary">
          분석 화면으로 돌아가기
        </Button>
      </div>
    );
  }

  if (!report) {
    return <div className="p-5">보고서 데이터를 불러올 수 없습니다.</div>;
  }

  // 하자 등급별 분포 계산
  const defectDistribution = inspectionData?.defects.reduce(
    (acc, defect) => {
      acc[defect.grade] = (acc[defect.grade] || 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  ) || {};

  const progressPercent =
    inspectionData && inspectionData.totalCount > 0
      ? (inspectionData.reviewedCount / inspectionData.totalCount) * 100
      : 0;

  return (
    <div className="flex h-full flex-col gap-6 py-6 pl-6 pr-28">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-text-default">보고서 초안 생성</h1>
        <Button onClick={handleBackToViewer} variant="secondary" size="md">
          분석 화면으로 돌아가기
        </Button>
      </div>

      {/* Report Status Card */}
      <div className="rounded-3xl border border-border bg-surface p-6">
        <div className="mb-4 flex items-center gap-2">
          <div className="h-3 w-3 rounded-full bg-primary" />
          <h2 className="text-lg font-semibold text-text-default">보고서 생성 결과</h2>
        </div>

        <div className="grid grid-cols-2 gap-4">
          {/* Status */}
          <div className="rounded-2xl border border-border bg-surface-muted p-4">
            <div className="mb-2 text-sm text-text-muted">상태</div>
            <div className="text-lg font-bold text-text-default">
              {report.status === 'DRAFT' ? '초안' : '최종본'}
            </div>
          </div>

          {/* Creation Date */}
          <div className="rounded-2xl border border-border bg-surface-muted p-4">
            <div className="mb-2 text-sm text-text-muted">생성일시</div>
            <div className="text-lg font-bold text-text-default">
              {new Date(report.createdAt).toLocaleString('ko-KR')}
            </div>
          </div>

          {/* Review Completion Rate */}
          {inspectionData && (
            <div className="rounded-2xl border border-border bg-surface-muted p-4">
              <div className="mb-2 text-sm text-text-muted">검수 완료율</div>
              <div className="text-lg font-bold text-text-default">{Math.round(progressPercent)}%</div>
            </div>
          )}

          {/* Total Defects */}
          {inspectionData && (
            <div className="rounded-2xl border border-border bg-surface-muted p-4">
              <div className="mb-2 text-sm text-text-muted">총 하자 수</div>
              <div className="text-lg font-bold text-text-default">{inspectionData.totalCount}</div>
            </div>
          )}
        </div>

        {/* Progress Bar */}
        {inspectionData && (
          <div className="mt-6 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-text-muted">검수 진행률</span>
              <span className="text-sm font-semibold text-text-default">
                {inspectionData.reviewedCount} / {inspectionData.totalCount}
              </span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-border">
              <div
                className="h-full bg-primary transition-all duration-300"
                style={{ width: `${progressPercent}%` }}
              />
            </div>
          </div>
        )}

        {/* Defect Grade Distribution */}
        {Object.keys(defectDistribution).length > 0 && (
          <div className="mt-6">
            <div className="mb-4 text-sm font-medium text-text-default">하자 등급 분포</div>
            <div className="flex gap-3">
              {['A', 'B', 'C', 'D', 'E'].map((grade) => (
                <div key={grade} className="flex flex-1 flex-col gap-1">
                  <div className="rounded-lg border border-border bg-surface-muted p-2 text-center">
                    <div className="text-sm font-bold text-text-default">{grade}</div>
                  </div>
                  <div className="text-center text-xs text-text-muted">
                    {defectDistribution[grade] || 0}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Grounding Check Status */}
        {report.groundingCheckPassed !== null && report.groundingCheckPassed !== undefined && (
          <div className="mt-6 rounded-lg bg-info-soft-bg p-3">
            <div className="text-sm text-info-soft-fg">
              {report.groundingCheckPassed ? '✓ 검증 완료' : '⚠ 검증 대기 중'}
            </div>
          </div>
        )}
      </div>

      {/* NOTE: Stub notice */}
      <div className="rounded-lg bg-yellow-50 p-4 text-sm text-yellow-800">
        <strong>알림:</strong> 이 화면은 임시 화면입니다. 향후 보고서 편집 및 PDF 내보내기 기능이 추가될 예정입니다.
      </div>
    </div>
  );
}
