import type { ServiceStatsResponse } from '../stats.types';

// 캡처 이미지(Figma node-id 177-3515) 수치를 그대로 이식한 목데이터.
// KPI 분석 요청(24,180)·상담 건수(486)는 monthlySummary의 분석 장수·상담 건수 합계와 정확히 일치한다
// (3190+3200+3650+4100+4810+5230=24180, 65+70+76+85+92+98=486) — 화면 간 수치 정합을 목데이터
// 단계에서부터 보장한다.
export const mockServiceStats: ServiceStatsResponse = {
  kpi: {
    totalSubscribers: 1284,
    totalSubscribersDelta: 38,
    newSubscribersThisMonth: 152,
    newSubscribersChangePercent: 12,
    analysisRequests: 24180,
    counselCount: 486,
  },
  subscriberTrend: [
    { month: '1월', subscribers: 1050 },
    { month: '2월', subscribers: 1090 },
    { month: '3월', subscribers: 1140 },
    { month: '4월', subscribers: 1180 },
    { month: '5월', subscribers: 1175 },
    { month: '6월', subscribers: 1284 },
  ],
  analysisRequestTrend: [
    { month: '1월', requests: 3190 },
    { month: '2월', requests: 3200 },
    { month: '3월', requests: 3650 },
    { month: '4월', requests: 4100 },
    { month: '5월', requests: 4810 },
    { month: '6월', requests: 5230 },
  ],
  planDistribution: [
    { plan: 'FREE', percent: 60 },
    { plan: 'STANDARD', percent: 30 },
    { plan: 'ENTERPRISE', percent: 10 },
  ],
  counselTypeDistribution: [
    { type: 'USAGE', count: 312 },
    { type: 'ANALYSIS_RESULT', count: 104 },
    { type: 'BILLING_ETC', count: 70 },
  ],
  monthlySummary: [
    { month: '6월', newSubscribers: 152, analysisCount: 5230, counselCount: 98, freeToStandardConversions: 12, trend: 'UP' },
    { month: '5월', newSubscribers: 140, analysisCount: 4810, counselCount: 92, freeToStandardConversions: 15, trend: 'UP' },
    { month: '4월', newSubscribers: 128, analysisCount: 4100, counselCount: 85, freeToStandardConversions: 9, trend: 'UP' },
    { month: '3월', newSubscribers: 110, analysisCount: 3650, counselCount: 76, freeToStandardConversions: 8, trend: 'UP' },
    { month: '2월', newSubscribers: 95, analysisCount: 3200, counselCount: 70, freeToStandardConversions: 5, trend: 'DOWN' },
    { month: '1월', newSubscribers: 105, analysisCount: 3190, counselCount: 65, freeToStandardConversions: 4, trend: 'FLAT' },
  ],
};
