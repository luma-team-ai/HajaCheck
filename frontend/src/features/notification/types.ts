// 알림 센터(HAJA-38 FR-9, #25/#423) — BE NotificationController/NotificationResponse.java 계약과 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — shared NotificationDropdown이 쓰는
// NotificationItem/NotificationFilter 타입은 그쪽(shared)에서 그대로 가져다 쓰고, 여기서는 BE 원본
// 응답 형태만 정의한다.

// backend NotificationType.java(notification_type enum)와 1:1
export type NotificationTypeCode = 'ANALYSIS_DONE' | 'REVIEW_PENDING' | 'COUNSEL_REPLIED' | 'INSPECTION_DUE';

// NotificationDropdown 필터 카테고리 — Anima 정답지(anima_알림센터_20260721.md 파트3) categories 4종 +
// BE가 NotificationType을 확장했는데 FE 배포가 아직 안 따라간 경우를 위한 폴백 '기타'(PR머신 P1)
export type NotificationCategory = '분석' | '검수' | '상담' | '점검일' | '기타';

// GET /api/notifications 응답 1건 — NotificationResponse.java와 1:1.
// type은 constants.ts의 NOTIFICATION_TYPE_META 4종을 우선 매핑 대상으로 삼되, BE enum이 FE보다 먼저
// 확장될 수 있어(PR머신 P1) string으로 넓게 받는다 — getNotificationTypeMeta가 미지의 값을 폴백 처리한다.
// payload는 타입별로 구조가 다른 자유 형식 jsonb(현재 BE에 생성 트리거가 없어 스키마 미확정)라
// Record<string, unknown>으로만 받고, description 등 표시용 필드는 있으면 쓰고 없으면 생략한다.
export interface NotificationApiItem {
  id: number;
  type: string;
  payload: Record<string, unknown> | null;
  isRead: boolean;
  createdAt: string; // LocalDateTime 직렬화 — 'Z' 없는 ISO 문자열
}
