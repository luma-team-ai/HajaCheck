import type { MyPlanLimits, MyPlanUsage } from '../types';
import { UsageBar } from './UsageBar';

type Props = {
  limits: MyPlanLimits;
  usage: MyPlanUsage;
};

// 사용량 3종 — 시설물/월 분석/점검자 좌석(handoff 구현 범위 §3). Figma 3열 균등 배치, 좁은 화면은 세로 스택.
export function UsageSection({ limits, usage }: Props) {
  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <h3 className="text-xl font-semibold text-heading">사용량</h3>
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-3">
        <UsageBar label="시설물" used={usage.facilityCount} limit={limits.maxFacilities} unit="개" />
        <UsageBar
          label="월 분석"
          used={usage.analyzedImageCount}
          limit={limits.maxMonthlyAnalyses}
          unit="장"
        />
        <UsageBar label="점검자 좌석" used={usage.seatCount} limit={limits.maxSeats} unit="명" />
      </div>
    </section>
  );
}
