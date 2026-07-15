import type { MyPlan, SeatsInfo } from '../types';

// 백엔드 #211(HAJA-177) 미배포 대비 예제 데이터(HAJA-185) — Figma "My Page - My Plan Management" 시안 기준
export const mockMyPlan: MyPlan = {
  plan: { name: 'STANDARD', priceMonthly: 99000, status: 'ACTIVE' },
  limits: { maxFacilities: 10, maxMonthlyAnalyses: 1000, maxSeats: 3 },
  usage: { facilityCount: 4, analyzedImageCount: 786, seatCount: 2, period: '2026-07-01' },
};

export const mockSeats: SeatsInfo = {
  used: 2,
  limit: 3,
  members: [
    {
      userId: 1,
      name: '김지수',
      email: 'jisoo.kim@hajacheck.com',
      role: 'INSPECTOR',
      status: 'ACTIVE',
    },
    {
      userId: 2,
      name: '오영석',
      email: 'youngseok.oh@hajacheck.com',
      role: 'ADMIN',
      status: 'ACTIVE',
    },
  ],
};
