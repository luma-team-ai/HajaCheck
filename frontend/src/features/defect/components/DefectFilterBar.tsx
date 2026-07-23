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

// 등급 심각도 오름차순 순서(A=양호 … E=중대). 백엔드 GET /api/defects의 grade 파라미터는
// `>=`(이상, 심각도 min 이상) 의미로만 구현돼 있어(DefectRepositoryImpl.java의 greaterThanOrEqualTo),
// 정확히 표현 가능한 다중 등급은 "최고 등급 E로 끝나는 연속 집합(min..E)"뿐이다.
const GRADE_ORDER: Record<DefectGrade, number> = { A: 0, B: 1, C: 2, D: 3, E: 4 };

// grade 배열을 단일 `>= min` 파라미터로 변환한다. `>= min`은 심각도 min..E 를 정확히 표현하므로,
// E로 끝나는 연속 집합만 그대로 적용한다. 그 외는 상위 등급까지 오노출되므로 미적용 + 안내(리뷰 P2):
//  - 단일 non-E(예: ["A"] = "A만" 의도)를 `>= A`로 보내면 A~E 전부 노출됨(P2-1)
//  - E로 끝나지 않는 "~이하" 범위(예: ["A","B"])는 `<=` 필터가 없어 표현 불가
//  - 비연속 집합(예: ["A","C"])도 단일 임계값으로 표현 불가
// structured output은 배열 순서를 보장하지 않으므로(예: ["E","D"] 내림차순 가능) 심각도 오름차순
// 정렬 후 판별한다(P2-3).
function resolveGradeFilter(grade: DefectGrade[]): { value: DefectGrade | undefined; notice: string | null } {
  if (grade.length === 0) {
    return { value: undefined, notice: null };
  }
  // 중복 등급 제거 후 정렬 — ["E","E"]·["D","E","E"] 같은 중복이 연속성 판정을 깨 오적용되지 않게(P3-1).
  const sorted = Array.from(new Set(grade)).sort((a, b) => GRADE_ORDER[a] - GRADE_ORDER[b]);
  const contiguousToE =
    sorted[sorted.length - 1] === "E" &&
    sorted.every((g, i) => GRADE_ORDER[g] === GRADE_ORDER[sorted[0]] + i);
  if (contiguousToE) {
    // `>= sorted[0]` == sorted[0]..E, 정확히 표현됨
    return { value: sorted[0], notice: null };
  }
  return {
    value: undefined,
    notice: `등급 ${sorted.join(", ")} 조건은 아직 목록 필터에 정확히 적용할 수 없어 제외했어요`,
  };
}

// NlSearchFilters는 다중 선택(배열)을 반환하지만 GET /api/defects(DefectListFilters)는 아직 단일값
// 파라미터만 지원한다(백엔드 확장은 이번 범위 밖) — 배열의 첫 값만 적용해 기존 단일 필터에 맞춘다.
function toDefectListFilters(nlFilters: NlSearchFilters): Partial<DefectListFilters> {
  return {
    type: nlFilters.type[0],
    grade: resolveGradeFilter(nlFilters.grade).value,
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
  const gradeNotice = resolveGradeFilter(nlFilters.grade).notice;
  if (gradeNotice) {
    messages.push(gradeNotice);
  }
  if (nlFilters.status.length > 1) {
    const skipped = nlFilters.status.slice(1).map((value) => DEFECT_STATUS_LABEL[value]).join(", ");
    messages.push(`상태 ${skipped}은(는) 아직 함께 적용할 수 없어 제외했어요`);
  }
  if (nlFilters.confidenceMin !== null) {
    messages.push(
      `신뢰도 ${Math.round(nlFilters.confidenceMin * 100)}% 이상 조건은 아직 목록 필터에 적용할 수 없어 제외했어요`,
    );
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
        // 적용 가능한 조건이 0건이면(전부 unsupported·미표현 등급·confidenceMin 등) 사용자의 기존
        // 수동필터를 조용히 덮어쓰지 않고 그대로 유지한다 — 안내(notices)만 노출한다(리뷰 P2).
        const applied = toDefectListFilters(result.filters);
        const hasApplicable =
          applied.type !== undefined || applied.grade !== undefined || applied.status !== undefined;
        if (!hasApplicable) {
          return;
        }
        onChange({ ...filters, ...applied, page: 0 });
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
