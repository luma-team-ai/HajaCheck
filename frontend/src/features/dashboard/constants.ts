// 대시보드 화면 전용 경로 상수 — 사이드바 네비게이션 구성은 공통 컴포넌트
// shared/components/SideNavBar(DEFAULT_ITEMS)가 담당(HAJA-186, #217 앱 셸 연결)

// 스토리보드 DASH-01 action 이동 경로 (URL 하드코딩 방지 — 단일 지점 관리)
// A1: 새 점검 시작 → 점검 회차 생성(INSP-01, FR-2-01 업로드)
export const INSPECTION_NEW_PATH = '/inspections/new';
// A2: 검수하기 → 처리 대기 하자의 상세(하자 상세, /defects/:id)로 이동 (Figma node 1-1588 동기화)
export const defectDetailPath = (defectId: number): string => `/defects/${defectId}`;

// 사이드바 "AI 주간 브리핑 카드" 메뉴는 별도 화면이 아니라 이 페이지 안의 AiBriefingCard 인라인 위젯을
// 가리킨다(#478, #472와 동일한 라우트-메뉴 불일치 유형). 새 화면 대신 이 경로로 진입하면 위젯 위치로
// 스크롤한다 — 값은 router.tsx의 라우트 등록·DashboardPage.tsx의 스크롤 판정 양쪽에서 동일하게 참조.
export const AI_WEEKLY_BRIEFING_PATH = '/dashboard/ai-weekly-briefing';
export const AI_WEEKLY_BRIEFING_ANCHOR_ID = 'ai-weekly-briefing-card';
