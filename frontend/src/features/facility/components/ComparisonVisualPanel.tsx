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
// 실 백엔드(HAJA-531/#1112)는 실 데이터 근거가 없어 이미지 url을 생략(null)한다 — 시설물 카드
// (FacilityCard)의 "사진 없음" 플레이스홀더와 동일 패턴으로 깨진 <img> 대신 안내 문구를 보여준다.
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
        <span className="text-xs font-medium text-text-muted">⊙ 동일 촬영 지점 정렬됨</span>
      </div>
      <div className="grid grid-cols-2 overflow-hidden rounded-2xl border border-border">
        <div className="relative">
          <ComparisonImage url={beforeImageUrl} label={`${beforeCycle.cycle}회차 촬영 이미지`} />
          <span className="absolute left-3 top-3 rounded-full bg-black/60 px-2.5 py-1 text-xs font-bold text-white">
            {beforeCycle.cycle}회차 (이전)
          </span>
        </div>
        <div className="relative">
          <ComparisonImage url={afterImageUrl} label={`${afterCycle.cycle}회차 촬영 이미지`} />
          <span className="absolute right-3 top-3 rounded-full bg-black/60 px-2.5 py-1 text-xs font-bold text-white">
            {afterCycle.cycle}회차 (현재)
          </span>
        </div>
      </div>
    </div>
  );
}