import type { ChangeEvent } from 'react';
import { useState } from 'react';
import '../../../shared/styles/layout.css';
import { Button } from '../../../shared/components/Button';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { StatusBadge } from '../components/StatusBadge';
import { useRecentInspectionsList } from '../hooks/useRecentInspectionsList';
import type { InspectionStatus, RecentInspectionsSearchFilters } from '../types';

const DEFAULT_SIZE = 10;
const SEARCH_DEBOUNCE_MS = 300;

// 상태 필터 pills — dashboard/types.ts InspectionStatus(대시보드 4단계 한글 라벨)와 값을 그대로 재사용
// (신규 라벨을 만들지 않는다 — StatusBadge/getInspectionStatusClass가 이 값 그대로 렌더링).
const STATUS_PILLS: { value: InspectionStatus | undefined; label: string }[] = [
  { value: undefined, label: '전체' },
  { value: '분석중', label: '분석중' },
  { value: '검수대기', label: '검수대기' },
  { value: '분석실패', label: '분석실패' },
  { value: '검수확정', label: '검수확정' },
  { value: '완료', label: '완료' },
];

// "시설물 종류" 카테고리 — facility feature의 FACILITY_TYPE_OPTIONS(#731, "{종류}-{점검유형}-{주기}"
// 조합 12종)에서 종류 부분만 뽑은 값이지만, feature 간 직접 import 금지 컨벤션에 따라 로컬로
// 재정의한다(facility.constants.ts findFacilityTypeCycleMonths 참고). 백엔드가 접두(prefix) 매칭을
// 하므로 이 4개 값만으로 컴파운드형 시설물 종류도 전부 커버된다.
const FACILITY_TYPE_CATEGORIES = ['건물', '교량', '도로', '기타'] as const;

// RecentInspectionsTable.tsx(대시보드 카드, 2026-07-25 Figma 재대조 완료)와 동일 톤 유지 — 헤더
// bg-pink-50 text-xs uppercase, 본문 text-sm. 색 토큰은 전부 colors.ts 단일 관리를 그대로 재사용한다.
const TH_BASE_CLASS =
  `text-left text-xs uppercase tracking-wide ${DASHBOARD_COLOR_CLASS.labelText} font-medium py-3 px-4 bg-pink-50 border-b border-[#eee] whitespace-nowrap`;
const TD_CLASS = 'p-3 text-sm border-b border-[#f4f4f4] whitespace-nowrap';

