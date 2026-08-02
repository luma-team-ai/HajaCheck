import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { PeriodFilterValue } from '../components/PeriodFilterSelect';
import type {
  InspectionHistoryRow,
  MyInspectionsSummary,
  MyPlan,
  MyReportCard,
  PasswordChangeRequest,
  PaymentHistoryItem,
  PlanName,
  PlanOrder,
  SeatsInfo,
} from '../types';

export const mypageApi = {
  getPlan: () => api.get<MyPlan>('/me/plan'),
  getSeats: () => api.get<SeatsInfo>('/me/seats'),
  // 토스페이먼츠 결제창 연동(#989, HAJA-490) — 기존 모의 결제(POST /me/plan/checkout, #712)를
  // 대체한다. 주문 생성(orders) → 결제창(requestPayment) → 승인(payments/confirm) 3단계 흐름.
  // STANDARD/ENTERPRISE만 대상, 금액은 응답 amount가 source of truth(클라이언트 하드코딩 금지).
  createPlanOrder: (planName: PlanName) => api.post<PlanOrder>('/me/plan/orders', { planName }),
  // 결제 승인 — successUrl 리다이렉트 쿼리(paymentKey/orderId/amount)를 그대로 전달한다. 백엔드가
  // 멱등 처리하므로 새로고침·중복 진입 시에도 동일 결과를 200으로 반환한다(handoff §3).
  confirmPayment: (payload: { paymentKey: string; orderId: string; amount: number }) =>
    api.post<MyPlan>('/me/payments/confirm', payload),
  // 결제 내역 실연동(#864) — 최신순, 이력 0건이면 빈 배열.
  getPayments: () => api.get<{ payments: PaymentHistoryItem[] }>('/me/payments'),
  // 내 점검 이력 / 보고서 (HAJA-366/#668, BE 연동 #844/HAJA-442) — GET /api/me/inspections/summary,
  // /api/me/inspections, /api/me/reports 3종. period는 PeriodFilterSelect 값(1M/3M/6M/1Y/ALL)을
  // 쿼리 파라미터로 그대로 전달한다.
  getInspectionsSummary: () => api.get<MyInspectionsSummary>('/me/inspections/summary'),
  getInspections: (params: { page: number; size: number; period: PeriodFilterValue }) =>
    api.get<PageResponse<InspectionHistoryRow>>('/me/inspections', { params }),
  getReports: (period: PeriodFilterValue) =>
    api.get<MyReportCard[]>('/me/reports', { params: { period } }),
  // 비밀번호 변경(#1316, HAJA-602) — 계정 자체 엔드포인트라 다른 /me/* 마이페이지 API와 달리
  // authApi.getMe와 같은 /users/me 경로군을 쓴다(handoff §계약). 401은 "세션 만료"가 아니라
  // "현재 비밀번호 불일치"를 뜻하므로 axios 전역 401 하드 리다이렉트(shared/api/axios.ts)를
  // 우회해야 한다 — skipAuthRedirect:true로 getMe와 동일하게 처리한다.
  changePassword: (body: PasswordChangeRequest) =>
    api.patch<null>('/users/me/password', body, { skipAuthRedirect: true }),
};
