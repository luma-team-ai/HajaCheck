import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { NotificationItem } from '../../../shared/components/NotificationDropdown';
import { NotificationDropdown } from '../../../shared/components/NotificationDropdown';
import { NOTIFICATION_ALL_FILTER_KEY, NOTIFICATION_FILTERS, getNotificationTypeMeta } from '../constants';
import { useDeleteNotification, useMarkNotificationsAsRead, useNotifications } from '../hooks/useNotifications';
import type { NotificationApiItem } from '../types';
import { formatElapsedTime } from '../utils/formatElapsedTime';

// "대화 열기" 버튼이 markAsRead만 호출하고 실제로 어디로도 이동하지 않던 버그 수정(사용자 피드백).
// COUNSEL_REPLIED payload는 백엔드가 항상 {ticketId}로 직렬화한다(CounselReplyNotificationPayload
// 확인 완료) — 다른 타입은 payload 구조가 확정돼 있지 않아 이번 수정 범위에서 제외한다.
function resolveCounselTicketId(raw: NotificationApiItem): number | null {
  const ticketId = raw.payload?.ticketId;
  return typeof ticketId === 'number' ? ticketId : null;
}

interface NotificationCenterProps {
  /** 패널 열림 여부 — Header 벨(shared, AppLayout 내부)이 AppShellRoute에 있어 토글 핸들러도
   * 그쪽이 소유한다. 이 컴포넌트는 activeFilter·목록 조회·읽음 처리만 책임진다. */
  open: boolean;
  onClose: () => void;
  /** 로그인 상태에서만 조회 — useAuthStore.user 유무를 그대로 전달 */
  enabled: boolean;
}

function extractDescription(payload: NotificationApiItem['payload']): string | undefined {
  const raw = payload?.description;
  return typeof raw === 'string' ? raw : undefined;
}

// Header 벨 클릭 시 열리는 알림 패널 컨테이너 — shared NotificationDropdown(프리젠테이션, 이은석 소유,
// 이번 유형별 아이콘 배선(#1244)만 예외적으로 함께 수정)을 그대로 렌더한다(HAJA-38, Figma node-id
// 208-2458 / Anima 파트3 NotificationPanelSection).
export function NotificationCenter({ open, onClose, enabled }: NotificationCenterProps) {
  const navigate = useNavigate();
  const [activeFilter, setActiveFilter] = useState(NOTIFICATION_ALL_FILTER_KEY);
  const { data } = useNotifications(enabled);
  const { markAsRead, markAllAsRead } = useMarkNotificationsAsRead();
  const { deleteNotification } = useDeleteNotification();

  const notifications = data ?? [];

  // markAsRead(useMutation.mutate 래퍼)는 렌더마다 identity가 바뀌어 useMemo 의존성으로 써도
  // 매 렌더 재계산됐다(P2) — 목록 규모가 작아(최대 30건, BE 컷 기준) 순수 계산으로 충분하다.
  const items: NotificationItem[] = notifications.map((raw) => {
    const meta = getNotificationTypeMeta(raw.type);
    const counselTicketId = raw.type === 'COUNSEL_REPLIED' ? resolveCounselTicketId(raw) : null;
    return {
      id: raw.id,
      category: meta.category,
      title: meta.title,
      description: extractDescription(raw.payload),
      timestamp: formatElapsedTime(raw.createdAt),
      read: raw.isRead,
      iconSrc: meta.iconSrc,
      // 폴백(미지의 type) 메타는 actionLabel이 없다 — 의미를 모르는 액션 버튼을 노출하지 않는다.
      ...(meta.actionLabel
        ? {
            actionLabel: meta.actionLabel,
            onAction: () => {
              markAsRead(raw.id);
              if (counselTicketId !== null) {
                navigate(`/support/history?ticketId=${counselTicketId}`);
                onClose();
              }
            },
          }
        : {}),
      // X는 "숨김"이 아니라 실제 삭제 — DELETE /api/notifications/{id}로 notifications 행을 지운다.
      onDismiss: () => deleteNotification(raw.id),
    };
  });

  const unreadCount = notifications.filter((item) => !item.isRead).length;

  // open 상태 자체는 여전히 이 컴포넌트가 소유(AppShellRoute는 boolean만 넘김) — 훅은 open과 무관하게
  // 항상 호출해 미리 데이터를 준비해 둔다(패널을 처음 열 때 로딩 깜빡임 최소화).
  if (!open) {
    return null;
  }

  return (
    <div className="fixed top-16 right-6 z-50 md:right-8">
      <NotificationDropdown
        notifications={items}
        unreadCount={unreadCount}
        filters={NOTIFICATION_FILTERS}
        activeFilter={activeFilter}
        onFilterChange={setActiveFilter}
        onMarkAllRead={() => markAllAsRead(notifications.filter((item) => !item.isRead).map((item) => item.id))}
        onClose={onClose}
      />
    </div>
  );
}