// 대시보드 "최근 점검" 카드의 "전체보기" 버튼 진입 화면(신규) — 위젯(RecentInspectionsTable, 상위
// 10건 고정)과 달리 페이지네이션+검색+상태/시설물종류 필터를 지원하는 전체 목록.
//
// 설계 결정(2026-07-26 Figma 재대조로 갱신):
// - "시설물 종류" 드롭다운: 특정 시설물 선택(facilityId)이 아니라 카테고리(건물/교량/도로/기타)
//   필터다. 백엔드가 facility.type을 접두(prefix) 매칭하므로 #731 컴파운드값("건물-긴급-1개월")도
//   함께 잡힌다 — 시설물 목록을 별도로 fetch할 필요가 없어 단순해졌다(이전 버전의 facilityId select는
//   폐기, useDashboardFacilityOptions 훅도 함께 제거).
// - 행 클릭/체크박스: 스펙 미확정 항목이라 이번 1차 버전은 정보 제공용 표로만 렌더링한다(선택·네비게이션
//   없음) — 기존 위젯의 행 선택 인터랙션(RecentInspectionsTable)은 이 페이지로 옮기지 않았다.
export function RecentInspectionsFullListPage() {
  const [searchInput, setSearchInput] = useState('');
  const debouncedSearch = useDebouncedValue(searchInput, SEARCH_DEBOUNCE_MS);
  const [filters, setFilters] = useState<RecentInspectionsSearchFilters>({
    page: 0,
    size: DEFAULT_SIZE,
  });

  const trimmedSearch = debouncedSearch.trim();
  const effectiveFilters: RecentInspectionsSearchFilters = {
    ...filters,
    query: trimmedSearch === '' ? undefined : trimmedSearch,
  };

  const { data, isLoading, isError, refetch } = useRecentInspectionsList(effectiveFilters);

  const size = filters.size ?? DEFAULT_SIZE;
  const currentPage = (filters.page ?? 0) + 1; // TableFooterPagination은 1-based
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / size));

  function handleSearchChange(event: ChangeEvent<HTMLInputElement>) {
    setSearchInput(event.target.value);
    setFilters((prev) => ({ ...prev, page: 0 }));
  }

  function handleStatusChange(status: InspectionStatus | undefined) {
    setFilters((prev) => ({ ...prev, status, page: 0 }));
  }

  function handleFacilityTypeChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value;
    setFilters((prev) => ({ ...prev, facilityType: value === '' ? undefined : value, page: 0 }));
  }

  function handlePageChange(page: number) {
    setFilters((prev) => ({ ...prev, page: page - 1 }));
  }

  function handlePageSizeChange(nextSize: number) {
    setFilters((prev) => ({ ...prev, size: nextSize, page: 0 }));
  }

  const content = data?.content ?? [];

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <div className="flex items-baseline gap-3">
          <h1 className="dashboard-page-title">최근 점검 전체보기</h1>
          <span className={`text-sm ${DASHBOARD_COLOR_CLASS.mutedText}`}>
            총 {totalElements.toLocaleString()}건
          </span>
        </div>
      </div>

      {/* Figma 재대조(2026-07-26): 검색창이 flex-1로 남은 폭을 전부 차지해 종류 드롭다운이
          다음 줄로 밀려나던 문제 — 검색창 폭을 max-w-80(320px)으로 고정해 한 줄에 나란히 배치.
          상태 필터 pill도 별도 줄이 아니라 같은 줄에 나란히 배치한다(2026-07-26 2차 재대조). */}
      <div className="flex flex-wrap items-center gap-3">
        <input
          type="search"
          value={searchInput}
          onChange={handleSearchChange}
          placeholder="시설물, 담당자 검색"
          aria-label="시설물, 담당자 검색"
          className={`w-full max-w-80 rounded-full border ${DASHBOARD_COLOR_CLASS.filterInputBorder} px-4 py-2.5 text-sm outline-none ${DASHBOARD_COLOR_CLASS.filterInputFocusBorder}`}
        />
        <select
          value={filters.facilityType ?? ''}
          onChange={handleFacilityTypeChange}
          aria-label="시설물 종류 필터"
          className={`rounded-full border ${DASHBOARD_COLOR_CLASS.filterInputBorder} px-4 py-2.5 text-sm ${DASHBOARD_COLOR_CLASS.bodyText}`}
        >
          <option value="">시설물 종류: 전체</option>
          {FACILITY_TYPE_CATEGORIES.map((category) => (
            <option key={category} value={category}>
              {category}
            </option>
          ))}
        </select>

        <div role="tablist" aria-label="점검 상태 필터" className="flex flex-wrap gap-2">
          {STATUS_PILLS.map((pill) => (
            <Button
              key={pill.label}
              type="button"
              role="tab"
              aria-selected={filters.status === pill.value}
              variant={filters.status === pill.value ? 'primary' : 'secondary'}
              size="sm"
              onClick={() => handleStatusChange(pill.value)}
            >
              {pill.label}
            </Button>
          ))}
        </div>
      </div>

      {isLoading && <LoadingSpinner />}
      {isError && (
        <div className="dashboard-card-status flex items-center gap-3">
          <span>최근 점검 목록을 불러오지 못했습니다.</span>
          <Button variant="secondary" size="sm" onClick={() => refetch()}>
            다시 시도
          </Button>
        </div>
      )}
      {!isLoading && !isError && content.length === 0 && (
        <p className="dashboard-card-status">조건에 맞는 점검 이력이 없습니다.</p>
      )}

      {!isLoading && !isError && content.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-[#eee]">
          <table aria-label="최근 점검 전체 목록" className="w-full border-collapse">
            <thead>
              <tr>
                <th className={TH_BASE_CLASS}>시설물</th>
                <th className={TH_BASE_CLASS}>점검일</th>
                <th className={TH_BASE_CLASS}>담당자</th>
                <th className={TH_BASE_CLASS}>하자 수</th>
                <th className={TH_BASE_CLASS}>상태</th>
              </tr>
            </thead>
            <tbody>
              {content.map((item) => (
                <tr key={item.id}>
                  <td className={TD_CLASS}>{item.facilityName}</td>
                  <td className={TD_CLASS}>{item.inspectedAt}</td>
                  <td className={TD_CLASS}>{item.inspector}</td>
                  <td className={TD_CLASS}>{item.defectCount}건</td>
                  <td className={TD_CLASS}>
                    <StatusBadge status={item.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!isLoading && !isError && content.length > 0 && (
        <TableFooterPagination
          pageSize={size}
          onPageSizeChange={handlePageSizeChange}
          currentPage={currentPage}
          totalPages={totalPages}
          totalItems={totalElements}
          onPageChange={handlePageChange}
        />
      )}
    </div>
  );
}
