import { useState, type FormEvent } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { DEFECT_STATUS_LABEL } from '../types';
import type { Defect, DefectStatus } from '../types';

// 백엔드 DTO 제약(Request body reason은 max 500) — handoff §범위 API 설명과 1:1.
const MAX_REASON_LENGTH = 500;

interface Props {
  defect: Defect;
  targetStatus: DefectStatus;
  onCancel: () => void;
  onSubmit: (reason: string) => void;
}

// 조치 보드 역행·건너뛰기 드롭(HAJA-349/#630) — 사유 입력을 요구하는 모달. 별도 Toast 시스템을 도입하지
// 않는 컨벤션에 맞춰 성공/실패 알림은 이 모달을 닫은 뒤 보드 쪽 인라인 role="alert"로 처리한다
// (DefectStatusStepper.tsx가 쓰는 패턴, DefectActionBoard 참조).
export function DefectStatusReasonModal({ defect, targetStatus, onCancel, onSubmit }: Props) {
  const [reason, setReason] = useState('');
  const trimmed = reason.trim();
  const isValid = trimmed.length > 0 && trimmed.length <= MAX_REASON_LENGTH;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isValid) {
      return;
    }
    onSubmit(trimmed);
  }

  return (
    <Modal open onClose={onCancel} title="상태 변경 사유 입력" closeOnOverlayClick={false}>
      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <p className="m-0 text-sm text-text-default">
          {DEFECT_STATUS_LABEL[defect.status]} → {DEFECT_STATUS_LABEL[targetStatus]}(으)로 상태를 되돌리거나
          건너뛰려면 사유를 입력해야 합니다.
        </p>

        <label className="flex flex-col gap-1.5 text-sm font-medium text-text-default" htmlFor="defect-status-reason">
          사유
          <textarea
            id="defect-status-reason"
            className="min-h-24 rounded-xl border border-border bg-surface p-3 text-sm text-primary"
            value={reason}
            maxLength={MAX_REASON_LENGTH}
            onChange={(event) => setReason(event.target.value)}
            autoFocus
          />
        </label>

        {reason.length > 0 && !isValid && (
          <p className="m-0 text-sm text-danger" role="alert">
            사유는 500자 이하로 입력해 주세요.
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onCancel}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={!isValid}>
            확인
          </Button>
        </div>
      </form>
    </Modal>
  );
}
