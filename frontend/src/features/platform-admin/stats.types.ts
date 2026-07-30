// 플랫폼 관리자 > 서비스 통계(#634) 도메인 타입. Figma node-id 177-3515.
// 백엔드 /api/platform-admin/stats 계약 확정 전까지 MSW 목데이터로만 채운다(BE 이슈 별도).

export type ServiceStatsPlan = 'FREE' | 'STANDARD' | 'ENTERPRISE';

export type CounselTypeKey = 'USAGE' | 'ANALYSIS_RESULT' | 'BILLING_ETC';

export type MonthlyTrendDirection = 'UP' | 'DOWN' | 'FLAT';

export interface ServiceStatsKpi {
  totalSubscribers: number;
  /** 지난달 대비 증감(명) — 절대값 */
  totalSubscribersDelta: number;
  newSubscribersThisMonth: number;
  /** 지난달 대비 증감률(%) */
  newSubscribersChangePercent: number;
  /** 이번 기간 누적 분석 요청 장수 */
  analysisRequests: number;
  /** 이번 기간 누적 상담 건수 */
  counselCount: number;
}

export interface SubscriberTrendPoint {
  month: string;
  subscribers: number;
}

export interface AnalysisRequestTrendPoint {
  month: string;
  requests: number;
}

export interface PlanDistributionItem {
  plan: ServiceStatsPlan;
  percent: number;
}

export interface CounselTypeDistributionItem {
  type: CounselTypeKey;
  count: number;
}

export interface MonthlySummaryRow {
  month: string;
  newSubscribers: number;
  analysisCount: number;
  counselCount: number;
  /** 상위 플랜 업그레이드 전환 건수 (Free→Standard, Free→Enterprise, Standard→Enterprise 합산) */
  upgradeConversions: number;
  trend: MonthlyTrendDirection;
}

export interface ServiceStatsResponse {
  kpi: ServiceStatsKpi;
  subscriberTrend: SubscriberTrendPoint[];
  analysisRequestTrend: AnalysisRequestTrendPoint[];
  planDistribution: PlanDistributionItem[];
  counselTypeDistribution: CounselTypeDistributionItem[];
  monthlySummary: MonthlySummaryRow[];
}
