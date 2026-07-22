import type { NotificationApiItem } from '../types';

// MSW 응답용 예시 값 — Anima 정답지(anima_알림센터_20260721.md 파트3 initialNotifications) 5건 기준.
// BE payload는 타입별 자유 형식(jsonb)이라 description 표시용 필드만 최소로 채운다(types.ts 주석 참고).
function minutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60 * 1000).toISOString();
}

export const mockNotifications: NotificationApiItem[] = [
  {
    id: 1,
    type: 'ANALYSIS_DONE',
    payload: { description: '강남 오피스타워 8회차 · 하자 87건 탐지' },
    isRead: false,
    createdAt: minutesAgo(0),
  },
  {
    id: 2,
    type: 'REVIEW_PENDING',
    payload: null,
    isRead: false,
    createdAt: minutesAgo(12),
  },
  {
    id: 3,
    type: 'COUNSEL_REPLIED',
    payload: { description: '요금제 문의' },
    isRead: false,
    createdAt: minutesAgo(60),
  },
  {
    id: 4,
    type: 'INSPECTION_DUE',
    payload: { description: '한강대교 북단 D-3' },
    isRead: true,
    createdAt: minutesAgo(180),
  },
  {
    id: 5,
    type: 'ANALYSIS_DONE',
    payload: { description: '월 분석 이미지 80% 도달 (786/1,000)' },
    isRead: true,
    createdAt: minutesAgo(24 * 60),
  },
];
