// 선택된 시설물 팝업 카드 (지도 우측 영역 절대위치 오버레이)
import type { FacilityLocation } from '../types';
import { GradeBadge } from './GradeBadge';

interface SelectedFacilityPopupProps {
  facility: FacilityLocation;
  onViewDetail: () => void;
  onGoToInspectionResult: () => void;
}

export function SelectedFacilityPopup({
  facility,
  onViewDetail,
  onGoToInspectionResult,
}: SelectedFacilityPopupProps) {
  return (
    <div className="absolute bottom-4 right-4 flex w-64 flex-col gap-2 rounded-2xl border border-border bg-white p-3 shadow-lg">
      <div className="flex items-center gap-3">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-neutral-100 text-text-muted">
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
        <span className="flex min-w-0 flex-col gap-1">
          <span className="truncate text-sm font-semibold text-heading">{facility.name}</span>
          <GradeBadge grade={facility.highestGrade} />
        </span>
      </div>
      <p className="text-xs text-text-muted">
        결함 {facility.warningCount}건 · 주의 {facility.cautionCount}건이 등록되어 있습니다.
      </p>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onViewDetail}
          className="flex-1 rounded-full border border-border bg-white px-3 py-1.5 text-xs font-semibold text-text-default enabled:hover:opacity-85"
        >
          상세보기
        </button>
        <button
          type="button"
          onClick={onGoToInspectionResult}
          className="flex-1 rounded-full bg-primary px-3 py-1.5 text-xs font-semibold text-surface enabled:hover:opacity-85"
        >
          결과접수
        </button>
      </div>
    </div>
  );
}
