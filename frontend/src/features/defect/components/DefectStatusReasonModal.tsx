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
  /** 제출 중에는 확인 버튼을 비활성화해 중복 제출을 막는다. */
  isSubmitting?: boolean;
  /** 제출 실패 메시지 — Modal이 createPortal로 document.body에 풀스크린 오버레이를 씌우므로,
   * 호출부(DefectStatusChangeControl)가 모달 바깥에 에러를 렌더링하면 오버레이에 가려 사용자에게
   * 보이지 않는다(#726 롤백 이후 재통합 과정에서 발견). 반드시 모달 안에서 보여준다. */
  submitError?: string | null;
}

// 조치 보드 역행·건너뛰기 드롭(HAJA-349/#630) — 사유 입력을 요구하는 모달. 별도 Toast 시스템을 도입하지
// 않는 컨벤션에 맞춰 성공/실패 알림은 모달 내부 인라인 role="alert"로 처리한다(모달이 닫히기 전까지는
// 호출부의 인라인 알림이 오버레이에 가려 보이지 않으므로 — submitError 참고).
export function DefectStatusReasonModal({
  defect,
  targetStatus,
  onCancel,
  onSubmit,
  isSubmitting = false,
  submitError = null,
}: Props) {
  const [reason, setReason] = useState('');
  const trimmed = reason.trim();
  const isValid = trimmed.length > 0 && trimmed.length <= MAX_REASON_LENGTH;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isValid || isSubmitting) {
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

        {submitError && (
          <p className="m-0 text-sm text-danger" role="alert">
            {submitError}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onCancel}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={!isValid || isSubmitting}>
            {isSubmitting ? '처리 중...' : '확인'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
