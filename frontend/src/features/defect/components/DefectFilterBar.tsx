import type { ChangeEvent } from "react";
import { Button } from "../../../shared/components/Button";
import {
  DEFECT_GRADE_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
  type DefectGrade,
  type DefectListFilters,
  type DefectStatus,
  type DefectType,
} from "../types";

type Props = {
  filters: DefectListFilters;
  onChange: (filters: DefectListFilters) => void;
};

export function DefectFilterBar({ filters, onChange }: Props) {
  function handleTypeChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as DefectType | "";
    onChange({ ...filters, type: value === "" ? undefined : value, page: 0 });
  }

  function handleGradeChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as DefectGrade | "";
    onChange({ ...filters, grade: value === "" ? undefined : value, page: 0 });
  }

  function handleStatusChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as DefectStatus | "";
    onChange({ ...filters, status: value === "" ? undefined : value, page: 0 });
  }

  const appliedFilters = [
    filters.type
      ? {
          key: "type" as const,
          label: `유형: ${DEFECT_TYPE_LABEL[filters.type]}`,
        }
      : null,
    filters.grade
      ? { key: "grade" as const, label: `등급: ${filters.grade} 이상` }
      : null,
    filters.status
      ? {
          key: "status" as const,
          label: `상태: ${DEFECT_STATUS_LABEL[filters.status]}`,
        }
      : null,
  ].filter((filter) => filter !== null);

  function handleRemoveFilter(key: "type" | "grade" | "status") {
    onChange({ ...filters, [key]: undefined, page: 0 });
  }

  function handleReset() {
    onChange({ page: 0, size: filters.size });
  }

  return (
    <section className="defect-filter-bar" aria-label="하자 목록 검색 및 필터">
      <div className="defect-filter-bar__ai-heading">
        <span className="defect-filter-bar__sparkles" aria-hidden="true">
          ✦
        </span>
        <span>AI 검색</span>
      </div>

      <div className="defect-filter-bar__ai-field">
        <input
          aria-label="AI 자연어 검색"
          placeholder="자연어로 찾고 싶은 하자를 입력해 주세요"
          title="AI 자연어 검색 기능 연동 예정"
        />
        <button
          type="button"
          className="defect-filter-bar__submit"
          aria-label="AI 검색 실행"
          title="AI 자연어 검색 기능 연동 예정"
          disabled
        >
          <span aria-hidden="true">➤</span>
        </button>
      </div>

      <div className="defect-filter-bar__manual" aria-label="상세 필터">
        <select
          className="defect-filter-bar__select"
          aria-label="유형 필터"
          value={filters.type ?? ""}
          onChange={handleTypeChange}
        >
          <option value="">전체 유형</option>
          {(Object.entries(DEFECT_TYPE_LABEL) as [DefectType, string][]).map(
            ([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ),
          )}
        </select>

        <select
          className="defect-filter-bar__select"
          aria-label="등급 필터"
          value={filters.grade ?? ""}
          onChange={handleGradeChange}
        >
          <option value="">전체 등급</option>
          {(Object.entries(DEFECT_GRADE_LABEL) as [DefectGrade, string][]).map(
            ([value, label]) => (
              <option key={value} value={value}>
                {value} · {label}
              </option>
            ),
          )}
        </select>

        <select
          className="defect-filter-bar__select"
          aria-label="상태 필터"
          value={filters.status ?? ""}
          onChange={handleStatusChange}
        >
          <option value="">전체 상태</option>
          {(Object.entries(DEFECT_STATUS_LABEL) as [DefectStatus, string][]).map(
            ([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ),
          )}
        </select>
      </div>

      {appliedFilters.length > 0 && (
        <>
          <div className="defect-filter-bar__result">
            <span className="defect-filter-bar__check" aria-hidden="true">
              ✓
            </span>
            <span>
              질문을 {appliedFilters.length}개의 검색 조건으로 적용했어요
            </span>
          </div>

          <div className="defect-filter-bar__controls">
            <span className="defect-filter-bar__label">적용된 필터:</span>
            {appliedFilters.map((filter) => (
              <button
                type="button"
                className="defect-filter-bar__chip"
                key={filter.key}
                aria-label={`${filter.label} 필터 제거`}
                onClick={() => handleRemoveFilter(filter.key)}
              >
                <span>{filter.label}</span>
                <span aria-hidden="true">×</span>
              </button>
            ))}

            <Button
              variant="secondary"
              size="sm"
              className="defect-filter-bar__reset"
              aria-label="필터 초기화"
              onClick={handleReset}
            >
              초기화
            </Button>

            <span className="defect-filter-bar__details">자세히 알아보기</span>
          </div>
        </>
      )}
    </section>
  );
}
