import type { CounselTypeKey, MonthlyTrendDirection, ServiceStatsPlan } from './stats.types';

export const SERVICE_STATS_EMPTY_CELL = '-';

export const PLAN_DISTRIBUTION_LABEL: Record<ServiceStatsPlan, string> = {
  FREE: 'Free',
  STANDARD: 'Standard',
  ENTERPRISE: 'Enterprise',
};

/** DistributionBar 세그먼트 색상 — Free(밝은 회색) → Standard(중간 회색) → Enterprise(검정), Figma 시안 그대로. */
export const PLAN_DISTRIBUTION_COLOR: Record<ServiceStatsPlan, string> = {
  FREE: '#d4d4d8',
  STANDARD: '#71717a',
  ENTERPRISE: '#18181b',
};

export const COUNSEL_TYPE_LABEL: Record<CounselTypeKey, string> = {
  USAGE: '서비스 이용 방법',
  ANALYSIS_RESULT: '분석 결과 문의',
  BILLING_ETC: '요금·기타',
};

export const MONTHLY_TREND_ARROW: Record<MonthlyTrendDirection, string> = {
  UP: '↗',
  DOWN: '↘',
  FLAT: '→',
};

export const MONTHLY_TREND_LABEL: Record<MonthlyTrendDirection, string> = {
  UP: '상승',
  DOWN: '하락',
  FLAT: '유지',
};
