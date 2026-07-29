import { useState } from "react";
import { Button } from "../../../shared/components/Button";
import { TableFooterPagination } from "../../../shared/components/TableFooterPagination";
import { defectApi, fetchAllFilteredInspections } from "../api/defectApi";
import { InspectionFilterBar } from "../components/InspectionFilterBar";
import { InspectionTable } from "../components/InspectionTable";
import { useInspections } from "../hooks/useInspections";
import type { InspectionListFilters } from "../types";
import { exportDefectsToPdf } from "../utils/exportDefectsToPdf";
import "./DefectListPage.css";

const DEFAULT_SIZE = 10;

// 하자 목록 — HAJA-30 → HAJA-393/394(#725/#726)에서 점검(Inspection) 단위로 재해석했다(사용자 확정
// 지시, 2026-07-24 — 시각 디자인은 유지하되 로우를 점검 단위로 변경).
//
// 2026-07-26 정정(#726 코멘트): 실구현 과정에서 이 페이지에 "목록 보기/보드 보기" 2탭 구조를 얹고
// 하자 단건 기준 조치 보드(#630/HAJA-349, DefectActionBoard)를 "보드 보기" 탭으로 끼워 넣었으나,
// 이는 서로 다른 그레인(점검 vs 하자)의 별개 기능을 한 페이지에 억지로 합친 설계 오류였다 — 사용자가
// 되돌리라고 확정 지시. 이 페이지는 다시 점검 단위 목록 단일 플로우로만 구성한다.
// DefectActionBoard 컴포넌트 트리(DefectActionBoard/DefectBoardColumn/DefectBoardCard/
// DefectStatusReasonModal/useDefectActionBoard)와 DefectFilterBar는 삭제하지 않고 참조만 제거한다 —
// #630을 별도 라우트로 분리할지 완전 폐기할지는 후속 이슈에서 결정(이번 세션 범위 밖).
export function DefectListPage() {
  const [inspectionFilters, setInspectionFilters] = useState<InspectionListFilters>({
    page: 0,
    size: DEFAULT_SIZE,
  });
  const [isExporting, setIsExporting] = useState(false);

  const {
    data: inspectionData,
    isLoading: isInspectionLoading,
    isError: isInspectionError,
    refetch: refetchInspections,
  } = useInspections(inspectionFilters);

  const inspectionSize = inspectionFilters.size ?? DEFAULT_SIZE;
  const inspectionCurrentPage = (inspectionFilters.page ?? 0) + 1; // TableFooterPagination은 1-based
  const totalElements = inspectionData?.totalElements ?? 0;
  const inspectionTotalPages = Math.max(1, Math.ceil(totalElements / inspectionSize));

  const canExport = totalElements > 0;

  const handleInspectionPageChange = (page: number) => {
    setInspectionFilters((prev) => ({ ...prev, page: page - 1 }));
  };

  const handleInspectionPageSizeChange = (nextSize: number) => {
    setInspectionFilters((prev) => ({ ...prev, size: nextSize, page: 0 }));
  };

  // "내보내기"는 선택 여부·현재 페이지와 무관하게 현재 필터에 해당하는 모든 점검의 하자를 모아
  // PDF로 내보낸다. 관리자 사용자 목록 내보내기와 동일한 "필터 결과 전체" 계약이다.
  const handleExport = async () => {
    if (!canExport || isExporting) return;
    setIsExporting(true);
    try {
      const filteredInspections = await fetchAllFilteredInspections(inspectionFilters);
      const defectsByInspection = await Promise.all(
        filteredInspections.map((inspection) =>
          defectApi.getByInspection(inspection.id).then((res) => res.data),
        ),
      );
      await exportDefectsToPdf(defectsByInspection.flat());
    } catch (error) {
      console.error("점검 하자 목록 PDF 내보내기 실패", error);
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <section className="defect-list-page" aria-labelledby="defect-list-title">
      <header className="defect-list-page__header">
        <nav
          className="defect-list-page__breadcrumb"
          aria-label="하자 관리 현재 위치"
        >
          <span>HajaCheck</span>
          <span aria-hidden="true">›</span>
          <span className="defect-list-page__breadcrumb-current">
            하자 관리
          </span>
        </nav>

        <div className="defect-list-page__title-row">
          <div className="defect-list-page__title-group">
            <h1 id="defect-list-title">하자 관리</h1>
            <span className="defect-list-page__count">
              총 {totalElements.toLocaleString()}건
            </span>
          </div>

          <div
            className="defect-list-page__actions"
            aria-label="하자 목록 작업"
          >
            <Button
              variant="secondary"
              size="md"
              disabled={!canExport || isExporting}
              title={canExport ? undefined : "내보낼 필터 결과가 없습니다"}
              onClick={handleExport}
            >
              {isExporting ? "내보내는 중..." : "내보내기"}
            </Button>
          </div>
        </div>

        <InspectionFilterBar
          filters={inspectionFilters}
          onChange={setInspectionFilters}
        />
      </header>

      <div className="defect-list-page__table-region">
        <div className="defect-list-page__table-scroll">
          <InspectionTable
            inspections={inspectionData?.content}
            isLoading={isInspectionLoading}
            isError={isInspectionError}
            onRetry={refetchInspections}
          />
        </div>

        {!isInspectionLoading && !isInspectionError && (
          <div className="defect-list-page__pagination">
            <TableFooterPagination
              pageSize={inspectionSize}
              onPageSizeChange={handleInspectionPageSizeChange}
              currentPage={inspectionCurrentPage}
              totalPages={inspectionTotalPages}
              totalItems={totalElements}
              onPageChange={handleInspectionPageChange}
            />
          </div>
        )}
      </div>
    </section>
  );
}
