import type { NotificationFilter } from '../../shared/components/NotificationDropdown';
import type { NotificationCategory, NotificationTypeCode } from './types';

interface NotificationTypeMeta {
  category: NotificationCategory;
  title: string;
  actionLabel: string;
}

// BE payload에는 title/actionLabel 필드가 없어(types.ts 주석 참고) 타입별 고정 라벨을 쓴다.
// 문구는 Anima 정답지(anima_알림센터_20260721.md 파트3 initialNotifications)의 카테고리별 대표 문구 기준.
export const NOTIFICATION_TYPE_META: Record<NotificationTypeCode, NotificationTypeMeta> = {
  ANALYSIS_DONE: { category: '분석', title: 'AI 분석 완료', actionLabel: '결과 보기' },
  REVIEW_PENDING: { category: '검수', title: '검수 대기 알림', actionLabel: '검수하기' },
  COUNSEL_REPLIED: { category: '상담', title: '상담 답변이 도착했어요', actionLabel: '대화 열기' },
  INSPECTION_DUE: { category: '점검일', title: '점검일 도래', actionLabel: '점검 시작' },
};

export const NOTIFICATION_ALL_FILTER_KEY = 'all';

// NotificationDropdown filters prop — Anima categories(전체·분석·검수·상담·점검일)와 1:1
export const NOTIFICATION_FILTERS: NotificationFilter[] = [
  { key: NOTIFICATION_ALL_FILTER_KEY, label: '전체' },
  { key: '분석', label: '분석' },
  { key: '검수', label: '검수' },
  { key: '상담', label: '상담' },
  { key: '점검일', label: '점검일' },
];
