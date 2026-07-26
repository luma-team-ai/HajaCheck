import { useState, type ChangeEvent, type FormEvent } from 'react';
import { useNlSearch } from '../hooks/useNlSearch';
import type { NlSearchFilters } from '../nlSearchTypes';
import type { InspectionListFilters } from '../types';

type Props = {
  onApply: (filters: Partial<InspectionListFilters>) => void;
};

// GET /api/inspections의 defectType/defectGrade/defectStatus(#878/HAJA-452, 백엔드 PR #891)는
// 배열을 그대로 받는 EXISTS 서브쿼리라, GET /api/defects(단일값 파라미터)를 겨냥해 만들어진
// DefectFilterBar.toDefectListFilters류의 "배열→단일값" 트렁케이션이 필요 없다 — 인식된 값을
// 그대로 매핑한다.
function toInspectionFilters(nlFilters: NlSearchFilters): Partial<InspectionListFilters> {
  return {
    defectType: nlFilters.type,
    defectGrade: nlFilters.grade,
    defectStatus: nlFilters.status,
  };
}

// 셋 다 빈 배열이면(적용 가능한 조건 0건) 기존 필터를 조용히 유지한다(DefectFilterBar와 동일한
// §4.4 fallback 원칙).
function hasApplicableFilters(nlFilters: NlSearchFilters): boolean {
  return nlFilters.type.length > 0 || nlFilters.grade.length > 0 || nlFilters.status.length > 0;
}

// confidenceMin은 GET /api/inspections가 지원하지 않는 필드라 unsupported_terms와 마찬가지로
// 안내만 하고 적용에서는 제외한다.
function describeUnsupported(nlFilters: NlSearchFilters, unsupportedTerms: string[]): string[] {
  const messages: string[] = [];
  if (unsupportedTerms.length > 0) {
    messages.push(`다음 조건은 아직 지원하지 않아 제외했어요: ${unsupportedTerms.join(', ')}`);
  }
  if (nlFilters.confidenceMin !== null) {
    messages.push(
      `신뢰도 ${Math.round(nlFilters.confidenceMin * 100)}% 이상 조건은 아직 점검 목록 필터에 적용할 수 없어 제외했어요`,
    );
  }
  return messages;
}

// 점검 목록(#726/HAJA-394) 자연어 검색 — 점검 자체가 아니라 "그 점검에 속한 하자 조건"을 검색
// 대상으로 삼는다(GET /api/inspections의 defectType/defectGrade/defectStatus 재조회). 칩 표시/제거는
// InspectionFilterBar가 status/facilityId 칩과 통합 관리하므로 이 컴포넌트는 검색 입력과 안내
// 메시지만 담당한다.
export function InspectionNlSearchBar({ onApply }: Props) {
  const [query, setQuery] = useState('');
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
        if (!hasApplicableFilters(result.filters)) {
          return;
        }
        onApply(toInspectionFilters(result.filters));
      },
    });
  }

  const errorMessage = error
    ? error.code === 'AI_ADDON_REQUIRED'
      ? 'AI 자연어 검색은 AI 부가 기능이 포함된 플랜에서만 사용할 수 있습니다.'
      : 'AI 검색을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.'
    : null;

  return (
    <div>
      <div className="defect-filter-bar__ai-heading">
        <span className="defect-filter-bar__sparkles" aria-hidden="true">
          ✦
        </span>
        <span>AI 검색</span>
      </div>

      <form className="defect-filter-bar__ai-field" onSubmit={handleSubmit}>
        <input
          aria-label="AI 자연어 검색"
          placeholder="자연어로 찾고 싶은 점검(하자 조건)을 입력해 주세요"
          value={query}
          onChange={handleQueryChange}
          disabled={isPending}
        />
        <button
          type="submit"
          className="defect-filter-bar__submit"
          aria-label="AI 검색 실행"
          disabled={isPending || query.trim() === ''}
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

      {!error &&
        !data?.clarifying_question &&
        data &&
        (() => {
          const notices = describeUnsupported(data.filters, data.unsupported_terms);
          return notices.length > 0 ? (
            <div className="defect-filter-bar__ai-message" role="status">
              {notices.map((notice) => (
                <div key={notice}>{notice}</div>
              ))}
            </div>
          ) : null;
        })()}
    </div>
  );
}
