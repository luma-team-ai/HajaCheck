import { useMemo, useState } from 'react';
import { DefectCard } from './DefectCard';
import { DefectCardGridControls } from './DefectCardGridControls';
import type { SortOption, StatusTabValue } from './DefectCardGridControls';
import type { Defect, DefectGrade, DefectType } from '../types';
import {
  groupDefectsByImage,
  groupMatchesFilters,
  sortDefectImageGroups,
} from '../utils/defectImageGroups';

type Props = {
  defects: Defect[];
  onSelectDefect: (id: number) => void;
};

// 점검 상세(카드형) 하자 카드 그리드 — contract.md §화면 구조 ② "필터·정렬 + 하자 카드 그리드".
// 이미 GET /api/inspections/{id}/defects로 점검에 속한 하자 전체를 한 번에 받아오므로(페이지네이션
// 없음), 필터·정렬은 서버 재조회 없이 클라이언트에서 처리한다. 컨트롤 UI 자체는
// DefectCardGridControls로 분리하고, 이 컴포넌트는 필터링/정렬 로직과 카드 렌더링만 담당한다
// (Figma 정렬, #937 — 상태는 탭, 유형/등급은 퍼넬 드롭다운, 정렬은 select).
export function DefectCardGrid({ defects, onSelectDefect }: Props) {
  const [typeFilter, setTypeFilter] = useState<DefectType | ''>('');
  const [gradeFilter, setGradeFilter] = useState<DefectGrade | ''>('');
  const [statusFilter, setStatusFilter] = useState<StatusTabValue>('');
  const [sort, setSort] = useState<SortOption>('createdAt-desc');
  const groups = useMemo(() => groupDefectsByImage(defects), [defects]);

  const statusCounts = useMemo<Record<StatusTabValue, number>>(
    () => ({
      '': groups.length,
      CONFIRMED: groups.filter((group) => group.defects.some((defect) => defect.status === 'CONFIRMED')).length,
      IN_PROGRESS: groups.filter((group) => group.defects.some((defect) => defect.status === 'IN_PROGRESS')).length,
      RESOLVED: groups.filter((group) => group.defects.some((defect) => defect.status === 'RESOLVED')).length,
    }),
    [groups],
  );

  const visibleGroups = useMemo(
    () =>
      sortDefectImageGroups(
        groups.filter((group) =>
          groupMatchesFilters(group, { type: typeFilter, grade: gradeFilter, status: statusFilter }),
        ),
        sort,
      ),
    [groups, typeFilter, gradeFilter, statusFilter, sort],
  );

  return (
    <section className="defect-card-grid" aria-label="점검 하자 카드 목록">
      <DefectCardGridControls
        statusFilter={statusFilter}
        statusCounts={statusCounts}
        onStatusChange={setStatusFilter}
        typeFilter={typeFilter}
        onTypeChange={setTypeFilter}
        gradeFilter={gradeFilter}
        onGradeChange={setGradeFilter}
        sort={sort}
        onSortChange={setSort}
      />

      {visibleGroups.length === 0 ? (
        <p className="defect-card-grid__empty">조회된 하자가 없습니다. 필터 조건을 변경해 보세요.</p>
      ) : (
        <div className="defect-card-grid__grid">
          {visibleGroups.map((group) => (
            <DefectCard key={group.key} group={group} onSelect={onSelectDefect} />
          ))}
        </div>
      )}
    </section>
  );
}
