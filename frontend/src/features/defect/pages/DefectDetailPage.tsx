import { useParams } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { ActivityHistoryPanel } from '../components/ActivityHistoryPanel';
import { DefectExplainPanel } from '../components/DefectExplainPanel';
import { DefectImageViewer } from '../components/DefectImageViewer';
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
          <h1 className="sr-only">하자 상세</h1>

          <div className="defect-summary-chips" aria-label="하자 요약">
            <span className="defect-chip defect-chip--danger"><i aria-hidden="true" />{defect.typeLabel}</span>
            <span className="defect-chip">{defect.grade ? `${defect.grade}등급` : '미분류'}</span>
            <span className="defect-chip defect-chip--warning"><i aria-hidden="true" />{DEFECT_STATUS_LABEL[defect.status]}</span>
            <span className="defect-chip">{defect.facilityName}</span>
          </div>

          <dl className="sr-only">
            <dt>등급</dt>
            <dd>
              {defect.grade ? `${defect.grade} · ${DEFECT_GRADE_LABEL[defect.grade]}` : '미분류'}
            </dd>
            <dt>상태</dt>
            <dd>{DEFECT_STATUS_LABEL[defect.status]}</dd>
          </dl>

          {/* TODO(SLA): SLA 기준 정책 미확정(PRD 미기재) — 이번 스코프에서 제외(HAJA-314). 정책 확정 후
              여기에 SLA 배지/기한 표시를 추가한다. */}

          <div className="defect-detail-layout">
            <div className="defect-detail-primary">
              <DefectImageViewer
                imageUrl={defect.imageUrl}
                typeLabel={defect.typeLabel}
                bboxX={defect.bboxX}
                bboxY={defect.bboxY}
                bboxW={defect.bboxW}
                bboxH={defect.bboxH}
              />
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
                      <strong>{defect.crackWidthMm}mm</strong>
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
              <ActivityHistoryPanel defectId={defect.id} />
            </aside>
          </div>
        </>
      )}
    </div>
  );
}
