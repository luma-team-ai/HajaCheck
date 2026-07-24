import { useEffect } from 'react';
// defect-chip / defect-metrics / defect-metric-card 등은 DefectDetailPage.css에 정의돼 있다 — 이
// 모달이 렌더링되는 InspectionDefectsPage는 그 CSS를 별도로 로드하지 않으므로 함께 임포트해 스타일을
// 재사용한다(신규 스타일 중복 정의 금지).
import '../pages/DefectDetailPage.css';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { useDefect } from '../hooks/useDefect';
import { DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL } from '../types';
import { formatDefectCode } from '../utils/defectFormat';
import { ActivityHistoryPanel } from './ActivityHistoryPanel';
import { DefectActionForm } from './DefectActionForm';
import { DefectExplainPanel } from './DefectExplainPanel';
import { DefectImageViewer } from './DefectImageViewer';

type Props = {
  defectId: number;
  onClose: () => void;
};

// 하자 상세 모달(HAJA-393/394 §화면 구조 ③, contract.md 확정) — 사이드바(SideNavBar)/헤더(Header)를
// 침범하면 안 되므로 shared/components/Modal(createPortal + position:fixed 전체 뷰포트 오버레이)을
// 쓰지 않는다. 대신 호출부(InspectionDefectsPage)가 position:relative인 페이지 컨테이너 안에서 이
// 컴포넌트를 position:absolute; inset:0으로 렌더링한다 — 별도 fullscreen 오버레이 메커니즘을 새로
// 만들지 말라는 handoff 지시에 따른 의도적 설계(공통 Modal 재사용 원칙의 예외).
export function DefectDetailModal({ defectId, onClose }: Props) {
  const { data: defect, isLoading, isError, refetch } = useDefect(defectId);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <div className="defect-detail-modal" role="dialog" aria-modal="true" aria-label="하자 상세">
      <div className="defect-detail-modal__overlay" onClick={onClose} aria-hidden="true" />
      <div className="defect-detail-modal__panel">
        <button
          type="button"
          className="defect-detail-modal__close"
          aria-label="닫기"
          onClick={onClose}
        >
          ✕
        </button>

        {isLoading && (
          <div className="defect-detail-modal__loading" role="status">
            불러오는 중...
          </div>
        )}

        {isError && <ErrorFallback message="하자 정보를 불러오지 못했습니다." onRetry={refetch} />}

        {!isLoading && !isError && defect && (
          <>
            <header className="defect-detail-modal__header">
              <span className="defect-detail-modal__code">{formatDefectCode(defect.id)}</span>
              <span className="defect-chip">{defect.typeLabel}</span>
              <span className="defect-chip">
                {defect.grade ? `${defect.grade}등급 · ${DEFECT_GRADE_LABEL[defect.grade]}` : '미분류'}
              </span>
              <span className="defect-chip defect-chip--warning">
                <i aria-hidden="true" />
                {DEFECT_STATUS_LABEL[defect.status]}
              </span>
            </header>

            <div className="defect-detail-modal__body">
              <div className="defect-detail-modal__primary">
                <DefectImageViewer
                  imageUrl={defect.imageUrl}
                  typeLabel={defect.typeLabel}
                  bboxX={defect.bboxX}
                  bboxY={defect.bboxY}
                  bboxW={defect.bboxW}
                  bboxH={defect.bboxH}
                />

                <div className="defect-metrics">
                  <article className="defect-metric-card">
                    <span>AI 신뢰도</span>
                    <strong>
                      {Math.round(defect.confidence * 100)} <small>%</small>
                    </strong>
                  </article>
                  <article className="defect-metric-card">
                    <span>균열 폭(최대)</span>
                    <strong>
                      {defect.crackWidthMm != null ? (
                        `${defect.crackWidthMm}mm`
                      ) : (
                        <span className="defect-metric-empty">정보 없음</span>
                      )}
                    </strong>
                  </article>
                  <article className="defect-metric-card">
                    <span>균열 길이(추정)</span>
                    <strong>
                      {defect.crackLengthMm != null ? (
                        `${defect.crackLengthMm}mm`
                      ) : (
                        <span className="defect-metric-empty">정보 없음</span>
                      )}
                    </strong>
                  </article>
                </div>

                <DefectExplainPanel
                  defect_type={defect.typeLabel}
                  severity_grade={defect.grade ?? '미분류'}
                  location={defect.facilityName}
                  facility_type={defect.facilityType}
                />
              </div>

              <div className="defect-detail-modal__secondary">
                <DefectActionForm
                  defectId={defect.id}
                  inspectionId={defect.inspectionId}
                  actionResult={defect.actionResult}
                />
              </div>
            </div>

            <ActivityHistoryPanel key={defect.id} defectId={defect.id} />
          </>
        )}
      </div>
    </div>
  );
}
