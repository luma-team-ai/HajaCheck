import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { DefectCardGrid } from '../components/DefectCardGrid';
import { DefectDetailModal } from '../components/DefectDetailModal';
import { InspectionActivityPanel } from '../components/InspectionActivityPanel';
import { InspectionKpiSummary } from '../components/InspectionKpiSummary';
import { useInspectionDefects } from '../hooks/useInspectionDefects';
import './InspectionDefectsPage.css';

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②, contract.md 확정) — 하자 목록의 점검 로우를 클릭하면
// 이동하는 페이지. 라우트는 기존 /defects/:id(하자 단건 상세, 보드 보기에서 여전히 사용)와 의미가
// 겹치지 않도록 신규 경로 /inspections/:id/defects를 쓴다(:id는 inspectionId).
//
// 카드 클릭 시 뜨는 하자 상세 모달(§화면 구조 ③)은 사이드바/헤더를 침범하면 안 되므로, 이 페이지
// 컨테이너를 position:relative + 뷰포트 높이로 고정(.inspection-defects-page)하고 모달을 그 안에서
// position:absolute; inset:0으로 렌더링한다(DefectDetailModal.tsx 주석 참고 — handoff 지시).
export function InspectionDefectsPage() {
  const { id } = useParams<{ id: string }>();
  const inspectionId = id != null ? Number(id) : undefined;
  const { data: defects, isLoading, isError, refetch } = useInspectionDefects(inspectionId);
  const [selectedDefectId, setSelectedDefectId] = useState<number | null>(null);

  const isModalOpen = selectedDefectId != null;

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

        <h1 className="inspection-defects-page__sr-only">점검 하자 상세</h1>

        {isLoading && (
          <div role="status" className="inspection-defects-page__loading">
            불러오는 중...
          </div>
        )}

        {isError && (
          <ErrorFallback message="점검 하자 목록을 불러오지 못했습니다." onRetry={refetch} />
        )}

        {!isLoading && !isError && defects && (
          <>
            <InspectionKpiSummary defects={defects} />

            <div className="inspection-defects-page__layout">
              <DefectCardGrid defects={defects} onSelectDefect={setSelectedDefectId} />
              <InspectionActivityPanel defects={defects} />
            </div>
          </>
        )}
      </div>

      {selectedDefectId != null && (
        <DefectDetailModal defectId={selectedDefectId} onClose={() => setSelectedDefectId(null)} />
      )}
    </div>
  );
}
