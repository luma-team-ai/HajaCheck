// 검색 + 카테고리 필터 + 시설물 목록 패널 (지도 뷰 좌측) — HAJA-150(#129) 재오픈 컨벤션 준수 작업
import { FACILITY_CATEGORY_FILTERS, FALLBACK_GRADE_COLOR, GRADE_COLOR } from '../constants';
import type { FacilityLocation } from '../types';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { GradeBadge } from './GradeBadge';

// 결함/주의 건수 심각도 색상 — 등급 범례(MapLegend)와 동일한 GRADE_COLOR 팔레트를 재사용해
// 신호등(빨강/노랑/초록) 3단계로 단순화. Figma 대조로 임계값 확인(2026-07-17).
// export: 단위 테스트에서 경계값(3, 10)을 직접 검증하기 위해(code-reviewer P2).
// count가 null이면 하자건수 API 미연동(#661) 상태 — 회색(FALLBACK_GRADE_COLOR)으로 폴백한다.
export function getCountSeverityColor(count: number | null): string {
  if (count == null) return FALLBACK_GRADE_COLOR;
  if (count >= 10) return GRADE_COLOR.E; // 빨강
  if (count >= 3) return GRADE_COLOR.C; // 노랑
  return GRADE_COLOR.A; // 초록
}

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
    <div className="flex w-80 shrink-0 flex-col border-r border-border bg-surface-muted">
      <div className="flex flex-col gap-3 border-b border-border p-3">
        {/* role="search"는 검색 입력을 감싸는 랜드마크 컨테이너에 부여하는 것이 올바른 사용법(ARIA search landmark).
            input 자체에 부여하면 스크린리더가 잘못된 role 매핑을 시도하므로 컨테이너로 이동(P3) */}
        <div role="search" className="relative flex items-center w-full">
          <svg
            className="pointer-events-none absolute left-3.5 h-4 w-4 text-text-muted"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
            strokeWidth="2"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="8" />
            <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-4.3-4.3" />
          </svg>
          <input
            type="text"
            value={searchQuery}
            onChange={(event) => onSearchQueryChange(event.target.value)}
            placeholder="지역·시설물 검색"
            aria-label="지역·시설물 검색"
            className="w-full rounded-full border border-border bg-white pl-9 pr-3 py-2 text-sm text-text-default placeholder:text-text-muted focus:outline-none"
          />
        </div>
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
        {isLoading && <LoadingSpinner className="flex items-center justify-center gap-2 p-4" />}
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
                {/* 카드 간 구분선(border-b) 제거 및 호버 밝아짐 효과 제거 — Figma 시안 정합성 준수(2026-07-17) */}
                <button
                  type="button"
                  onClick={() => onSelectFacility(facility.id)}
                  className={`flex w-full items-start gap-3 px-3 py-3 text-left transition ${
                    selectedFacilityId === facility.id ? 'bg-primary/5' : ''
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
                    <span className="flex items-start justify-between gap-2">
                      <span className="min-w-0 flex-1 truncate text-sm font-semibold text-heading">
                        {facility.name}
                      </span>
                      <GradeBadge grade={facility.highestGrade} />
                    </span>
                    <span className="truncate text-xs text-text-muted">{facility.address}</span>
                    <span className="flex items-center gap-3 text-[11px] text-text-muted">
                      <span className="flex items-center gap-1">
                        <span
                          className="inline-block h-2.5 w-2.5 rounded-full"
                          style={{ backgroundColor: getCountSeverityColor(facility.warningCount) }}
                        />
                        결함 {facility.warningCount ?? '-'}
                      </span>
                      <span className="flex items-center gap-1">
                        <span
                          className="inline-block h-2.5 w-2.5 rounded-full"
                          style={{ backgroundColor: getCountSeverityColor(facility.cautionCount) }}
                        />
                        주의 {facility.cautionCount ?? '-'}
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
