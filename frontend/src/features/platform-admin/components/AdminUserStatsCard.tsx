import { EMPTY_CELL, GROWTH_UP_CLASS, STATUS_DOT_CLASS } from '../constants';
import type { AdminUserStats } from '../types';

/** 통계 카드 컨테이너 식별자 — 테스트가 카드 범위로 좁혀 조회할 때 사용 */
export const STATS_CARD_TEST_ID = 'admin-user-stats';

interface AdminUserStatsCardProps {
  /** 미도착(로딩/실패) 시 undefined — 카드는 사라지지 않고 0 또는 "-"로 자리를 지킨다 */
  stats?: AdminUserStats;
  isError?: boolean;
}

// 조회 전(로딩) 기본값 — 카드가 통째로 사라지면 레이아웃이 들썩이고 "미구현"으로 오해된다.
const EMPTY_STATS: AdminUserStats = {
  totalMembers: 0,
  active: 0,
  suspended: 0,
  newThisWeek: 0,
  newThisWeekGrowthRate: 0,
};

// 사용자 관리 상단 통계 카드 — Figma node-id 177-2017. 흰 카드 하나를 세로 구분선으로 4등분.
export function AdminUserStatsCard({ stats, isError = false }: AdminUserStatsCardProps) {
  // 조회 실패 시 0을 그리지 않는다 — "회원 0명"은 사실 주장이라, 집계를 못 가져온 것과 구분돼야 한다.
  const value = stats ?? EMPTY_STATS;

  return (
    // dl/dt/dd 시맨틱은 그대로 둔다 — "활성"·"정지"가 상태 필터 옵션과 텍스트가 겹치는 문제는
    // 테스트가 컨테이너를 잡는 문제이므로 test id로 해결한다(ARIA role을 덮어쓰지 않는다).
    <dl
      data-testid={STATS_CARD_TEST_ID}
      className="grid grid-cols-2 gap-y-6 rounded-[20px] border border-border bg-surface px-8 py-6 sm:grid-cols-4 sm:gap-y-0 sm:divide-x sm:divide-border"
    >
      <StatItem label="전체 회원" value={value.totalMembers} isError={isError} />
      <StatItem
        label="활성"
        value={value.active}
        dotClassName={STATUS_DOT_CLASS.ACTIVE}
        isError={isError}
      />
      <StatItem
        label="정지"
        value={value.suspended}
        dotClassName={STATUS_DOT_CLASS.SUSPENDED}
        isError={isError}
      />
      <StatItem
        label="이번 주 신규"
        labelClassName="text-info"
        value={value.newThisWeek}
        growthRate={value.newThisWeekGrowthRate}
        isError={isError}
      />
    </dl>
  );
}

interface StatItemProps {
  label: string;
  value: number;
  dotClassName?: string;
  labelClassName?: string;
  growthRate?: number;
  isError?: boolean;
}

function StatItem({
  label,
  value,
  dotClassName,
  labelClassName,
  growthRate,
  isError = false,
}: StatItemProps) {
  return (
    <div className="px-0 sm:px-8 sm:first:pl-0 sm:last:pr-0">
      <dt className={`flex items-center gap-1.5 text-[13px] ${labelClassName ?? 'text-text-muted'}`}>
        {dotClassName && (
          <span className={`inline-block h-1.5 w-1.5 rounded-full ${dotClassName}`} aria-hidden />
        )}
        {label}
      </dt>
      <dd className="mt-1 flex items-baseline gap-2">
        <span
          className={`text-[32px] leading-none font-bold ${isError ? 'text-text-muted' : 'text-heading'}`}
        >
          {isError ? EMPTY_CELL : value.toLocaleString('ko-KR')}
        </span>
        {!isError && growthRate !== undefined && growthRate > 0 && (
          <span className={`text-[13px] font-medium ${GROWTH_UP_CLASS}`}>↑{growthRate}%</span>
        )}
      </dd>
    </div>
  );
}
