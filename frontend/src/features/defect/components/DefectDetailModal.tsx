import { useEffect, useRef, useState } from 'react';
// defect-chip / defect-metrics / defect-metric-card 등은 DefectDetailPage.css에 정의돼 있다 — 이
// 모달이 렌더링되는 InspectionDefectsPage는 그 CSS를 별도로 로드하지 않으므로 함께 임포트해 스타일을
// 재사용한다(신규 스타일 중복 정의 금지).
import '../pages/DefectDetailPage.css';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { useDefect } from '../hooks/useDefect';
import { useDefectActionLogs } from '../hooks/useDefectActionLogs';
import { DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL } from '../types';
import type { Defect } from '../types';
import { formatDefectCode } from '../utils/defectFormat';
import { getBboxStyle } from '../utils/defectImageGeometry';
import { ActivityHistoryPanel } from './ActivityHistoryPanel';
import { DefectActionForm } from './DefectActionForm';
import { DefectExplainPanel } from './DefectExplainPanel';
import { DefectImageViewer } from './DefectImageViewer';

type Props = {
  defects: Defect[];
  initialDefectId: number;
  onClose: () => void;
};

// shared/components/Modal/Modal.tsx의 focusable-elements 탐색 로직을 그대로 이식(코드리뷰 P1 —
// role="dialog" aria-modal="true"만 선언하고 실제 포커스 트랩이 없던 문제). Modal.tsx는 이 헬퍼를
// export하지 않아 로컬로 복제한다.
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
}

