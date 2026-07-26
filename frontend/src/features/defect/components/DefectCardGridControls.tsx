import { useEffect, useRef, useState, type ChangeEvent } from 'react';
// defect-filter-bar__select 클래스는 DefectListPage.css에 정의돼 있다 — 이 컨트롤이 렌더링되는
// InspectionDefectsPage는 그 CSS를 별도로 로드하지 않으므로 여기서 함께 임포트해 select 스타일을
// 재사용한다(신규 스타일 중복 정의 금지).
import '../pages/DefectListPage.css';
import { DEFECT_GRADE_LABEL, DEFECT_TYPE_LABEL } from '../types';
import type { DefectGrade, DefectType } from '../types';

// 상태 탭 값 — 이 화면(점검 상세 카드형)엔 검수확정 이후 하자만 들어온다는 비즈니스 규칙 확인됨
// (사용자 확정 지시, #937) — DETECTED/ACTION_PENDING 탭은 만들지 않는다.
export type StatusTabValue = '' | 'CONFIRMED' | 'IN_PROGRESS' | 'RESOLVED';

export type SortOption = 'createdAt-desc' | 'createdAt-asc' | 'confidence-desc' | 'confidence-asc';

export const SORT_OPTION_LABEL: Record<SortOption, string> = {
  'createdAt-desc': '최신 등록순',
  'createdAt-asc': '오래된순',
  'confidence-desc': 'AI 신뢰도 높은순',
  'confidence-asc': 'AI 신뢰도 낮은순',
};

const STATUS_TABS: { value: StatusTabValue; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'CONFIRMED', label: '검수확정' },
  { value: 'IN_PROGRESS', label: '조치중' },
  { value: 'RESOLVED', label: '조치완료' },
];

type Props = {
  statusFilter: StatusTabValue;
  statusCounts: Record<StatusTabValue, number>;
  onStatusChange: (value: StatusTabValue) => void;
  typeFilter: DefectType | '';
  onTypeChange: (value: DefectType | '') => void;
  gradeFilter: DefectGrade | '';
  onGradeChange: (value: DefectGrade | '') => void;
  sort: SortOption;
  onSortChange: (value: SortOption) => void;
};

// 점검 상세(카드형) 필터·정렬 툴바(Figma 정렬, #937) — 상태는 탭, 유형/등급은 퍼넬 아이콘 드롭다운,
// 정렬은 select. DefectCardGrid에서 분리해 파일 길이를 관리한다(필터 로직 자체는 DefectCardGrid가
// useMemo로 그대로 소유 — 이 컴포넌트는 순수 컨트롤 UI만 담당).
export function DefectCardGridControls({
  statusFilter,
  statusCounts,
  onStatusChange,
  typeFilter,
  onTypeChange,
  gradeFilter,
  onGradeChange,
  sort,
  onSortChange,
}: Props) {
  const [isFunnelOpen, setIsFunnelOpen] = useState(false);
  const funnelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isFunnelOpen) return undefined;

    function handleOutsideClick(event: MouseEvent) {
      if (funnelRef.current && !funnelRef.current.contains(event.target as Node)) {
        setIsFunnelOpen(false);
      }
    }

    document.addEventListener('mousedown', handleOutsideClick);
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, [isFunnelOpen]);

  const hasActiveTypeGradeFilter = typeFilter !== '' || gradeFilter !== '';

  return (
    <div className="defect-card-grid__toolbar">
      <div className="defect-card-grid__status-tabs" role="tablist" aria-label="상태 필터">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value || 'all'}
            type="button"
            role="tab"
            aria-selected={statusFilter === tab.value}
            className={`defect-card-grid__status-tab${statusFilter === tab.value ? ' is-active' : ''}`}
            onClick={() => onStatusChange(tab.value)}
          >
            {tab.label}{' '}
            <span className="defect-card-grid__status-tab-count">{statusCounts[tab.value]}</span>
          </button>
        ))}
      </div>

      <div className="defect-card-grid__toolbar-actions">
        <div className="defect-card-grid__funnel" ref={funnelRef}>
          <button
            type="button"
            className={`defect-card-grid__funnel-button${hasActiveTypeGradeFilter ? ' has-active-filter' : ''}`}
            aria-haspopup="true"
            aria-expanded={isFunnelOpen}
            aria-label="유형·등급 필터"
            onClick={() => setIsFunnelOpen((prev) => !prev)}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M2 3h12l-4.5 5.5V13l-3 1.5V8.5L2 3z"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinejoin="round"
              />
            </svg>
          </button>

          {isFunnelOpen && (
            <div className="defect-card-grid__funnel-panel" role="group" aria-label="유형·등급 필터 옵션">
              <select
                className="defect-filter-bar__select"
                aria-label="유형 필터"
                value={typeFilter}
                onChange={(event: ChangeEvent<HTMLSelectElement>) =>
                  onTypeChange(event.target.value as DefectType | '')
                }
              >
                <option value="">전체 유형</option>
                {(Object.entries(DEFECT_TYPE_LABEL) as [DefectType, string][]).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>

              <select
                className="defect-filter-bar__select"
                aria-label="등급 필터"
                value={gradeFilter}
                onChange={(event: ChangeEvent<HTMLSelectElement>) =>
                  onGradeChange(event.target.value as DefectGrade | '')
                }
              >
                <option value="">전체 등급</option>
                {(Object.entries(DEFECT_GRADE_LABEL) as [DefectGrade, string][]).map(([value, label]) => (
                  <option key={value} value={value}>
                    {value} · {label}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>

        <select
          className="defect-card-grid__sort-select"
          aria-label="정렬 기준"
          value={sort}
          onChange={(event: ChangeEvent<HTMLSelectElement>) => onSortChange(event.target.value as SortOption)}
        >
          {(Object.entries(SORT_OPTION_LABEL) as [SortOption, string][]).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
