import { useMemo, useState, type ChangeEvent } from 'react';
import { DefectCard } from './DefectCard';
import {
  DEFECT_GRADE_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
} from '../types';
import type { Defect, DefectGrade, DefectStatus, DefectType } from '../types';

type Props = {
  defects: Defect[];
  onSelectDefect: (id: number) => void;
};

type SortOption = 'createdAt-desc' | 'createdAt-asc' | 'confidence-desc' | 'confidence-asc';

const SORT_OPTION_LABEL: Record<SortOption, string> = {
  'createdAt-desc': '최신 등록순',
  'createdAt-asc': '오래된순',
  'confidence-desc': 'AI 신뢰도 높은순',
  'confidence-asc': 'AI 신뢰도 낮은순',
};

function sortDefects(defects: Defect[], sort: SortOption): Defect[] {
  const sorted = [...defects];
  switch (sort) {
    case 'createdAt-asc':
      return sorted.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    case 'confidence-desc':
      return sorted.sort((a, b) => b.confidence - a.confidence);
    case 'confidence-asc':
      return sorted.sort((a, b) => a.confidence - b.confidence);
    case 'createdAt-desc':
    default:
      return sorted.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }
}

// 점검 상세(카드형) 하자 카드 그리드 — contract.md §화면 구조 ② "필터·정렬 + 하자 카드 그리드".
// 이미 GET /api/inspections/{id}/defects로 점검에 속한 하자 전체를 한 번에 받아오므로(페이지네이션
// 없음), 필터·정렬은 서버 재조회 없이 클라이언트에서 처리한다.
export function DefectCardGrid({ defects, onSelectDefect }: Props) {
  const [typeFilter, setTypeFilter] = useState<DefectType | ''>('');
  const [gradeFilter, setGradeFilter] = useState<DefectGrade | ''>('');
  const [statusFilter, setStatusFilter] = useState<DefectStatus | ''>('');
  const [sort, setSort] = useState<SortOption>('createdAt-desc');

  const visibleDefects = useMemo(() => {
    const filtered = defects.filter(
      (defect) =>
        (typeFilter === '' || defect.type === typeFilter) &&
        (gradeFilter === '' || defect.grade === gradeFilter) &&
        (statusFilter === '' || defect.status === statusFilter),
    );
    return sortDefects(filtered, sort);
  }, [defects, typeFilter, gradeFilter, statusFilter, sort]);

  function handleTypeChange(event: ChangeEvent<HTMLSelectElement>) {
    setTypeFilter(event.target.value as DefectType | '');
  }
  function handleGradeChange(event: ChangeEvent<HTMLSelectElement>) {
    setGradeFilter(event.target.value as DefectGrade | '');
  }
  function handleStatusChange(event: ChangeEvent<HTMLSelectElement>) {
    setStatusFilter(event.target.value as DefectStatus | '');
  }
  function handleSortChange(event: ChangeEvent<HTMLSelectElement>) {
    setSort(event.target.value as SortOption);
  }

  return (
    <section className="defect-card-grid" aria-label="점검 하자 카드 목록">
      <div className="defect-card-grid__controls" aria-label="하자 카드 필터·정렬">
        <select
          className="defect-filter-bar__select"
          aria-label="유형 필터"
          value={typeFilter}
          onChange={handleTypeChange}
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
          onChange={handleGradeChange}
        >
          <option value="">전체 등급</option>
          {(Object.entries(DEFECT_GRADE_LABEL) as [DefectGrade, string][]).map(([value, label]) => (
            <option key={value} value={value}>
              {value} · {label}
            </option>
          ))}
        </select>

        <select
          className="defect-filter-bar__select"
          aria-label="상태 필터"
          value={statusFilter}
          onChange={handleStatusChange}
        >
          <option value="">전체 상태</option>
          {(Object.entries(DEFECT_STATUS_LABEL) as [DefectStatus, string][]).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>

        <select
          className="defect-filter-bar__select"
          aria-label="정렬 기준"
          value={sort}
          onChange={handleSortChange}
        >
          {(Object.entries(SORT_OPTION_LABEL) as [SortOption, string][]).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>

      {visibleDefects.length === 0 ? (
        <p className="defect-card-grid__empty">조회된 하자가 없습니다. 필터 조건을 변경해 보세요.</p>
      ) : (
        <div className="defect-card-grid__grid">
          {visibleDefects.map((defect) => (
            <DefectCard key={defect.id} defect={defect} onSelect={onSelectDefect} />
          ))}
        </div>
      )}
    </section>
  );
}
