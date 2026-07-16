import { GRADE_COLOR } from '../constants';
import type { FacilityLocation } from '../types';

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
  // 등급별 안전 등급 텍스트 매핑
  const getStatusText = (grade: string) => {
    switch (grade) {
      case 'E':
        return '정밀안전진단 요망';
      case 'D':
        return '긴급 정비 필요';
      case 'C':
        return '추적 관찰 필요';
      case 'B':
        return '주기적 점검 요망';
      default:
        return '상태 양호';
    }
  };

  const badgeBgColor = GRADE_COLOR[facility.highestGrade] ?? '#9CA3AF';

  return (
    <div className="w-[290px] rounded-[24px] border border-[#d4d4d8] bg-white p-4 shadow-[0px_8px_24px_rgba(0,0,0,0.08)]">
      <div className="flex items-start gap-3">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full bg-neutral-100">
          {facility.thumbnailUrl ? (
            <img
              src={facility.thumbnailUrl}
              alt={facility.name}
              className="h-full w-full object-cover"
            />
          ) : (
            <span className="text-[10px] text-zinc-400">사진 없음</span>
          )}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <div className="truncate font-semibold text-[#3f3f46] text-[15px] leading-[22.5px]">
              {facility.name}
            </div>
            <div
              className="flex h-6 min-w-[22px] items-center justify-center rounded-md px-1.5"
              style={{ backgroundColor: badgeBgColor }}
            >
              <span className="font-semibold text-white text-[11px] leading-[16.5px]">
                {facility.highestGrade}
              </span>
            </div>
          </div>
          <div className="mt-1 font-normal text-[#71717a] text-[12px] leading-[18px]">
            {getStatusText(facility.highestGrade)}
          </div>
        </div>
      </div>
      <div className="mt-4 flex items-center gap-3">
        <button
          type="button"
          onClick={onViewDetail}
          className="flex h-10 w-[92px] items-center justify-center rounded-[999px] border border-[#e4e4e7] bg-[#f7f7f7] font-medium text-[#52525b] text-[14px] leading-[21px] transition hover:opacity-85"
        >
          상세 보기
        </button>
        <button
          type="button"
          onClick={onGoToInspectionResult}
          className="flex h-10 w-[92px] items-center justify-center rounded-[999px] bg-[#18181b] font-medium text-white text-[14px] leading-[21px] transition hover:opacity-85"
        >
          결과 검수
        </button>
      </div>
    </div>
  );
}
