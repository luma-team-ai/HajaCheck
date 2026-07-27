import type { MyPlan, PaymentHistoryItem, SeatMember, SeatsInfo } from '../types';

// 백엔드 #211(HAJA-177) 미배포 대비 예제 데이터(HAJA-185) — Figma "My Page - My Plan Management" 시안 기준.
// priceMonthly=29000은 PRD 확정 STANDARD가(platform-admin planPolicyApi.handlers.ts 시드값과 동일 기준,
// #712 리디자인 시 99000 → 정정). nextBillingDate/businessVerified는 BE #711/PR#714에서 확정된 신규 필드.
export const mockMyPlan: MyPlan = {
  plan: {
    name: 'STANDARD',
    priceMonthly: 29000,
    status: 'ACTIVE',
    nextBillingDate: '2026-08-01',
    businessVerified: true,
  },
  limits: { maxFacilities: 10, maxMonthlyAnalyses: 1000, maxSeats: 3 },
  usage: { facilityCount: 4, analyzedImageCount: 786, seatCount: 2, period: '2026-07-01' },
};

// PR머신 시크릿 가드 오탐 방지(#213) — 실존 팀원명·회사 도메인 이메일 대신
// 누가 봐도 예시 데이터임이 드러나는 값(RFC 2606 example.com + 통상적 한국어 예시 인명)만 사용한다.
export const mockSeats: SeatsInfo = {
  used: 2,
  limit: 3,
  members: [
    {
      userId: 1,
      name: '홍길동',
      email: 'inspector@example.com',
      role: 'INSPECTOR',
      status: 'ACTIVE',
    },
    {
      userId: 2,
      name: '김철수',
      email: 'admin@example.com',
      role: 'ADMIN',
      status: 'ACTIVE',
    },
  ],
};

// '초대됨' 상태 데모 전용(#659, HAJA-361) — 실 UserStatus엔 없는 프론트 전용 값(types.ts 참고).
// mockSeats.members에는 넣지 않는다(넣으면 /mypage/plan 등 SeatsSection을 쓰는 기존 화면까지
// 함께 바뀐다) — MyProfilePage에서만 SeatsSection의 extraDemoMembers로 별도 주입한다.
export const mockInvitedSeatMember: SeatMember = {
  userId: 999,
  name: '박초대',
  email: 'invited@example.com',
  role: 'INSPECTOR',
  status: 'INVITED',
};

// 결제 내역 실연동(#864, 토스페이먼츠 #989/HAJA-490) 예제 데이터 — 백엔드(#988) 미배포 시 usePayments
// 네이티브 폴백용(fetchWithFallback). MSW 핸들러(mypageApi.handlers.ts)는 이 값을 초기 상태로 삼아
// 결제 승인 성공 시 새 레코드를 앞에 추가한다.
export const mockPayments: PaymentHistoryItem[] = [
  {
    id: 1,
    orderId: 'order_mock_20260701',
    planName: 'STANDARD',
    amount: 29000,
    status: 'PAID',
    method: '카드',
    approvedAt: '2026-07-01T09:12:00',
    receiptUrl: 'https://mock.tosspayments.com/receipt/order_mock_20260701',
  },
];
