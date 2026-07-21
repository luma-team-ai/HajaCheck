// 알림 센터(HAJA-38 FR-9, #25/#423) — BE NotificationController/NotificationResponse.java 계약과 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — shared NotificationDropdown이 쓰는
// NotificationItem/NotificationFilter 타입은 그쪽(shared)에서 그대로 가져다 쓰고, 여기서는 BE 원본
// 응답 형태만 정의한다.

// backend NotificationType.java(notification_type enum)와 1:1
export type NotificationTypeCode = 'ANALYSIS_DONE' | 'REVIEW_PENDING' | 'COUNSEL_REPLIED' | 'INSPECTION_DUE';

// NotificationDropdown 필터 카테고리 — Anima 정답지(anima_알림센터_20260721.md 파트3) categories 4종과 1:1
export type NotificationCategory = '분석' | '검수' | '상담' | '점검일';

// GET /api/notifications 응답 1건 — NotificationResponse.java와 1:1.
// payload는 타입별로 구조가 다른 자유 형식 jsonb(현재 BE에 생성 트리거가 없어 스키마 미확정)라
// Record<string, unknown>으로만 받고, description 등 표시용 필드는 있으면 쓰고 없으면 생략한다.
export interface NotificationApiItem {
  id: number;
  type: NotificationTypeCode;
  payload: Record<string, unknown> | null;
  isRead: boolean;
  createdAt: string; // LocalDateTime 직렬화 — 'Z' 없는 ISO 문자열
}
