import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { DefectCardGrid } from '../components/DefectCardGrid';
import { DefectDetailModal } from '../components/DefectDetailModal';
import { InspectionActivityPanel } from '../components/InspectionActivityPanel';
import { InspectionKpiSummary } from '../components/InspectionKpiSummary';
import { useInspectionDefects } from '../hooks/useInspectionDefects';
import { formatInspectionCode } from '../utils/defectFormat';
import { findDefectImageGroup, groupDefectsByImage } from '../utils/defectImageGroups';
import './InspectionDefectsPage.css';

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②, contract.md 확정) — 하자 목록의 점검 로우를 클릭하면
// 이동하는 페이지. 하자 단건 상세 화면을 대체하는 /inspections/:id/defects 경로를 쓴다(:id는 inspectionId).
//
// 카드 클릭 시 뜨는 하자 상세 모달(§화면 구조 ③)은 사이드바/헤더를 침범하면 안 되므로, 이 페이지
// 컨테이너를 position:relative + 뷰포트 높이로 고정(.inspection-defects-page)하고 모달을 그 안에서
// position:absolute; inset:0으로 렌더링한다(DefectDetailModal.tsx 주석 참고 — handoff 지시).
export function InspectionDefectsPage() {
  const { id } = useParams<{ id: string }>();
  const inspectionId = id != null ? Number(id) : undefined;
  const { data: defects, isLoading, isError, refetch } = useInspectionDefects(inspectionId);
  const [searchParams, setSearchParams] = useSearchParams();
  // 대시보드 "검수하기"의 defectId 딥링크(#1117 회귀 수정) — 진입 시점의 쿼리파라미터로만 초기값을
  // 정하고, 이후 URL이 바뀌어도 모달은 사용자의 명시적 조작(카드 클릭/닫기)으로만 열고 닫는다.
  const [selectedDefectId, setSelectedDefectId] = useState<number | null>(() => {
    const raw = searchParams.get('defectId');
    const parsed = raw != null ? Number(raw) : NaN;
    return Number.isFinite(parsed) ? parsed : null;
  });

  // defectId는 초기값으로만 한 번 소비하고 URL에서 곧바로 제거한다(features/counsel/hooks/
  // useCounselHistory.ts의 ticketId 딥링크와 동일 패턴) — 남겨두면 모달을 닫은 뒤 새로고침하거나
  // URL을 공유했을 때 같은 하자 모달이 사용자 의사와 무관하게 계속 재오픈된다(코드리뷰 P2).
  useEffect(() => {
    if (!searchParams.has('defectId')) return;
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('defectId');
        return next;
      },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 마운트 시 1회만 소비, 이후 URL 변경엔 반응하지 않는다
  }, []);

  const defectGroups = useMemo(() => groupDefectsByImage(defects ?? []), [defects]);
  const selectedGroup =
    selectedDefectId == null ? null : findDefectImageGroup(defectGroups, selectedDefectId);
  const isModalOpen = selectedGroup != null;

  return (
    <div className="inspection-defects-page">
      {/* 모달이 열려 있는 동안 배경 콘텐츠를 스크린리더에서 숨긴다(코드리뷰 P1 — 포커스 트랩과
          별개로, aria-hidden이 없으면 배경 텍스트가 그대로 읽힌다). 실제 키보드 포커스 이동은
          DefectDetailModal의 자체 Tab 트랩이 막는다. */}
      <div className="inspection-defects-page__scroll" aria-hidden={isModalOpen || undefined}>
        <nav className="inspection-defects-page__breadcrumb" aria-label="하자 관리 현재 위치">
          <span>하자 관리</span>
          <span aria-hidden="true">›</span>
          <span>하자 목록</span>
          <span aria-hidden="true">›</span>
          <span className="inspection-defects-page__breadcrumb-current">하자 상세</span>
        </nav>

        <div className="inspection-defects-page__header">
          <h1 className="inspection-defects-page__title">
            {inspectionId != null ? formatInspectionCode(inspectionId) : '-'}
            <span className="inspection-defects-page__sr-only"> 하자 상세</span>
          </h1>

          {!isLoading && !isError && defects && (
            <>
              <span className="inspection-defects-page__header-divider" aria-hidden="true" />
              <InspectionKpiSummary defects={defects} />
            </>
          )}
        </div>

        {isLoading && (
          <div role="status" className="inspection-defects-page__loading">
            불러오는 중...
          </div>
        )}

        {isError && (
          <ErrorFallback message="점검 하자 목록을 불러오지 못했습니다." onRetry={refetch} />
        )}

        {!isLoading && !isError && defects && (
          <div className="inspection-defects-page__layout">
            <DefectCardGrid defects={defects} onSelectDefect={setSelectedDefectId} />
            <InspectionActivityPanel defects={defects} />
          </div>
        )}
      </div>

      {selectedDefectId != null && selectedGroup != null && (
        <DefectDetailModal
          defects={selectedGroup.defects}
          initialDefectId={selectedDefectId}
          onClose={() => setSelectedDefectId(null)}
        />
      )}
    </div>
  );
}
