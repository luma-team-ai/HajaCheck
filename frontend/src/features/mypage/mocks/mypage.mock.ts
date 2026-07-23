import type { MyPlan, SeatMember, SeatsInfo } from '../types';

// 백엔드 #211(HAJA-177) 미배포 대비 예제 데이터(HAJA-185) — Figma "My Page - My Plan Management" 시안 기준
export const mockMyPlan: MyPlan = {
  plan: { name: 'STANDARD', priceMonthly: 99000, status: 'ACTIVE' },
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
