import type { MyPlanLimits, MyPlanUsage } from '../types';
import { UsageBar } from './UsageBar';

type Props = {
  limits: MyPlanLimits;
  usage: MyPlanUsage;
};

// 사용량 3종 — 시설물/월 분석/검사자 좌석(handoff 구현 범위 §3)
export function UsageSection({ limits, usage }: Props) {
  return (
    <section className="dashboard-card mypage-usage-card">
      <h3 className="dashboard-card-title">사용량</h3>
      <UsageBar label="시설물" used={usage.facilityCount} limit={limits.maxFacilities} unit="개" />
      <UsageBar
        label="월 분석"
        used={usage.analyzedImageCount}
        limit={limits.maxMonthlyAnalyses}
        unit="장"
      />
      <UsageBar label="검사자 좌석" used={usage.seatCount} limit={limits.maxSeats} unit="명" />
    </section>
  );
}
