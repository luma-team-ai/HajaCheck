import { useEffect, useMemo, useRef } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ErrorFallback } from "../../../shared/components/ErrorFallback";
import type { TableColumn } from "../../../shared/components/Table";
import { Table } from "../../../shared/components/Table";
import type { Defect, DefectGrade, DefectStatus } from "../types";
import { formatDefectCode, formatDefectDate } from "../utils/defectFormat";

type Props = {
  defects: Defect[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  selectedIds: Set<number>;
  onSelectionChange: (ids: Set<number>) => void;
};

interface DefectTableRow {
  id: number;
  selection: string;
  defectCode: string;
  thumbnail: string;
  typeLabel: string;
  grade: DefectGrade | null;
  facilityName: string;
  location: string;
  status: DefectStatus;
  createdAt: string;
  assignee: string;
}

// 조치 보드 카드(HAJA-349/#630)도 동일한 등급 배지 색상을 재사용한다 — feature 내 로컬 상수 재사용
// 컨벤션(신규 색상 상수 추가 금지)에 따라 여기서만 정의하고 export만 넓힌다.
export const GRADE_CLASSES: Record<DefectGrade, string> = {
  A: "border-emerald-200 bg-emerald-50 text-emerald-600",
  B: "border-sky-200 bg-sky-50 text-sky-600",
  C: "border-amber-200 bg-amber-50 text-amber-600",
  D: "border-orange-200 bg-orange-50 text-orange-600",
  E: "border-red-200 bg-red-50 text-red-500",
};

// PDF 내보내기(exportDefectsToPdf)에서도 화면에 보이는 상태 라벨과 동일하게 표기하기 위해 export
export const STATUS_PRESENTATION: Record<
  DefectStatus,
  { label: string; className: string }
> = {
  DETECTED: {
    label: "신규",
    className: "border-blue-200 bg-blue-50 text-blue-500",
  },
  CONFIRMED: {
    label: "검수확정",
    className: "border-zinc-200 bg-zinc-50 text-zinc-700",
  },
  ACTION_PENDING: {
    label: "조치대기",
    className: "border-amber-200 bg-amber-50 text-amber-500",
  },
  IN_PROGRESS: {
    label: "조치중",
    className: "border-orange-200 bg-orange-50 text-orange-500",
  },
  RESOLVED: {
    label: "조치완료",
    className: "border-emerald-200 bg-emerald-50 text-emerald-600",
  },
};

type SelectionCheckboxProps = {
  ariaLabel: string;
  checked: boolean;
  disabled?: boolean;
  indeterminate?: boolean;
  onChange: () => void;
};

function SelectionCheckbox({
  ariaLabel,
  checked,
  disabled = false,
  indeterminate = false,
  onChange,
}: SelectionCheckboxProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = indeterminate;
    }
  }, [indeterminate]);

  return (
    <input
      ref={inputRef}
      className="defect-list-table__select"
      type="checkbox"
      aria-label={ariaLabel}
      checked={checked}
      disabled={disabled}
      onChange={onChange}
      onClick={(event) => event.stopPropagation()}
    />
  );
}

