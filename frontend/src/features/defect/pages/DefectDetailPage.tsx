import { useParams } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { DefectExplainPanel } from '../components/DefectExplainPanel';
import { DefectStatusStepper } from '../components/DefectStatusStepper';
import { useDefect } from '../hooks/useDefect';
import { useUpdateDefectStatus } from '../hooks/useUpdateDefectStatus';
import { DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';
import './DefectDetailPage.css';

export function DefectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const defectId = id != null ? Number(id) : undefined;
  const { data: defect, isLoading, isError, refetch } = useDefect(defectId);
  const { updateStatus, isPending: isStatusUpdating, error: statusError } =
    useUpdateDefectStatus(defectId);

  const handleAdvance = (next: DefectStatus) => {
    updateStatus(next).catch(() => {});
  };

  return (
    <div className="defect-detail-page">
      {isLoading && (
        <div className="flex items-center justify-center px-4 py-12 text-sm text-text-muted" role="status">
          불러오는 중...
        </div>
      )}

      {isError && <ErrorFallback message="하자 정보를 불러오지 못했습니다." onRetry={refetch} />}

      {!isLoading && !isError && defect && (
        <>
          <div className="defect-summary-chips" aria-label="하자 요약">
            <span className="defect-chip defect-chip--danger"><i aria-hidden="true" />{defect.typeLabel}</span>
            <span className="defect-chip">{defect.grade ? `${defect.grade}등급` : '미분류'}</span>
            <span className="defect-chip defect-chip--warning"><i aria-hidden="true" />{DEFECT_STATUS_LABEL[defect.status]}</span>
          </div>

          <dl className="sr-only">
            <dt>등급</dt>
            <dd>
              {defect.grade ? `${defect.grade} · ${DEFECT_GRADE_LABEL[defect.grade]}` : '미분류'}
            </dd>
            <dt>위치</dt>
            <dd>{defect.facilityName}</dd>
            <dt>상태</dt>
            <dd>{DEFECT_STATUS_LABEL[defect.status]}</dd>
          </dl>

          <div className="defect-detail-layout">
            <div className="defect-detail-primary">
              <DefectStatusStepper
                status={defect.status}
                onAdvance={handleAdvance}
                isPending={isStatusUpdating}
                errorMessage={statusError?.message}
              />
            </div>

            <aside className="defect-detail-sidebar" aria-label="하자 분석 정보">
              <div className="defect-metrics">
                <article className="defect-metric-card">
                  <span>AI 신뢰도</span>
                  <strong>{Math.round(defect.confidence * 100)} <small>%</small></strong>
                </article>
                <article className="defect-metric-card">
                  <span>가로 · 세로</span>
                  {defect.crackWidthMm != null && defect.crackLengthMm != null ? (
                    <>
                      <strong>{defect.crackWidthMm}</strong>
                      <em>· &nbsp;&nbsp;{defect.crackLengthMm}mm</em>
                    </>
                  ) : (
                    <strong className="defect-metric-empty">정보 없음</strong>
                  )}
                </article>
              </div>

              <DefectExplainPanel
                defect_type={defect.typeLabel}
                severity_grade={defect.grade ?? '미분류'}
                location={defect.facilityName}
                facility_type={defect.facilityType}
              />
            </aside>
          </div>
        </>
      )}
    </div>
  );
}
