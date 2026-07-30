import type { MyPlanLimits, MyPlanUsage } from '../types';
import { UsageBar } from './UsageBar';

type Props = {
  limits: MyPlanLimits;
  usage: MyPlanUsage;
};

// 사용량 3종 — 시설물/월 분석/점검자 좌석(handoff 구현 범위 §3). Figma 리디자인(node 1463-2786,
// #712)은 3열 균등 배치 대신 세로 스택 하나로 통일한다(이전 sm:grid-cols-3 폐기). 월 분석만
// 매월 초기화되는 한도라 resetMonthly로 경고 배지에 안내 문구를 덧붙인다.
export function UsageSection({ limits, usage }: Props) {
  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <h3 className="text-xl font-semibold text-heading">사용량</h3>
      <div className="flex flex-col gap-6">
        <UsageBar label="시설물" used={usage.facilityCount} limit={limits.maxFacilities} unit="개" />
        <UsageBar
          label="월 분석"
          used={usage.analyzedImageCount}
          limit={limits.maxMonthlyAnalyses}
          unit="장"
          resetMonthly
        />
        <UsageBar label="점검자 좌석" used={usage.seatCount} limit={limits.maxSeats} unit="명" />
      </div>
    </section>
  );
}