function createColumns({
  isAllSelected,
  isPartiallySelected,
  selectedIds,
  hasRows,
  onToggleAll,
  onToggleRow,
}: {
  isAllSelected: boolean;
  isPartiallySelected: boolean;
  selectedIds: Set<number>;
  hasRows: boolean;
  onToggleAll: () => void;
  onToggleRow: (id: number) => void;
}): TableColumn<DefectTableRow>[] {
  return [
    {
      key: "selection",
      header: (
        <SelectionCheckbox
          ariaLabel="현재 페이지 하자 전체 선택"
          checked={isAllSelected}
          disabled={!hasRows}
          indeterminate={isPartiallySelected}
          onChange={onToggleAll}
        />
      ),
      render: (row) => (
        <SelectionCheckbox
          ariaLabel={`${row.defectCode} 선택`}
          checked={selectedIds.has(row.id)}
          onChange={() => onToggleRow(row.id)}
        />
      ),
    },
    {
      key: "defectCode",
      header: "하자 ID",
      render: (row) => (
        <Link
          aria-label="상세보기"
          className="defect-list-table__id"
          to={`/defects/${row.id}`}
        >
          {row.defectCode}
        </Link>
      ),
    },
    {
      key: "thumbnail",
      header: "썸네일",
      render: (row) => (
        <span
          className="defect-list-table__thumbnail"
          aria-label={`${row.typeLabel} 썸네일 준비 중`}
        >
          {row.typeLabel.slice(0, 1)}
        </span>
      ),
    },
    { key: "typeLabel", header: "유형" },
    {
      key: "grade",
      header: "등급",
      render: (row) =>
        row.grade ? (
          <span
            className={`defect-list-table__grade ${GRADE_CLASSES[row.grade]}`}
          >
            {row.grade}
          </span>
        ) : (
          <span className="defect-list-table__empty">-</span>
        ),
    },
    { key: "facilityName", header: "시설물" },
    { key: "location", header: "위치" },
    {
      key: "status",
      header: "상태",
      render: (row) => {
        const presentation = STATUS_PRESENTATION[row.status];
        return (
          <span
            className={`defect-list-table__status ${presentation.className}`}
          >
            <span aria-hidden="true" />
            {presentation.label}
          </span>
        );
      },
    },
    {
      key: "createdAt",
      header: "발견일",
    },
    {
      key: "assignee",
      header: "담당자",
      render: () => (
        <span
          className="defect-list-table__assignee"
          title="담당자 정보 API 연동 예정"
        >
          -
        </span>
      ),
    },
  ];
}

function toTableRow(defect: Defect): DefectTableRow {
  return {
    id: defect.id,
    selection: "",
    defectCode: formatDefectCode(defect.id),
    thumbnail: "",
    typeLabel: defect.typeLabel,
    grade: defect.grade,
    facilityName: defect.facilityName,
    location: "-",
    status: defect.status,
    createdAt: formatDefectDate(defect.createdAt),
    assignee: "-",
  };
}

// 로딩/에러 상태는 Table 바깥에서 처리하고, 빈 데이터는 Table의 emptyMessage로 위임(FacilityTable과 동일 패턴)
// 행 전체를 클릭하면 상세로 이동한다(HAJA-17) — 사이드바에서 "하자 상세" 항목이 빠지면서 목록→상세
// 진입 경로가 이 행 클릭(및 하자 ID 링크)만 남기 때문. 체크박스는 onClick stopPropagation으로 예외 처리.
export function DefectTable({
  defects,
  isLoading,
  isError,
  onRetry,
  selectedIds,
  onSelectionChange,
}: Props) {
  const navigate = useNavigate();
  const rows = useMemo(() => (defects ?? []).map(toTableRow), [defects]);
  const visibleIds = useMemo(() => rows.map((row) => row.id), [rows]);
  const selectedVisibleCount = visibleIds.filter((id) =>
    selectedIds.has(id),
  ).length;
  const isAllSelected =
    visibleIds.length > 0 && selectedVisibleCount === visibleIds.length;
  const isPartiallySelected = selectedVisibleCount > 0 && !isAllSelected;

  const handleToggleAll = () => {
    const next = new Set(selectedIds);

    if (visibleIds.every((id) => next.has(id))) {
      visibleIds.forEach((id) => next.delete(id));
    } else {
      visibleIds.forEach((id) => next.add(id));
    }

    onSelectionChange(next);
  };

  const handleToggleRow = (id: number) => {
    const next = new Set(selectedIds);

    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }

    onSelectionChange(next);
  };

  const columns = createColumns({
    isAllSelected,
    isPartiallySelected,
    selectedIds,
    hasRows: rows.length > 0,
    onToggleAll: handleToggleAll,
    onToggleRow: handleToggleRow,
  });

  if (isLoading) {
    return (
      <div className="defect-list-table__loading" role="status">
        <span className="defect-list-table__loading-bar" />
        <span>하자 목록을 불러오는 중입니다</span>
      </div>
    );
  }

  if (isError) {
    return (
      <ErrorFallback
        message="하자 목록을 불러오지 못했습니다."
        onRetry={onRetry}
      />
    );
  }

  return (
    <div className="defect-list-table">
      <Table
        columns={columns}
        data={rows}
        emptyMessage="조회된 하자가 없습니다. 필터 조건을 변경해 보세요."
        onRowClick={(row) => navigate(`/defects/${row.id}`)}
      />
    </div>
  );
}
