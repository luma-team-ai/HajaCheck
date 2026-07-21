import { Button } from "../../../shared/components/Button";
import {
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
  type DefectListFilters,
} from "../types";

type Props = {
  filters: DefectListFilters;
  onChange: (filters: DefectListFilters) => void;
};

export function DefectFilterBar({ filters, onChange }: Props) {
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
