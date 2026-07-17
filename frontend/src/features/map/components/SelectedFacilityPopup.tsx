import { FALLBACK_GRADE_COLOR, GRADE_COLOR } from '../constants';
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

  const badgeBgColor = GRADE_COLOR[facility.highestGrade] ?? FALLBACK_GRADE_COLOR;

  return (
    // border-[#d4d4d8]: Figma 팝업 전용 보더 색상 — styles/tokens.css의 --color-border(#e4e4e7)와
    // 값이 달라 시맨틱 토큰으로 치환하지 않고 유지(P3, 2026-07-16 검토)
    // 글라스(반투명+블러) 효과 — Figma 시안 대조 결과 누락돼 있던 부분 추가(2026-07-17):
    // bg-white/70 + backdrop-blur로 지도 위에 떠 있는 느낌을 재현. Safari 대응 -webkit- 접두사 병기.
    <div className="w-[290px] rounded-[24px] border border-[#d4d4d8] bg-white/70 p-4 shadow-[0px_8px_24px_rgba(0,0,0,0.08)] backdrop-blur-[10px] backdrop-brightness-[100%] [-webkit-backdrop-filter:blur(10px)_brightness(100%)] z-10">
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
            {/* text-[#3f3f46]: Figma 팝업 전용 제목 색상 — 기존 text-heading(#1d1b20)/text-default(#494551)
                토큰과 값이 달라 임의 대입하지 않고 하드코딩 유지(P3, 2026-07-16 검토) */}
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
          {/* text-[#71717a]: Figma 팝업 보조 텍스트 색상 — text-text-muted(#7a7582)와 근접하지만 동일값이
              아니라 시맨틱 토큰으로 대체 시 시안과 미세하게 어긋남. 하드코딩 유지(P3, 2026-07-16 검토) */}
          <div className="mt-1 font-normal text-[#71717a] text-[12px] leading-[18px]">
            {getStatusText(facility.highestGrade)}
          </div>
        </div>
      </div>
      <div className="mt-4 flex items-center gap-3">
        {/* border-border(#e4e4e7)로 시맨틱 토큰 치환(정확히 일치, P3). bg-[#f7f7f7]/text-[#52525b]는
            매칭되는 토큰이 없어 하드코딩 유지 */}
        <button
          type="button"
          onClick={onViewDetail}
          className="flex h-10 w-[92px] items-center justify-center rounded-[999px] border border-border bg-[#f7f7f7] font-medium text-[#52525b] text-[14px] leading-[21px] transition hover:opacity-85"
        >
          상세 보기
        </button>
        {/* bg-primary(#18181b)로 시맨틱 토큰 치환(정확히 일치, P3) */}
        <button
          type="button"
          onClick={onGoToInspectionResult}
          className="flex h-10 w-[92px] items-center justify-center rounded-[999px] bg-primary font-medium text-white text-[14px] leading-[21px] transition hover:opacity-85"
        >
          결과 검수
        </button>
      </div>
    </div>
  );
}
