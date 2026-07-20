import { Link, useNavigate } from "react-router-dom";
import { ErrorFallback } from "../../../shared/components/ErrorFallback";
import type { TableColumn } from "../../../shared/components/Table";
import { Table } from "../../../shared/components/Table";
import type { Defect, DefectGrade, DefectStatus } from "../types";

type Props = {
  defects: Defect[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
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

const GRADE_CLASSES: Record<DefectGrade, string> = {
  A: "border-emerald-200 bg-emerald-50 text-emerald-600",
  B: "border-sky-200 bg-sky-50 text-sky-600",
  C: "border-amber-200 bg-amber-50 text-amber-600",
  D: "border-orange-200 bg-orange-50 text-orange-600",
  E: "border-red-200 bg-red-50 text-red-500",
};

const STATUS_PRESENTATION: Record<
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

const COLUMNS: TableColumn<DefectTableRow>[] = [
  {
    key: "selection",
    header: "",
    render: (row) => (
      <input
        type="checkbox"
        aria-label={`${row.defectCode} 선택`}
        onClick={(e) => e.stopPropagation()}
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
        <span className={`defect-list-table__status ${presentation.className}`}>
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

function toTableRow(defect: Defect): DefectTableRow {
  return {
    id: defect.id,
    selection: "",
    defectCode: `DEF-${String(defect.id).padStart(4, "0")}`,
    thumbnail: "",
    typeLabel: defect.typeLabel,
    grade: defect.grade,
    facilityName: defect.facilityName,
    location: "-",
    status: defect.status,
    createdAt: defect.createdAt.slice(2, 10).replaceAll("-", "."),
    assignee: "-",
  };
}

// 로딩/에러 상태는 Table 바깥에서 처리하고, 빈 데이터는 Table의 emptyMessage로 위임(FacilityTable과 동일 패턴)
// 행 전체를 클릭하면 상세로 이동한다(HAJA-17) — 사이드바에서 "하자 상세" 항목이 빠지면서 목록→상세
// 진입 경로가 이 행 클릭(및 하자 ID 링크)만 남기 때문. 체크박스는 onClick stopPropagation으로 예외 처리.
export function DefectTable({ defects, isLoading, isError, onRetry }: Props) {
  const navigate = useNavigate();

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
        columns={COLUMNS}
        data={(defects ?? []).map(toTableRow)}
        emptyMessage="조회된 하자가 없습니다. 필터 조건을 변경해 보세요."
        onRowClick={(row) => navigate(`/defects/${row.id}`)}
      />
    </div>
  );
}
