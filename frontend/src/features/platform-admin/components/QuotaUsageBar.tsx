import {
  PLAN_QUOTA_EMPTY_CELL,
  QUOTA_BAR_NORMAL_CLASS,
  QUOTA_BAR_WARNING_CLASS,
  QUOTA_TEXT_NORMAL_CLASS,
  QUOTA_TEXT_WARNING_CLASS,
  QUOTA_WARNING_PERCENT,
} from '../planQuota.constants';
import { formatQuotaUsed, quotaPercent } from '../utils/quotaUsage';

interface QuotaUsageBarProps {
  used: number;
  limit: number | null;
  /** 접근성 라벨 접두(예: 계정명) — "{name} 쿼터 사용률"로 조합 */
  label: string;
}

// 월 분석 쿼터 사용량 바 — Figma node-id 1197-3519. 사용 장수(좌)·사용률%(우) 한 줄 + 진행 바.
// 사용률 90% 이상이면 주황으로 강조. 기존 얇은(h-1) 바 + 검정 채움이 옅은 트랙 위에서 잘 안 보인다는
// 지적(사용자 지시)에 따라 바 두께를 키우고(h-1 → h-2) 채움색을 선명한 파랑/주황으로 바꿨다. 트랙
// 배경도 bg-surface-muted(#fafafa, 거의 흰색이라 채울 값이 없을 때 바 자체가 안 보였다)에서 짙은
// 회색(#d4d4d8)으로 바꿔, 사용량이 0이거나 데이터가 없어도 바 윤곽이 항상 보이게 한다.
export function QuotaUsageBar({ used, limit, label }: QuotaUsageBarProps) {
  const percent = quotaPercent(used, limit);
  const isWarning = percent !== null && percent >= QUOTA_WARNING_PERCENT;
  const barClass = isWarning ? QUOTA_BAR_WARNING_CLASS : QUOTA_BAR_NORMAL_CLASS;
  const percentTextClass = isWarning ? QUOTA_TEXT_WARNING_CLASS : QUOTA_TEXT_NORMAL_CLASS;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-[13px]">
        <span className="font-medium text-text-default">{formatQuotaUsed(used)}</span>
        <span className={`font-semibold ${percentTextClass}`}>
          {percent === null ? PLAN_QUOTA_EMPTY_CELL : `${percent}%`}
        </span>
      </div>
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-[#d4d4d8]"
        role="progressbar"
        aria-label={`${label} 쿼터 사용률`}
        aria-valuenow={percent ?? undefined}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        {percent !== null && (
          <div className={`h-full rounded-full ${barClass}`} style={{ width: `${percent}%` }} />
        )}
      </div>
    </div>
  );
}
