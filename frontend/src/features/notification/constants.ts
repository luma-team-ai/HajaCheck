import type { NotificationFilter } from '../../shared/components/NotificationDropdown';
import type { NotificationCategory, NotificationTypeCode } from './types';

interface NotificationTypeMeta {
  category: NotificationCategory;
  title: string;
  actionLabel?: string;
}

// BE payload에는 title/actionLabel 필드가 없어(types.ts 주석 참고) 타입별 고정 라벨을 쓴다.
// 문구는 Anima 정답지(anima_알림센터_20260721.md 파트3 initialNotifications)의 카테고리별 대표 문구 기준.
export const NOTIFICATION_TYPE_META: Record<NotificationTypeCode, NotificationTypeMeta> = {
  ANALYSIS_DONE: { category: '분석', title: 'AI 분석 완료', actionLabel: '결과 보기' },
  REVIEW_PENDING: { category: '검수', title: '검수 대기 알림', actionLabel: '검수하기' },
  COUNSEL_REPLIED: { category: '상담', title: '상담 답변이 도착했어요', actionLabel: '대화 열기' },
  INSPECTION_DUE: { category: '점검일', title: '점검일 도래', actionLabel: '점검 시작' },
};

// BE NotificationType이 FE 배포보다 먼저 확장되면(새 enum 값) 위 맵에 없는 type 문자열이 내려올 수
// 있다 — 그때 NOTIFICATION_TYPE_META[raw.type]이 undefined가 되어 렌더링이 크래시했다(PR머신 P1,
// 레포 전체에 ErrorBoundary가 0건이라 AppShellRoute 전체가 죽을 수 있었음). 항목을 숨기면 unreadCount와
// 어긋나므로, 폴백 메타로 "기타" 카테고리에 표시한다(액션 버튼은 의미를 알 수 없어 노출하지 않음).
const NOTIFICATION_UNKNOWN_TYPE_META: NotificationTypeMeta = {
  category: '기타',
  title: '새 알림',
};

export function getNotificationTypeMeta(type: string): NotificationTypeMeta {
  return (NOTIFICATION_TYPE_META as Record<string, NotificationTypeMeta>)[type] ?? NOTIFICATION_UNKNOWN_TYPE_META;
}

export const NOTIFICATION_ALL_FILTER_KEY = 'all';

// NotificationDropdown filters prop — Anima categories(전체·분석·검수·상담·점검일)와 1:1
export const NOTIFICATION_FILTERS: NotificationFilter[] = [
  { key: NOTIFICATION_ALL_FILTER_KEY, label: '전체' },
  { key: '분석', label: '분석' },
  { key: '검수', label: '검수' },
  { key: '상담', label: '상담' },
  { key: '점검일', label: '점검일' },
];
