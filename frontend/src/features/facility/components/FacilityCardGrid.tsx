import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { Facility } from '../types';
import { FacilityCard } from './FacilityCard';

type Props = {
  facilities: Facility[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  // latestDefectId — 하자 오버레이 직행(HAJA-434 갭1) 라우팅 판단용, 하자 없으면 null.
  onSelectFacility: (id: number, latestDefectId: number | null) => void;
  // 검색/필터 적용 결과 0건일 때 "등록된 시설물이 없습니다"(#810 이전 문구)는 오해를 줄 수 있어
  // FacilityListPage가 필터 활성 여부에 따라 다른 안내 문구를 넘길 수 있게 열어둔다.
  emptyMessage?: string;
};

// 시설물 카드형 그리드(HAJA-368/#671) — 기존 FacilityTable(순수 텍스트 테이블)을 대체한다.
// 로딩/에러 상태는 그리드 바깥에서 처리하고, 빈 데이터는 안내 문구로 대체한다
// (React_코드_컨벤션.md §5 4상태 — FacilityTable과 동일한 구조를 유지).
export function FacilityCardGrid({
  facilities,
  isLoading,
  isError,
  onRetry,
  onSelectFacility,
  emptyMessage = '등록된 시설물이 없습니다. 시설물을 등록해 주세요.',
}: Props) {
  if (isLoading) {
    return (
      <LoadingSpinner className="flex items-center justify-center gap-2 px-4 py-12" />
    );
  }

  if (isError) {
    return <ErrorFallback message="시설물 목록을 불러오지 못했습니다." onRetry={onRetry} />;
  }

  if (!facilities || facilities.length === 0) {
    return (
      <p role="status" className="m-0 px-4 py-12 text-center text-sm text-text-muted">
        {emptyMessage}
      </p>
    );
  }

  return (
    <div className="grid grid-cols-3 gap-6 max-[1100px]:grid-cols-2 max-[720px]:grid-cols-1">
      {facilities.map((facility) => (
        <FacilityCard key={facility.id} facility={facility} onSelect={onSelectFacility} />
      ))}
    </div>
  );
}
