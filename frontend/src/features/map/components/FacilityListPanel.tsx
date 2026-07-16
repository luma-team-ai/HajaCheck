// 검색 + 카테고리 필터 + 시설물 목록 패널 (지도 뷰 좌측) — HAJA-150(#129) 재오픈 컨벤션 준수 작업
import { FACILITY_CATEGORY_FILTERS } from '../constants';
import type { FacilityLocation } from '../types';
import { GradeBadge } from './GradeBadge';

interface FacilityListPanelProps {
  facilities: FacilityLocation[];
  isLoading: boolean;
  isError: boolean;
  searchQuery: string;
  onSearchQueryChange: (value: string) => void;
  selectedCategory: string;
  onSelectCategory: (category: string) => void;
  selectedFacilityId: number | null;
  onSelectFacility: (id: number) => void;
}

export function FacilityListPanel({
  facilities,
  isLoading,
  isError,
  searchQuery,
  onSearchQueryChange,
  selectedCategory,
  onSelectCategory,
  selectedFacilityId,
  onSelectFacility,
}: FacilityListPanelProps) {
  return (
    <div className="flex w-60 shrink-0 flex-col border-r border-border bg-surface-muted">
      <div className="flex flex-col gap-3 border-b border-border p-3">
        <input
          role="search"
          type="text"
          value={searchQuery}
          onChange={(event) => onSearchQueryChange(event.target.value)}
          placeholder="시설물명, 주소 검색"
          className="w-full rounded-full border border-border bg-white px-3 py-2 text-sm text-text-default placeholder:text-text-muted focus:outline-none"
        />
        <div className="flex flex-wrap gap-1.5">
          {FACILITY_CATEGORY_FILTERS.map((category) => (
            <button
              key={category}
              type="button"
              onClick={() => onSelectCategory(category)}
              className={`rounded-full px-3 py-1 text-xs font-medium transition ${
                selectedCategory === category
                  ? 'bg-primary text-surface'
                  : 'bg-white text-text-default border border-border'
              }`}
            >
              {category}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <p className="p-4 text-center text-sm text-text-muted">불러오는 중...</p>
        )}
        {!isLoading && isError && (
          <p className="p-4 text-center text-sm text-danger">시설물 위치를 불러오지 못했습니다.</p>
        )}
        {!isLoading && !isError && facilities.length === 0 && (
          <p className="p-4 text-center text-sm text-text-muted">조건에 맞는 시설물이 없습니다.</p>
        )}
        {!isLoading && !isError && facilities.length > 0 && (
          <ul>
            {facilities.map((facility) => (
              <li key={facility.id}>
                <button
                  type="button"
                  onClick={() => onSelectFacility(facility.id)}
                  className={`flex w-full items-start gap-3 border-b border-border px-3 py-3 text-left transition hover:bg-white ${
                    selectedFacilityId === facility.id ? 'bg-white' : ''
                  }`}
                >
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center overflow-hidden rounded-md bg-neutral-100 text-text-muted">
                    {facility.thumbnailUrl ? (
                      <img
                        src={facility.thumbnailUrl}
                        alt={facility.name}
                        className="h-full w-full object-cover"
                      />
                    ) : (
                      <span className="text-[10px]">사진 없음</span>
                    )}
                  </span>
                  <span className="flex min-w-0 flex-1 flex-col gap-1">
                    <span className="truncate text-sm font-semibold text-heading">
                      {facility.name}
                    </span>
                    <span className="truncate text-xs text-text-muted">{facility.address}</span>
                    <span className="flex items-center gap-2">
                      <GradeBadge grade={facility.highestGrade} />
                      <span className="text-[11px] text-text-muted">
                        결함 {facility.warningCount} · 주의 {facility.cautionCount}
                      </span>
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
