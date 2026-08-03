import { useEffect, useRef, useState } from 'react';
import '../pages/DefectDetailPage.css';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { useDefect } from '../hooks/useDefect';
import { useDefectActionLogs } from '../hooks/useDefectActionLogs';
import { DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL } from '../types';
import type { InspectionDefect } from '../types';
import { formatDefectCode } from '../utils/defectFormat';
import { ActivityHistoryPanel } from './ActivityHistoryPanel';
import { DefectActionForm } from './DefectActionForm';
import { DefectExplainPanel } from './DefectExplainPanel';
import { DefectImageViewer } from './DefectImageViewer';

type Props = {
  defects: InspectionDefect[];
  initialDefectId: number;
  onClose: () => void;
};

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
}

export function DefectDetailModal({ defects, initialDefectId, onClose }: Props) {
  const [selectedDefectId, setSelectedDefectId] = useState(initialDefectId);
  const selectedDefect =
    defects.find((item) => item.id === selectedDefectId) ?? defects[0];
  const { data, isLoading, isError, refetch } = useDefect(selectedDefect.id);
  const detailDefect = data?.id === selectedDefect.id ? data : null;
  const [activePhotoTab, setActivePhotoTab] = useState<'before' | 'inProgress' | 'resolved'>('before');
  const [selectedInProgressLogId, setSelectedInProgressLogId] = useState<number | null>(null);
  const [selectedResolvedLogId, setSelectedResolvedLogId] = useState<number | null>(null);
  const { data: inProgressLogsData } = useDefectActionLogs(selectedDefect.id, 'IN_PROGRESS');
  const { data: resolvedLogsData } = useDefectActionLogs(selectedDefect.id, 'RESOLVED');
  const inProgressLogs = inProgressLogsData ?? [];
  const resolvedLogs = resolvedLogsData ?? [];
  const selectedInProgressLog =
    inProgressLogs.find((log) => log.id === selectedInProgressLogId) ?? inProgressLogs[0] ?? null;
  const selectedResolvedLog =
    resolvedLogs.find((log) => log.id === selectedResolvedLogId) ?? resolvedLogs[0] ?? null;
  const activeTabLogs =
    activePhotoTab === 'inProgress' ? inProgressLogs : activePhotoTab === 'resolved' ? resolvedLogs : [];
  const activeTabLog =
    activePhotoTab === 'inProgress'
      ? selectedInProgressLog
      : activePhotoTab === 'resolved'
        ? selectedResolvedLog
        : null;
  const panelRef = useRef<HTMLDivElement>(null);
  const previousActiveElementRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  const handleSelectDefect = (defectId: number) => {
    if (defectId === selectedDefect.id) return;
    setActivePhotoTab('before');
    setSelectedInProgressLogId(null);
    setSelectedResolvedLogId(null);
    setSelectedDefectId(defectId);
  };

  useEffect(() => {
    previousActiveElementRef.current = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    if (panel) {
      const [firstFocusable] = getFocusableElements(panel);
      (firstFocusable ?? panel).focus();
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;

      const focusable = getFocusableElements(panel);
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !panel.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      previousActiveElementRef.current?.focus();
    };
  }, []);

  return (
    <div className="defect-detail-modal" role="dialog" aria-modal="true" aria-label="하자 상세">
      <div className="defect-detail-modal__overlay" onClick={onClose} aria-hidden="true" />
      <div className="defect-detail-modal__panel" ref={panelRef} tabIndex={-1}>
        <button type="button" className="defect-detail-modal__close" aria-label="닫기" onClick={onClose}>
          ×
        </button>

        <header className="defect-detail-modal__header">
          <h2 className="defect-detail-modal__code">{formatDefectCode(selectedDefect.id)}</h2>
          <span className="defect-chip">{selectedDefect.typeLabel}</span>
          <span className="defect-chip">
            {selectedDefect.grade
              ? `${selectedDefect.grade}등급 · ${DEFECT_GRADE_LABEL[selectedDefect.grade]}`
              : '미분류'}
          </span>
          <span className="defect-chip defect-chip--warning">
            <i aria-hidden="true" />
            {DEFECT_STATUS_LABEL[selectedDefect.status]}
          </span>
        </header>

        <div className="defect-detail-modal__body">
          <div className="defect-detail-modal__primary">
            <div className="defect-detail-modal__media-row">
              <div className="defect-detail-modal__image-col">
                <div className="defect-detail-modal__photo-tab-row">
                  <div className="defect-detail-modal__photo-tabs" role="tablist" aria-label="조치 전/조치/조치 완료 사진">
                    <button
                      type="button"
                      role="tab"
                      aria-selected={activePhotoTab === 'before'}
                      className={`defect-detail-modal__photo-tab${activePhotoTab === 'before' ? ' is-active' : ''}`}
                      onClick={() => setActivePhotoTab('before')}
                    >
                      조치 전 사진
                    </button>
                    {inProgressLogs.length > 0 && (
                      <button
                        type="button"
                        role="tab"
                        aria-selected={activePhotoTab === 'inProgress'}
                        className={`defect-detail-modal__photo-tab${activePhotoTab === 'inProgress' ? ' is-active' : ''}`}
                        onClick={() => setActivePhotoTab('inProgress')}
                      >
                        조치 사진
                      </button>
                    )}
                    {resolvedLogs.length > 0 && (
                      <button
                        type="button"
                        role="tab"
                        aria-selected={activePhotoTab === 'resolved'}
                        className={`defect-detail-modal__photo-tab${activePhotoTab === 'resolved' ? ' is-active' : ''}`}
                        onClick={() => setActivePhotoTab('resolved')}
                      >
                        조치 완료 사진
                      </button>
                    )}
                  </div>
                  {activeTabLogs.length > 1 && (
                    <select
                      className="defect-detail-modal__photo-log-select"
                      aria-label="회차 선택"
                      value={activeTabLog?.id ?? ''}
                      onChange={(event) => {
                        const logId = Number(event.target.value);
                        if (activePhotoTab === 'inProgress') setSelectedInProgressLogId(logId);
                        if (activePhotoTab === 'resolved') setSelectedResolvedLogId(logId);
                      }}
                    >
                      {activeTabLogs.map((log, index) => (
                        <option key={log.id} value={log.id}>
                          {activeTabLogs.length - index}회차 · {log.actionDate}
                        </option>
                      ))}
                    </select>
                  )}
                </div>

                {activePhotoTab !== 'before' && activeTabLog ? (
                  <DefectImageViewer
                    imageUrl={activeTabLog.photoUrl}
                    typeLabel={selectedDefect.typeLabel}
                    defects={defects}
                    selectedDefectId={selectedDefect.id}
                    onSelectDefect={handleSelectDefect}
                    showDetectionBoxes={false}
                  />
                ) : (
                  <DefectImageViewer
                    imageUrl={selectedDefect.detailUrl ?? selectedDefect.imageUrl}
                    typeLabel={selectedDefect.typeLabel}
                    defects={defects}
                    selectedDefectId={selectedDefect.id}
                    onSelectDefect={handleSelectDefect}
                  />
                )}
              </div>

              <div className="defect-metrics">
                <article className="defect-metric-card">
                  <span>AI 신뢰도</span>
                  <strong>{Math.round(selectedDefect.confidence * 100)} <small>%</small></strong>
                </article>
                <article className="defect-metric-card">
                  <span>균열 폭(최대)</span>
                  <strong>
                    {selectedDefect.crackWidthMm != null
                      ? `${selectedDefect.crackWidthMm}mm`
                      : <span className="defect-metric-empty">정보 없음</span>}
                  </strong>
                </article>
                <article className="defect-metric-card">
                  <span>균열 길이(추정)</span>
                  <strong>
                    {selectedDefect.crackLengthMm != null
                      ? `${selectedDefect.crackLengthMm}mm`
                      : <span className="defect-metric-empty">정보 없음</span>}
                  </strong>
                </article>
              </div>
            </div>

            {isLoading && (
              <div className="defect-detail-modal__detail-state" role="status" aria-live="polite">
                상세 정보를 불러오는 중...
              </div>
            )}
            {isError && (
              <div className="defect-detail-modal__detail-state" aria-label="선택 하자 상세 오류">
                <ErrorFallback message="하자 상세 정보를 불러오지 못했습니다." onRetry={refetch} />
              </div>
            )}
            {!isLoading && !isError && detailDefect && (
              <DefectExplainPanel
                defect_type={detailDefect.typeLabel}
                severity_grade={detailDefect.grade ?? '미분류'}
                location={detailDefect.facilityName}
                facility_type={detailDefect.facilityType}
              />
            )}
          </div>

          <div className="defect-detail-modal__secondary" aria-busy={isLoading}>
            {isLoading && <div className="defect-detail-modal__detail-state">조치·활동 정보를 준비하는 중...</div>}
            {isError && <div className="defect-detail-modal__detail-state">다른 하자를 선택하거나 다시 시도해 주세요.</div>}
            {!isLoading && !isError && detailDefect && (
              <>
                <DefectActionForm
                  key={`action-${detailDefect.id}`}
                  defectId={detailDefect.id}
                  inspectionId={detailDefect.inspectionId}
                  status={detailDefect.status}
                  actionResult={detailDefect.actionResult}
                />
                <ActivityHistoryPanel key={`history-${detailDefect.id}`} defectId={detailDefect.id} />
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
