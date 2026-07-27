import type { SideNavItem } from '../../shared/components/SideNavBar';
import supportIcon from '../../assets/brand/sidenav-support.svg';
import type { CounselTicketStatus, CounselTicketStatusFilter } from './types';

// "새 상담 시작" 버튼 이동 경로 — SideNavBar DEFAULT_ITEMS(shared/components/SideNavBar/SideNavBar.tsx)의
// 고객지원 하위메뉴 "상담 챗봇" href('/support/chat-bot')와 동일하게 맞춘다. 원 작업 브리프는
// '/support/chatbot'(하이픈 없음)으로 적혀 있었으나 실제 사이드바 href는 '/support/chat-bot'이라
// 다른 경로를 새로 만들면 메뉴-라우트 불일치(#478 유형 회귀)가 생긴다 — 기존 값을 단일 소스로 따른다.
export const CHAT_BOT_PATH = '/support/chat-bot';

// 목록 필터 탭 — 브리프 디자인(markup)이 "전체/진행중/종료" 3탭만 정의하고 있어, 백엔드가 지원하는
// 4개 상태(WAITING/IN_PROGRESS/RESOLVED/OFFLINE_LEFT) 중 "진행중"은 IN_PROGRESS, "종료"는 RESOLVED로
// 매핑한다(WAITING·OFFLINE_LEFT 세부 탭은 이번 스코프 밖 — 필요해지면 탭을 늘리고 이 배열만 확장).
export const STATUS_FILTER_TABS: { value: CounselTicketStatusFilter; label: string }[] = [
  { value: 'ALL', label: '전체' },
  { value: 'IN_PROGRESS', label: '진행중' },
  { value: 'RESOLVED', label: '종료' },
];

export const DEFAULT_PAGE_SIZE = 20;

// 카드 뱃지 표시용 상태 그룹 — WAITING도 대기 중이라는 의미에서 "진행중" 계열로 묶는다(마찬가지로
// OFFLINE_LEFT는 상담이 끝난 상태라 "종료" 계열). 필터 탭과 달리 뱃지는 개별 티켓 하나의 실제 status를
// 그대로 반영해야 하므로 4종 상태 → 2종 배지(진행중/종료) 매핑을 별도로 둔다.
export const STATUS_BADGE: Record<CounselTicketStatus, { label: string; dotClassName: string; textClassName: string }> = {
  WAITING: { label: '진행중', dotClassName: 'bg-indigo-600', textClassName: 'text-indigo-600' },
  IN_PROGRESS: { label: '진행중', dotClassName: 'bg-indigo-600', textClassName: 'text-indigo-600' },
  RESOLVED: { label: '종료', dotClassName: 'bg-zinc-500', textClassName: 'text-zinc-500' },
  OFFLINE_LEFT: { label: '종료', dotClassName: 'bg-zinc-500', textClassName: 'text-zinc-500' },
};

// 종료된 티켓 여부 — "상담이 종료되었어요" 요약 카드 노출 조건에 사용
export function isTicketEnded(status: CounselTicketStatus): boolean {
  return status === 'RESOLVED' || status === 'OFFLINE_LEFT';
}

// 상담원 콘솔 사이드바(#1001, HAJA-495) — 대기열 화면 하나뿐인 최소 구성. 채팅 화면(/counsel-console/
// tickets/:id)은 대기열에서 클레임한 티켓을 클릭해 진입하는 동적 경로라 메뉴 항목으로 두지 않는다
// (다른 :id 상세 라우트들과 동일한 관례 — router.tsx /defects/:id 참고). 피그마 디자인이 아직 없어
// 기존 플랫폼 관리자 콘솔(PLATFORM_ADMIN_NAV_ITEM) 아이콘 재사용 관례를 그대로 따름(신규 아이콘 없음).
export const COUNSELOR_NAV_ITEM: SideNavItem = {
  label: '상담 대기열',
  href: '/counsel-console/queue',
  icon: supportIcon,
};
