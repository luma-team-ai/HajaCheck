import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button } from "../../../shared/components/Button";
import { TableFooterPagination } from "../../../shared/components/TableFooterPagination";
import { defectApi } from "../api/defectApi";
import { DefectActionBoard } from "../components/DefectActionBoard";
import { DefectFilterBar } from "../components/DefectFilterBar";
import { InspectionFilterBar } from "../components/InspectionFilterBar";
import { InspectionTable } from "../components/InspectionTable";
import { useDefects } from "../hooks/useDefects";
import { useInspections } from "../hooks/useInspections";
import type { DefectListFilters, InspectionListFilters } from "../types";
import { exportDefectsToPdf } from "../utils/exportDefectsToPdf";
import "./DefectListPage.css";

const DEFAULT_SIZE = 10;

// "목록 보기 / 보드 보기" 탭(HAJA-349/#630) — 신규 라우트·사이드바 항목 추가 없이 기존 목록 페이지
// 안에 탭으로 얹는다(#499에서 사이드바 "하자 관리"를 하위메뉴 없는 단일 링크로 정리한 결정 유지,
// handoff §UI 배치).
type DefectViewMode = "list" | "board";

// 하자 목록 — HAJA-30 → HAJA-393/394(#725/#726)에서 "목록 보기" 탭을 점검(Inspection) 단위로
// 재해석했다(사용자 확정 지시, 2026-07-24 — 시각 디자인은 유지하되 로우를 점검 단위로 변경).
// "보드 보기" 탭(HAJA-349/#630)은 여전히 하자 단건 기준으로 유지한다(이번 개편 범위 밖 — 손대지
// 않음). 두 탭이 서로 다른 그레인(점검 vs 하자)을 다루므로 필터 상태도 분리한다:
// - defectFilters: 보드 탭(DefectActionBoard) + 헤더 "총 N건"(보드 탭 활성 시) 소스
// - inspectionFilters: 목록 탭(InspectionTable) 소스
export function DefectListPage() {
  const navigate = useNavigate();
  const [defectFilters, setDefectFilters] = useState<DefectListFilters>({
    page: 0,
    size: DEFAULT_SIZE,
  });
  const [inspectionFilters, setInspectionFilters] = useState<InspectionListFilters>({
    page: 0,
    size: DEFAULT_SIZE,
  });
  const [viewMode, setViewMode] = useState<DefectViewMode>("list");
  // 헤더 "총 N건"(보드 탭 활성 시) 소스로만 쓴다 — 보드 탭 자체 로딩/에러 UI는 DefectActionBoard가
  // 내부 useDefectActionBoard로 별도 처리한다. 목록 보기 탭에서는 이 응답을 전혀 쓰지 않으므로
  // viewMode==='board'일 때만 조회한다(PR머신 P2 지적 — 불필요한 GET /api/defects 매 렌더 호출).
  const { data } = useDefects(defectFilters, { enabled: viewMode === "board" });
  const {
    data: inspectionData,
    isLoading: isInspectionLoading,
    isError: isInspectionError,
    refetch: refetchInspections,
  } = useInspections(inspectionFilters);
  // 선택 대상은 활성 탭에 따라 그레인이 다르다(목록=점검 id, 보드=선택 없음) — viewMode 전환 시
  // 이전 탭의 선택이 남아 있으면 혼동되므로 탭을 바꿀 때 함께 초기화한다.
  const [selectedIds, setSelectedIds] = useState<Set<number>>(() => new Set());
  const [isExporting, setIsExporting] = useState(false);

  const inspectionSize = inspectionFilters.size ?? DEFAULT_SIZE;
  const inspectionCurrentPage = (inspectionFilters.page ?? 0) + 1; // TableFooterPagination은 1-based
  const inspectionTotalElements = inspectionData?.totalElements ?? 0;
  const inspectionTotalPages = Math.max(1, Math.ceil(inspectionTotalElements / inspectionSize));

  // 헤더 "총 N건"은 활성 탭 그레인 기준(목록=점검 건수, 보드=하자 건수)으로 표시한다.
  const totalElements = viewMode === "list" ? inspectionTotalElements : data?.totalElements ?? 0;

  const selectedInspections = useMemo(
    () => (inspectionData?.content ?? []).filter((inspection) => selectedIds.has(inspection.id)),
    [inspectionData, selectedIds],
  );
  // 보드 탭에서는 점검 선택 자체가 의미 없으므로(테이블이 렌더링되지 않음) 목록 탭에서만 활성화한다.
  const canGenerateReport = viewMode === "list" && selectedInspections.length === 1;
  const canExport = viewMode === "list" && selectedInspections.length > 0;
  const reportButtonTitle =
    viewMode !== "list"
      ? "목록 보기에서 점검을 선택하세요"
      : selectedInspections.length === 0
        ? "보고서를 생성할 점검을 선택하세요"
        : selectedInspections.length > 1
          ? "보고서는 점검 1건씩만 생성할 수 있습니다"
          : undefined;

  const handleInspectionPageChange = (page: number) => {
    setInspectionFilters((prev) => ({ ...prev, page: page - 1 }));
  };

  const handleInspectionPageSizeChange = (nextSize: number) => {
    setInspectionFilters((prev) => ({ ...prev, size: nextSize, page: 0 }));
  };

  const handleViewModeChange = (mode: DefectViewMode) => {
    setViewMode(mode);
    setSelectedIds(new Set());
  };

  const handleGenerateReport = () => {
    if (!canGenerateReport) return;
    navigate(`/inspections/${selectedInspections[0].id}/viewer`);
  };

  // "내보내기"는 선택된 점검(들)에 속한 하자 전체를 모아 PDF로 내보낸다 — 기존(하자 단건 선택 후
  // 바로 내보내기)과 달리 점검 단위 선택이라 하자 목록을 먼저 조회해야 한다(HAJA-393/394 재해석).
  const handleExport = async () => {
    if (!canExport || isExporting) return;
    setIsExporting(true);
    try {
      const defectsByInspection = await Promise.all(
        selectedInspections.map((inspection) =>
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
              title={canExport ? undefined : "내보낼 점검을 선택하세요"}
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

        {viewMode === "list" ? (
          <InspectionFilterBar filters={inspectionFilters} onChange={setInspectionFilters} />
        ) : (
          <DefectFilterBar filters={defectFilters} onChange={setDefectFilters} />
        )}

        <div className="defect-list-page__view-tabs" role="tablist" aria-label="하자 보기 방식">
          <button
            type="button"
            id="defect-list-tab-list"
            role="tab"
            aria-selected={viewMode === "list"}
            aria-controls="defect-list-tabpanel-list"
            className={
              viewMode === "list"
                ? "defect-list-page__view-tab is-active"
                : "defect-list-page__view-tab"
            }
            onClick={() => handleViewModeChange("list")}
          >
            목록 보기
          </button>
          <button
            type="button"
            id="defect-list-tab-board"
            role="tab"
            aria-selected={viewMode === "board"}
            aria-controls="defect-list-tabpanel-board"
            className={
              viewMode === "board"
                ? "defect-list-page__view-tab is-active"
                : "defect-list-page__view-tab"
            }
            onClick={() => handleViewModeChange("board")}
          >
            보드 보기
          </button>
        </div>
      </header>

      {viewMode === "list" ? (
        <div
          className="defect-list-page__table-region"
          id="defect-list-tabpanel-list"
          role="tabpanel"
          aria-labelledby="defect-list-tab-list"
        >
          <div className="defect-list-page__table-scroll">
            <InspectionTable
              inspections={inspectionData?.content}
              isLoading={isInspectionLoading}
              isError={isInspectionError}
              onRetry={refetchInspections}
              selectedIds={selectedIds}
              onSelectionChange={setSelectedIds}
            />
          </div>

          {!isInspectionLoading && !isInspectionError && (
            <div className="defect-list-page__pagination">
              <TableFooterPagination
                pageSize={inspectionSize}
                onPageSizeChange={handleInspectionPageSizeChange}
                currentPage={inspectionCurrentPage}
                totalPages={inspectionTotalPages}
                totalItems={inspectionTotalElements}
                onPageChange={handleInspectionPageChange}
              />
            </div>
          )}
        </div>
      ) : (
        // 보드 스코프는 전체 하자(사용자 확정) — 페이지네이션은 보드에서 의미가 없어 숨긴다.
        // 목록/보드 탭이 유형·등급·상태 필터는 공유하되, 보드는 자체 페이지 크기(BOARD_PAGE_SIZE)로
        // 조회한다(useDefectActionBoard 참조).
        <div
          className="defect-list-page__board-region"
          id="defect-list-tabpanel-board"
          role="tabpanel"
          aria-labelledby="defect-list-tab-board"
        >
          <DefectActionBoard filters={defectFilters} />
        </div>
      )}
    </section>
  );
}
