// 대시보드 화면 전용 경로 상수 — 사이드바 네비게이션 구성은 공통 컴포넌트
// shared/components/SideNavBar(DEFAULT_ITEMS)가 담당(HAJA-186, #217 앱 셸 연결)

// 스토리보드 DASH-01 action 이동 경로 (URL 하드코딩 방지 — 단일 지점 관리)
// A1: 새 점검 시작 → 점검 회차 생성(INSP-01, FR-2-01 업로드). router.tsx에는 '/inspections/create'로만
// 등록돼 있고 '/inspections/new'는 등록된 적이 없어 실제로는 미매치 경로였다(#543에서 발견·수정).
export const INSPECTION_NEW_PATH = '/inspections/create';
// A2: 검수하기 → 처리 대기 하자가 속한 점검의 하자 목록으로 이동한다. 점검 상세는 카드형(점검 단위)
// 목록이라 개별 하자 상세는 카드 클릭 시 뜨는 모달로만 진입한다(HAJA-393/394 §화면 구조 ③) — #1117에서
// 하자 단건 상세 화면이 삭제되며 "검수하기"가 목록까지만 이동하고 모달을 자동으로 열지 못하게 된 회귀를
// defectId 쿼리파라미터로 딥링크해 해결한다. InspectionDefectsPage가 이 값을 읽어 모달을 자동으로 연다.
export const inspectionDefectsPath = (inspectionId: number, defectId?: number): string =>
  defectId != null
    ? `/inspections/${inspectionId}/defects?defectId=${defectId}`
    : `/inspections/${inspectionId}/defects`;

// AI 주간 브리핑은 별도 화면이 아니라 대시보드 안의 AiBriefingCard 인라인 위젯이다. 이 경로로 진입하면
// 새 화면을 띄우는 대신 위젯 위치로 스크롤한다(#478, #472와 동일한 라우트-메뉴 불일치 유형).
// 값은 router.tsx의 라우트 등록·DashboardPage.tsx의 스크롤 판정 양쪽에서 동일하게 참조한다.
//
// ⚠️ #1538: **사이드바 메뉴 항목은 존재하지 않는다.** 서버 menus 테이블에도 없고 SideNavBar 폴백에서도
// 뺐다(위젯이 이미 대시보드에 노출되므로 메뉴를 따로 두지 않기로 #1522에서 결정). 따라서 이 경로의
// 진입점은 **URL 직접 입력·외부 링크뿐**이다 — "사이드바 href와 맞춘다"는 근거로 이 값을 바꾸지 말 것.
export const AI_WEEKLY_BRIEFING_PATH = '/dashboard/ai-weekly-briefing';
export const AI_WEEKLY_BRIEFING_ANCHOR_ID = 'ai-weekly-briefing-card';
