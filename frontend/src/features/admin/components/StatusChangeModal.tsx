import { useEffect, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { STATUS_CHANGE_OPTIONS, STATUS_DOT_CLASS, STATUS_LABEL } from '../constants';
import type { AdminUser, AdminUserStatus } from '../types';
import { UserAvatar } from './UserAvatar';

interface StatusChangeModalProps {
  user: AdminUser | null;
  onClose: () => void;
  onConfirm: (user: AdminUser, status: AdminUserStatus) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
}

// 상태 변경 모달 — Figma node-id 991-3102, 행 액션 "상태 변경"에서 연다.
// 변경 사유 입력은 제외한다(사용자 지시) — 신규 상태 선택만으로 저장 버튼이 활성화된다.
export function StatusChangeModal({
  user,
  onClose,
  onConfirm,
  isSubmitting,
  submitErrorMessage,
}: StatusChangeModalProps) {
  const [selectedStatus, setSelectedStatus] = useState<AdminUserStatus | null>(null);

  useEffect(() => {
    setSelectedStatus(user?.status ?? null);
  }, [user]);

  if (!user) {
    return null;
  }

  function handleSave() {
    if (selectedStatus && user) {
      // catch만 해서 콘솔에 unhandled rejection이 찍히지 않게 한다 — 에러 메시지는
      // 페이지가 넘겨주는 submitErrorMessage(mutation.error)로 아래에 표시된다.
      onConfirm(user, selectedStatus).catch(() => {});
    }
  }

  return (
    <Modal open={Boolean(user)} onClose={onClose} title="상태 변경" closeOnOverlayClick={false}>
      <div className="flex w-105 max-w-full flex-col gap-6">
        <div className="flex items-center justify-between rounded-[20px] border border-border bg-surface-muted p-4">
          <div className="flex items-center gap-4">
            <UserAvatar user={user} size="lg" />
            <div className="flex flex-col">
              <span className="text-base text-heading">{user.name}</span>
              <span className="text-sm text-text-muted">{user.email}</span>
            </div>
          </div>
          <div className="flex flex-col items-end gap-1.5">
            <span className="text-sm tracking-wide text-text-muted uppercase">Current status</span>
            <span className="flex items-center gap-1.5 text-sm font-medium">
              <span
                className={`inline-block h-2 w-2 rounded-sm ${STATUS_DOT_CLASS[user.status]}`}
                aria-hidden
              />
              {STATUS_LABEL[user.status]}
            </span>
          </div>
        </div>

        <div className="flex flex-col gap-3">
          <p className="m-0 text-base text-text-muted">신규 상태 선택</p>
          <div role="radiogroup" aria-label="신규 상태" className="flex flex-col gap-3">
            {STATUS_CHANGE_OPTIONS.map(({ status, description }) => (
              <label
                key={status}
                className={`flex cursor-pointer flex-col gap-1 rounded-[20px] border p-4 ${
                  selectedStatus === status
                    ? 'border-heading bg-surface-muted'
                    : 'border-border bg-surface'
                }`}
              >
                <span className="flex items-center justify-between">
                  <span className="flex items-center gap-2">
                    <span
                      className={`inline-block h-2 w-2 rounded-sm ${STATUS_DOT_CLASS[status]}`}
                      aria-hidden
                    />
                    <span className="text-base text-heading">
                      {STATUS_LABEL[status]} ({status === 'ACTIVE' ? 'Active' : 'Suspended'})
                    </span>
                  </span>
                  <input
                    type="radio"
                    name="admin-user-status"
                    className="h-5 w-5 accent-heading"
                    checked={selectedStatus === status}
                    onChange={() => setSelectedStatus(status)}
                  />
                </span>
                <span className="text-sm text-text-muted">{description}</span>
              </label>
            ))}
          </div>
        </div>

        {submitErrorMessage && (
          <p role="alert" className="m-0 text-sm text-danger">
            {submitErrorMessage}
          </p>
        )}

        <div className="-mx-6 -mb-6 flex justify-center gap-3.5 border-t border-border bg-surface-muted px-6 pt-5 pb-6">
          <Button
            type="button"
            variant="secondary"
            size="lg"
            onClick={onClose}
            disabled={isSubmitting}
            className="w-[180px]"
          >
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            size="lg"
            disabled={!selectedStatus || isSubmitting}
            onClick={handleSave}
            className="flex-1"
          >
            {isSubmitting ? '저장 중...' : '변경 내용 저장'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
