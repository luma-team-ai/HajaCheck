import { useEffect, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { ROLE_CHANGE_OPTIONS, ROLE_LABEL } from '../constants';
import type { AdminUser, AdminUserRole } from '../types';
import { UserAvatar } from './UserAvatar';

interface RoleChangeModalProps {
  user: AdminUser | null;
  onClose: () => void;
  onConfirm: (user: AdminUser, role: AdminUserRole) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
}

// 역할 변경 모달 — Figma node-id 991-2926, 행 액션 "역할 변경"에서 연다.
export function RoleChangeModal({
  user,
  onClose,
  onConfirm,
  isSubmitting,
  submitErrorMessage,
}: RoleChangeModalProps) {
  const [selectedRole, setSelectedRole] = useState<AdminUserRole | null>(null);

  // user가 바뀔 때(다른 행에서 열릴 때)만 현재 역할로 다시 초기화한다.
  useEffect(() => {
    setSelectedRole(user?.role ?? null);
  }, [user]);

  if (!user) {
    return null;
  }

  function handleSave() {
    if (selectedRole && user) {
      // 실패해도 여기서 별도 처리는 없다 — 모달을 열어둔 채(닫지 않고) 페이지가 넘겨주는
      // submitErrorMessage(mutation.error)가 아래에 표시된다. catch만 해서 콘솔에
      // unhandled rejection이 찍히지 않게 한다.
      onConfirm(user, selectedRole).catch(() => {});
    }
  }

  return (
    <Modal open={Boolean(user)} onClose={onClose} title="역할 변경" closeOnOverlayClick={false}>
      <div className="flex w-105 max-w-full flex-col gap-6">
        <div className="flex items-center gap-4 rounded-[20px] border border-border bg-surface-muted p-3.5">
          <UserAvatar user={user} size="lg" />
          <div className="flex flex-col gap-0.5">
            <span className="flex items-baseline gap-2">
              <span className="text-base font-medium text-heading">{user.name}</span>
              <span className="text-[11px] text-text-muted">({user.email})</span>
            </span>
            <span className="flex items-center gap-1.5 text-sm text-text-muted">
              <span className="inline-block h-1.5 w-1.5 rounded-full bg-warning" aria-hidden />
              현재 역할: {ROLE_LABEL[user.role]}
            </span>
          </div>
        </div>

        <div className="flex flex-col gap-3">
          <p className="m-0 text-xs tracking-wide text-text-muted uppercase">새로운 역할 선택</p>
          <div role="radiogroup" aria-label="새로운 역할" className="flex flex-col gap-3">
            {ROLE_CHANGE_OPTIONS.map(({ role, description }) => (
              <label
                key={role}
                className={`flex cursor-pointer items-start gap-3 rounded-[20px] border p-4 ${
                  selectedRole === role ? 'border-heading' : 'border-border'
                }`}
              >
                <input
                  type="radio"
                  name="admin-user-role"
                  className="mt-1 h-4 w-4 accent-heading"
                  checked={selectedRole === role}
                  onChange={() => setSelectedRole(role)}
                />
                <span className="flex flex-col gap-0.5">
                  <span className="text-base font-medium text-heading">{ROLE_LABEL[role]}</span>
                  <span className="text-xs text-text-muted">{description}</span>
                </span>
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
            disabled={!selectedRole || isSubmitting}
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