// 하자 상세 모달(HAJA-393/394 §화면 구조 ③, contract.md 확정) — 사이드바(SideNavBar)/헤더(Header)를
// 침범하면 안 되므로 shared/components/Modal(createPortal + position:fixed 전체 뷰포트 오버레이)을
// 쓰지 않는다. 대신 호출부(InspectionDefectsPage)가 position:relative인 페이지 컨테이너 안에서 이
// 컴포넌트를 position:absolute; inset:0으로 렌더링한다 — 별도 fullscreen 오버레이 메커니즘을 새로
// 만들지 말라는 handoff 지시에 따른 의도적 설계(공통 Modal 재사용 원칙의 예외). 포지셔닝만 예외이고,
// 포커스 트랩/초기 포커스/포커스 복원은 shared Modal과 동일한 로직을 그대로 따른다.
export function DefectDetailModal({ defects, initialDefectId, onClose }: Props) {
  const [selectedDefectId, setSelectedDefectId] = useState(initialDefectId);
  const { data: defect, isLoading, isError, refetch } = useDefect(selectedDefectId);
  // 조치 전/조치/조치 완료 사진 탭(#969, #1193/HAJA-569로 3탭 확장) — 사진 등록 전후에
  // 레이아웃이 바뀌지 않도록 탭바는 항상 노출하고, "조치 사진"/"조치 완료 사진" 탭은 각 phase
  // 이력이 1건 이상일 때만 추가한다. 기본은 언제나 "조치 전" 탭이다.
  const [activePhotoTab, setActivePhotoTab] = useState<'before' | 'inProgress' | 'resolved'>('before');
  // 각 탭에 이력이 2건 이상일 때만 노출되는 등록일 select의 선택값 — null이면 최신(배열 첫 항목)을
  // 기본 선택한다(백엔드가 이미 createdAt 내림차순으로 정렬해 내려줌).
  const [selectedInProgressLogId, setSelectedInProgressLogId] = useState<number | null>(null);
  const [selectedResolvedLogId, setSelectedResolvedLogId] = useState<number | null>(null);
  const { data: inProgressLogsData } = useDefectActionLogs(defect?.id, 'IN_PROGRESS');
  const { data: resolvedLogsData } = useDefectActionLogs(defect?.id, 'RESOLVED');
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
  // onClose가 부모 렌더마다 새로 생성돼도 effect가 재실행되지 않도록 ref로 최신값만 참조(Modal.tsx와 동일)
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const imageUrl = defects.find((item) => item.imageUrl != null)?.imageUrl ?? null;
  const unpositionedDefects = defects.filter(
    (item) => getBboxStyle(item.bboxX, item.bboxY, item.bboxW, item.bboxH) == null,
  );

  useEffect(() => {
    setActivePhotoTab('before');
    setSelectedInProgressLogId(null);
    setSelectedResolvedLogId(null);
  }, [selectedDefectId]);

  useEffect(() => {
    // 열리기 직전 포커스를 기억해뒀다가, 닫힐 때 트리거 요소(카드 등)로 복귀시킨다.
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

      if (event.key === 'Tab' && panel) {
        const focusable = getFocusableElements(panel);
        if (focusable.length === 0) {
          event.preventDefault();
          return;
        }

        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;

        if (event.shiftKey) {
          if (active === first || !panel.contains(active)) {
            event.preventDefault();
            last.focus();
          }
        } else if (active === last || !panel.contains(active)) {
          event.preventDefault();
          first.focus();
        }
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      previousActiveElementRef.current?.focus();
    };
    // 모달은 InspectionDefectsPage에서 조건부로 마운트/언마운트되므로(열림=마운트) 마운트 시
    // 1회만 실행 — defect 데이터가 로딩 후 채워져도 트랩을 재설정할 필요는 없다(포커스 가능 요소
    // 목록은 매 Tab 입력마다 다시 조회하므로 최신 DOM을 반영한다).
  }, []);

  return (
    <div className="defect-detail-modal" role="dialog" aria-modal="true" aria-label="하자 상세">
      <div className="defect-detail-modal__overlay" onClick={onClose} aria-hidden="true" />
      <div className="defect-detail-modal__panel" ref={panelRef} tabIndex={-1}>
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
              <h2 className="defect-detail-modal__code">{formatDefectCode(defect.id)}</h2>
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
                {/* 이미지(4:3 고정) + 지표 카드를 좌우로 배치(#1417) — 넓은 화면에서 이미지 높이가
                    과도하게 커지는 걸 막고, 지표 카드가 이미지 옆 여백을 채우도록 한다. 640px 이하에서는
                    세로로 쌓인다(아래 media-row 미디어쿼리). */}
                <div className="defect-detail-modal__media-row">
                  <div className="defect-detail-modal__image-col">
                    <div className="defect-detail-modal__photo-tab-row">
                      <div
                        className="defect-detail-modal__photo-tabs"
                        role="tablist"
                        aria-label="조치 전/조치/조치 완료 사진"
                      >
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
                            if (activePhotoTab === 'inProgress') {
                              setSelectedInProgressLogId(logId);
                            } else if (activePhotoTab === 'resolved') {
                              setSelectedResolvedLogId(logId);
                            }
                          }}
                        >
                          {/* activeTabLogs는 백엔드가 createdAt 내림차순(최신 먼저)으로 내려주므로,
                              등록 순서상 가장 오래된 항목이 1회차가 되도록 인덱스를 뒤집는다. 같은 날
                              여러 건 등록 시 actionDate만으로는 옵션 라벨이 동일해 구분이 안 되던
                              문제(#1417)를 회차 번호로 해소 — 날짜는 참고용으로 함께 표시. */}
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
                        typeLabel={defect.typeLabel}
                      />
                    ) : (
                      <>
                        <DefectImageViewer
                          imageUrl={imageUrl}
                          typeLabel={defect.typeLabel}
                          defects={defects}
                          selectedDefectId={selectedDefectId}
                          onSelectDefect={setSelectedDefectId}
                        />
                        {unpositionedDefects.length > 0 && (
                          <div className="defect-detail-modal__unpositioned" aria-label="위치 미지정 하자">
                            <span>위치 미지정</span>
                            {unpositionedDefects.map((item) => (
                              <button
                                key={item.id}
                                type="button"
                                className={item.id === selectedDefectId ? 'is-selected' : ''}
                                aria-pressed={item.id === selectedDefectId}
                                onClick={() => setSelectedDefectId(item.id)}
                              >
                                {formatDefectCode(item.id)} · {item.typeLabel}
                              </button>
                            ))}
                          </div>
                        )}
                      </>
                    )}
                  </div>

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
                  key={`action-${defect.id}`}
                  defectId={defect.id}
                  inspectionId={defect.inspectionId}
                  status={defect.status}
                  actionResult={defect.actionResult}
                />
                <ActivityHistoryPanel key={`history-${defect.id}`} defectId={defect.id} />
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
