import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { useNlSearch } from '../hooks/useNlSearch';
import type { InspectionListFilters } from '../types';
import {
  describeUnsupported,
  hasApplicableInspectionFilters,
  toInspectionFilters,
} from '../utils/inspectionNlSearch';

type Props = {
  onApply: (filters: InspectionListFilters) => void;
};

// 점검 목록(#726/HAJA-394) 자연어 검색 — 점검 자체가 아니라 "그 점검에 속한 하자 조건"을 검색
// 대상으로 삼는다(GET /api/inspections의 defectType/defectGrade/defectStatus 재조회). 칩 표시/제거는
// InspectionFilterBar가 status/facilityId 칩과 통합 관리하므로 이 컴포넌트는 검색 입력과 안내
// 메시지만 담당한다.
export function InspectionNlSearchBar({ onApply }: Props) {
  const [query, setQuery] = useState('');
  const lastSubmittedQuery = useRef('');
  const { search, data, error, isPending, reset } = useNlSearch();

  function handleQueryChange(event: ChangeEvent<HTMLInputElement>) {
    setQuery(event.target.value);
  }

  function executeSearch(trimmed: string) {
    reset();
    search(trimmed, {
      onSuccess: (result) => {
        // 되묻는 질문이 있으면 필터를 적용하지 않고 질문만 노출한다(§2.3/§4.3).
        if (result.clarifying_question) {
          return;
        }
        const nextFilters = toInspectionFilters(result.filters);
        if (!hasApplicableInspectionFilters(nextFilters)) {
          return;
        }
        onApply(nextFilters);
      },
    });
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || isPending) {
      return;
    }
    lastSubmittedQuery.current = trimmed;
    executeSearch(trimmed);
  }

  function handleRetry() {
    if (lastSubmittedQuery.current && !isPending) {
      executeSearch(lastSubmittedQuery.current);
    }
  }

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

      {isPending && <AILoadingIndicator message="검색 조건을 분석하고 있습니다..." />}

      {error?.code === 'AI_ADDON_REQUIRED' && (
        <div className="defect-filter-bar__ai-message defect-filter-bar__ai-message--error" role="alert">
          AI 자연어 검색은 AI 부가 기능이 포함된 플랜에서만 사용할 수 있습니다.
        </div>
      )}

      {error && error.code !== 'AI_ADDON_REQUIRED' && <AIErrorFallback onRetry={handleRetry} />}

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
