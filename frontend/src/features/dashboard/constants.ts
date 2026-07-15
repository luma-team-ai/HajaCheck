// 대시보드 화면 전용 경로 상수 — 사이드바 네비게이션 구성(NAV_ITEMS)은 여러 feature가 공유하는
// 앱 셸 데이터라 shared/constants/navItems.ts로 이관됨(HAJA-185, 마이페이지 활성화 시)

// 스토리보드 DASH-01 action 이동 경로 (URL 하드코딩 방지 — 단일 지점 관리)
// A1: 새 점검 시작 → 점검 회차 생성(INSP-01, FR-2-01 업로드)
export const INSPECTION_NEW_PATH = '/inspections/new';
// A2: 검수하기 → 분석 결과 뷰어에서 수동 검수(INSP-04, FR-4-02)
export const inspectionReviewPath = (inspectionId: number): string =>
  `/inspections/${inspectionId}/viewer`;
