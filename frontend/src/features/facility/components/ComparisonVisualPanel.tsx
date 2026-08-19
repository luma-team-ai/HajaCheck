import type { InspectionCycleOption } from '../types';

type Props = {
  beforeCycle: InspectionCycleOption;
  afterCycle: InspectionCycleOption;
  beforeImageUrl: string | null;
  afterImageUrl: string | null;
};

// 시각적 비교 — 회차 이미지 두 장을 좌우로 나란히 배치(정적 레이아웃). 드래그 가능한 슬라이더는
// 범위 밖(#489 스펙) — 과도한 엔지니어링을 피하고 좌/우 분할 + 라벨만 구현한다.
//
// 백엔드(HAJA-612/#1346)는 각 회차의 "첫 사진"(회차별 대표 사진)을 beforeImageUrl/afterImageUrl로
// 내려준다. 사진이 한 장도 없는 회차는 null이며, 시설물 카드(FacilityCard)의 "사진 없음" 플레이스홀더와
// 동일 패턴으로 깨진 <img> 대신 안내 문구를 보여준다.
function ComparisonImage({ url, label }: { url: string | null; label: string }) {
  if (!url) {
    return (
      <div className="flex aspect-[4/3] w-full items-center justify-center bg-surface-muted text-xs text-text-muted">
        사진 없음
      </div>
    );
  }
  return <img src={url} alt={label} className="aspect-[4/3] w-full object-cover" />;
}

export function ComparisonVisualPanel({ beforeCycle, afterCycle, beforeImageUrl, afterImageUrl }: Props) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="m-0 text-base font-bold text-heading">시각적 비교</h2>
        <span className="text-xs font-medium text-text-muted">⊙ 회차별 대표 사진</span>
      </div>
      <div className="grid grid-cols-2 overflow-hidden rounded-2xl border border-border">
        <div className="relative">
          <ComparisonImage url={beforeImageUrl} label={`${beforeCycle.cycle}회차 촬영 이미지`} />
          {/* bg-black/60이 아니라 명시적 rgba: Tailwind v4는 /NN 투명도를 color-mix(in oklab, ...)로
              컴파일한다. exportComparisonReportAsPdf가 html2canvas-pro로 교체돼(#1692, 2026-08-19)
              oklch/oklab 자체는 더 이상 캡처 실패 원인이 아니지만, 이 배지는 그때 도입한 명시적
              rgba를 그대로 유지한다(2026-08-05 원 발견 당시 배경 — 굳이 되돌릴 이유 없음). */}
          <span className="absolute left-3 top-3 rounded-full bg-[rgba(0,0,0,0.6)] px-2.5 py-1 text-xs font-bold text-white">
            {beforeCycle.cycle}회차 (이전)
          </span>
        </div>
        <div className="relative">
          <ComparisonImage url={afterImageUrl} label={`${afterCycle.cycle}회차 촬영 이미지`} />
          <span className="absolute right-3 top-3 rounded-full bg-[rgba(0,0,0,0.6)] px-2.5 py-1 text-xs font-bold text-white">
            {afterCycle.cycle}회차 (현재)
          </span>
        </div>
      </div>
    </div>
  );
}