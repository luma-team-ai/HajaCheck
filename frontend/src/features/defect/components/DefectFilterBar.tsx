import { useState, type ChangeEvent, type FormEvent } from "react";
import { Button } from "../../../shared/components/Button";
import { useNlSearch } from "../hooks/useNlSearch";
import type { NlSearchFilters } from "../nlSearchTypes";
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

// NlSearchFilters는 다중 선택(배열)을 반환하지만 GET /api/defects(DefectListFilters)는 아직 단일값
// 파라미터만 지원한다(백엔드 확장은 이번 범위 밖) — 배열의 첫 값만 적용해 기존 단일 필터에 맞춘다.
function toDefectListFilters(nlFilters: NlSearchFilters): Partial<DefectListFilters> {
  return {
    type: nlFilters.type[0],
    grade: nlFilters.grade[0],
    status: nlFilters.status[0],
  };
}

// AI가 정확히 인식한 다중 값 중 첫 값만 적용될 때, unsupported_terms와 달리 조용히 사라지지
// 않도록 잘려나간 값을 사용자에게 안내하는 문구를 만든다(리뷰 P2 — 안전점검 도구에서 인식된
// 조건이 말없이 누락되면 안 됨).
function describeTruncatedFilters(nlFilters: NlSearchFilters): string[] {
  const messages: string[] = [];
  if (nlFilters.type.length > 1) {
    const skipped = nlFilters.type.slice(1).map((value) => DEFECT_TYPE_LABEL[value]).join(", ");
    messages.push(`유형 ${skipped}은(는) 아직 함께 적용할 수 없어 제외했어요`);
  }
  if (nlFilters.grade.length > 1) {
    messages.push(`등급 ${nlFilters.grade.slice(1).join(", ")}은(는) 아직 함께 적용할 수 없어 제외했어요`);
  }
  if (nlFilters.status.length > 1) {
    const skipped = nlFilters.status.slice(1).map((value) => DEFECT_STATUS_LABEL[value]).join(", ");
    messages.push(`상태 ${skipped}은(는) 아직 함께 적용할 수 없어 제외했어요`);
  }
  return messages;
}

export function DefectFilterBar({ filters, onChange }: Props) {
  const [query, setQuery] = useState("");
  const { search, data, error, isPending, reset } = useNlSearch();

  function handleQueryChange(event: ChangeEvent<HTMLInputElement>) {
    setQuery(event.target.value);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || isPending) {
      return;
    }
    reset();
    search(trimmed, {
      onSuccess: (result) => {
        // 되묻는 질문이 있으면 필터를 적용하지 않고 질문만 노출한다(§2.3/§4.3).
        if (result.clarifying_question) {
          return;
        }
        onChange({ ...filters, ...toDefectListFilters(result.filters), page: 0 });
      },
    });
  }

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
    reset();
  }

  function handleReset() {
    onChange({ page: 0, size: filters.size });
    reset();
  }

  const errorMessage = error
    ? error.code === "AI_ADDON_REQUIRED"
      ? "AI 자연어 검색은 AI 부가 기능이 포함된 플랜에서만 사용할 수 있습니다."
      : "AI 검색을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요."
    : null;

  return (
    <section className="defect-filter-bar" aria-label="하자 목록 검색 및 필터">
      <div className="defect-filter-bar__ai-heading">
        <span className="defect-filter-bar__sparkles" aria-hidden="true">
          ✦
        </span>
        <span>AI 검색</span>
      </div>

      <form className="defect-filter-bar__ai-field" onSubmit={handleSubmit}>
        <input
          aria-label="AI 자연어 검색"
          placeholder="자연어로 찾고 싶은 하자를 입력해 주세요"
          value={query}
          onChange={handleQueryChange}
          disabled={isPending}
        />
        <button
          type="submit"
          className="defect-filter-bar__submit"
          aria-label="AI 검색 실행"
          disabled={isPending || query.trim() === ""}
        >
          <span aria-hidden="true">➤</span>
        </button>
      </form>

      {errorMessage && (
        <div className="defect-filter-bar__ai-message defect-filter-bar__ai-message--error" role="alert">
          {errorMessage}
        </div>
      )}

      {!error && data?.clarifying_question && (
        <div className="defect-filter-bar__ai-message" role="status">
          {data.clarifying_question}
        </div>
      )}

      {!error && !data?.clarifying_question && data && (() => {
        const notices = [
          ...(data.unsupported_terms.length > 0
            ? [`다음 조건은 아직 지원하지 않아 제외했어요: ${data.unsupported_terms.join(", ")}`]
            : []),
          ...describeTruncatedFilters(data.filters),
        ];
        return notices.length > 0 ? (
          <div className="defect-filter-bar__ai-message" role="status">
            {notices.map((notice) => (
              <div key={notice}>{notice}</div>
            ))}
          </div>
        ) : null;
      })()}

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
