import { useEffect, useRef, useState } from 'react';
import '../pages/DefectDetailPage.css';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { useDefect } from '../hooks/useDefect';
import { useDefectActionLogs } from '../hooks/useDefectActionLogs';
import { DEFECT_GRADE_COLOR, DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL } from '../types';
import type { Defect, InspectionDefect } from '../types';
import { resolveDefectGroupSummary } from '../utils/defectGroupSummary';
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

  // 사진(그룹) 단위 조치 상태(#1644) — 백엔드 groupSize/groupStatus는 조치 등록 PATCH 응답에서만
  // 계산되고(DefectResponse.java:38-43) 목록/단순 상세 조회는 항상 null이라, 등록 전에도 그룹
  // 상태를 보여주려면 이미 이 모달이 들고 있는 defects(같은 사진의 group-eligible 하자 전체 —
  // 실 사용처인 InspectionDefectsPage는 groupDefectsByImage로 미리 이렇게 걸러 넘긴다)에서 직접
  // 계산한다. 백엔드 DefectService.resolveActionGroup/aggregateGroupStatus(DefectService.java:192-217)
  // 와 동일한 판정 규칙을 재현하므로, mediaId==null(수동 추가 하자)이면 자연히 크기 1로 떨어져
  // 별도 분기 없이 폴백을 겸한다.
  const groupSummary = resolveDefectGroupSummary(defects, selectedDefect);
  const actionFormGroupKey =
    selectedDefect.mediaId != null ? `media-${selectedDefect.mediaId}` : `defect-${selectedDefect.id}`;

  // 조치 등록 폼 진입점 통합(#1644) — bbox를 전환해도 같은 사진 그룹이면 DefectActionForm을
  // 언마운트하지 않아야 입력 중이던 초안(사진/조치내용/조치일 등 로컬 state)이 유지된다. 하지만
  // 이 모달은 selectedDefectId가 바뀔 때마다 useDefect(새 id)를 다시 fetch하므로(아래 detailDefect),
  // 그 fetch가 끝나기 전까지는 매번 detailDefect가 잠깐 null이 된다 — 그 순간 DefectActionForm을
  // 그대로 걷어내 버리면(원래 코드) key를 그룹 단위로 바꿔도 unmount+remount가 일어나 소용없다.
  // 그래서 "이 그룹에서 마지막으로 성공 로드된 Defect"를 별도 state로 들고 있다가 다음 fetch가
  // 끝날 때까지 그걸로 계속 렌더링한다. 갱신은 useEffect가 아니라 렌더 중 직접 한다(React
  // "Adjusting state based on props/state during render" 패턴) — effect까지 기다리면 커밋 후
  // 한 틱이 더 필요해, isLoading이 false로 바뀌는 바로 그 렌더에서 폼이 함께 나타나지 못하고
  // 한 프레임 늦게 나타난다(다른 하자상세 통합테스트가 기대하는 "isLoading 해제 즉시 폼 노출"
  // 타이밍과 어긋남). 그룹이 바뀌면(mediaId 변경) 이전 그룹의 데이터가 새 폼에 잘못 노출되지
  // 않도록 그 즉시 비우거나(detailDefect가 아직 없으면) 이미 로드돼 있으면 곧바로 새 값으로 채운다.
  const [actionFormDefect, setActionFormDefect] = useState<Defect | null>(null);
  const [actionFormDefectGroupKey, setActionFormDefectGroupKey] = useState<string | null>(null);
  // (#1644 리뷰 P3, 정보성) 아래 `detailDefect !== actionFormDefect` 참조 동등성 비교는 react-query
  // 기본 structuralSharing(동일 데이터면 이전 렌더의 참조를 그대로 재사용)이 안정적이라는 전제에
  // 기대고 있다 — QueryClient 설정에서 structuralSharing을 끄거나 커스텀 비교자로 바꾸면 매 응답마다
  // 새 참조가 생겨 이 블록이 더 이상 "값이 실제로 바뀔 때만" 수렴하지 않을 수 있으니, 그런 변경 시
  // 이 수렴 전제를 다시 검토할 것.
  if (actionFormGroupKey !== actionFormDefectGroupKey) {
    setActionFormDefectGroupKey(actionFormGroupKey);
    setActionFormDefect(detailDefect ?? null);
  } else if (detailDefect && detailDefect !== actionFormDefect) {
    setActionFormDefect(detailDefect);
  }

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
          <span
            className="defect-chip defect-chip--grade"
            style={
              selectedDefect.grade
                ? { backgroundColor: DEFECT_GRADE_COLOR[selectedDefect.grade], borderColor: 'transparent' }
                : undefined
            }
          >
            {selectedDefect.grade
              ? `${selectedDefect.grade}등급 · ${DEFECT_GRADE_LABEL[selectedDefect.grade]}`
              : '미분류'}
          </span>
          <span className="defect-chip defect-chip--warning">
            <i aria-hidden="true" />
            {groupSummary.size > 1
              ? `이 사진의 조치 상태: ${DEFECT_STATUS_LABEL[groupSummary.status]} (하자 ${groupSummary.size}건)`
              : DEFECT_STATUS_LABEL[groupSummary.status]}
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

              {/* AI 분석 설명 + 지표 4개(신뢰도/균열 폭/균열 길이/실측 면적, #1658/#1669 추가)를
                  한 블럭으로 묶는다. 이미지는
                  이 블럭이 차지하는 만큼을 뺀 나머지 가로폭을 비율(3:4) 유지한 채 그대로 키워
                  채운다(.defect-detail-modal__image-col의 flex-grow, max-width 해제). */}
              <div className="defect-detail-modal__diagnosis-col">
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

                <div className="defect-metric-row">
                  <article className="defect-metric-card">
                    <span>AI 신뢰도</span>
                    <strong>{Math.round(selectedDefect.confidence * 100)}%</strong>
                  </article>
                  <article className="defect-metric-card">
                    <span>균열 폭(최대)</span>
                    <strong>
                      {selectedDefect.crackWidthMm != null
                        ? `${selectedDefect.crackWidthMm}mm`
                        : <span className="defect-metric-empty">측정 예정</span>}
                    </strong>
                  </article>
                  <article className="defect-metric-card">
                    <span>균열 길이(추정)</span>
                    <strong>
                      {selectedDefect.crackLengthMm != null
                        ? `${selectedDefect.crackLengthMm}mm`
                        : <span className="defect-metric-empty">측정 예정</span>}
                    </strong>
                  </article>
                  {/* 실측 면적(mm², #1658/#1669) — crackWidthMm/crackLengthMm과 동일한 단독 null
                      체크 패턴(다른 필드와 AND 묶기 금지, #1588 교훈). 박리박락·철근노출만 값이 있을
                      수 있고, 카드 기준물 미검출이거나 균열 타입이면 null → '측정 예정'. */}
                  <article className="defect-metric-card">
                    <span>실측 면적</span>
                    <strong>
                      {selectedDefect.areaMm2 != null
                        ? `${selectedDefect.areaMm2}mm²`
                        : <span className="defect-metric-empty">측정 예정</span>}
                    </strong>
                  </article>
                </div>
              </div>
            </div>
          </div>

          <div className="defect-detail-modal__secondary" aria-busy={isLoading}>
            {isLoading && !actionFormDefect && (
              <div className="defect-detail-modal__detail-state">조치·활동 정보를 준비하는 중...</div>
            )}
            {isError && !actionFormDefect && (
              <div className="defect-detail-modal__detail-state">다른 하자를 선택하거나 다시 시도해 주세요.</div>
            )}
            {actionFormDefect && (
              <DefectActionForm
                key={`action-${actionFormGroupKey}`}
                defect={actionFormDefect}
                actionResult={actionFormDefect.actionResult}
                groupSize={groupSummary.size}
              />
            )}
            {!isLoading && !isError && detailDefect && (
              <ActivityHistoryPanel key={`history-${detailDefect.id}`} defectId={detailDefect.id} />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
