import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button } from "../../../shared/components/Button";
import { TableFooterPagination } from "../../../shared/components/TableFooterPagination";
import { DefectActionBoard } from "../components/DefectActionBoard";
import { DefectFilterBar } from "../components/DefectFilterBar";
import { DefectTable } from "../components/DefectTable";
import { useDefects } from "../hooks/useDefects";
import type { DefectListFilters } from "../types";
import { exportDefectsToPdf } from "../utils/exportDefectsToPdf";
import "./DefectListPage.css";

const DEFAULT_SIZE = 10;

// "목록 보기 / 보드 보기" 탭(HAJA-349/#630) — 신규 라우트·사이드바 항목 추가 없이 기존 목록 페이지
// 안에 탭으로 얹는다(#499에서 사이드바 "하자 관리"를 하위메뉴 없는 단일 링크로 정리한 결정 유지,
// handoff §UI 배치).
type DefectViewMode = "list" | "board";

// 하자 목록 — HAJA-30. FacilityListPage(features/facility)와 동일하게 목록 조회 훅 + 테이블 +
// (신규) 필터·페이지네이션 조합으로 구성한다. AppShellRoute 자식(셸 포함) — /defects/:id(상세)와
// 동일한 셸 아래 목록→상세 이동 흐름을 유지한다.
export function DefectListPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState<DefectListFilters>({
    page: 0,
    size: DEFAULT_SIZE,
  });
  const { data, isLoading, isError, refetch } = useDefects(filters);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(() => new Set());
  const [isExporting, setIsExporting] = useState(false);
  const [viewMode, setViewMode] = useState<DefectViewMode>("list");

  const size = filters.size ?? DEFAULT_SIZE;
  const currentPage = (filters.page ?? 0) + 1; // TableFooterPagination/Pagination은 1-based
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / size));

  const selectedDefects = useMemo(
    () => (data?.content ?? []).filter((defect) => selectedIds.has(defect.id)),
    [data, selectedIds],
  );
  const selectedInspectionIds = useMemo(
    () => new Set(selectedDefects.map((defect) => defect.inspectionId)),
    [selectedDefects],
  );
  const canGenerateReport =
    selectedDefects.length > 0 && selectedInspectionIds.size === 1;
  const reportButtonTitle =
    selectedDefects.length === 0
      ? "보고서를 생성할 하자를 선택하세요"
      : selectedInspectionIds.size > 1
        ? "같은 점검 회차의 하자만 선택하세요"
        : undefined;
  const canExport = selectedDefects.length > 0;

  const handlePageChange = (page: number) => {
    setFilters((prev) => ({ ...prev, page: page - 1 }));
  };

  const handlePageSizeChange = (nextSize: number) => {
    setFilters((prev) => ({ ...prev, size: nextSize, page: 0 }));
  };

  const handleGenerateReport = () => {
    if (!canGenerateReport) return;
    navigate(`/inspections/${selectedDefects[0].inspectionId}/viewer`);
  };

  const handleExport = async () => {
    if (!canExport || isExporting) return;
    setIsExporting(true);
    try {
      await exportDefectsToPdf(selectedDefects);
    } catch (error) {
      console.error("하자 목록 PDF 내보내기 실패", error);
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
              title={canExport ? undefined : "내보낼 하자를 선택하세요"}
              onClick={handleExport}
            >
              {isExporting ? "내보내는 중..." : "내보내기"}
            </Button>
            <Button
              variant="primary"
              size="md"
              disabled={!canGenerateReport}
              title={reportButtonTitle}
              onClick={handleGenerateReport}
            >
              보고서 생성
            </Button>
          </div>
        </div>

        <DefectFilterBar filters={filters} onChange={setFilters} />

        <div className="defect-list-page__view-tabs" role="tablist" aria-label="하자 보기 방식">
          <button
            type="button"
            role="tab"
            aria-selected={viewMode === "list"}
            className={
              viewMode === "list"
                ? "defect-list-page__view-tab is-active"
                : "defect-list-page__view-tab"
            }
            onClick={() => setViewMode("list")}
          >
            목록 보기
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={viewMode === "board"}
            className={
              viewMode === "board"
                ? "defect-list-page__view-tab is-active"
                : "defect-list-page__view-tab"
            }
            onClick={() => setViewMode("board")}
          >
            보드 보기
          </button>
        </div>
      </header>

      {viewMode === "list" ? (
        <div className="defect-list-page__table-region">
          <div className="defect-list-page__table-scroll">
            <DefectTable
              defects={data?.content}
              isLoading={isLoading}
              isError={isError}
              onRetry={refetch}
              selectedIds={selectedIds}
              onSelectionChange={setSelectedIds}
            />
          </div>

          {!isLoading && !isError && (
            <div className="defect-list-page__pagination">
              <TableFooterPagination
                pageSize={size}
                onPageSizeChange={handlePageSizeChange}
                currentPage={currentPage}
                totalPages={totalPages}
                totalItems={totalElements}
                onPageChange={handlePageChange}
              />
            </div>
          )}
        </div>
      ) : (
        // 보드 스코프는 전체 하자(사용자 확정) — 페이지네이션은 보드에서 의미가 없어 숨긴다.
        // 목록/보드 탭이 유형·등급·상태 필터는 공유하되, 보드는 자체 페이지 크기(BOARD_PAGE_SIZE)로
        // 조회한다(useDefectActionBoard 참조).
        <div className="defect-list-page__board-region">
          <DefectActionBoard filters={filters} />
        </div>
      )}
    </section>
  );
}
