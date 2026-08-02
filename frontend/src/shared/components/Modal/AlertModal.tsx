import { Button } from '../Button';
import { Modal } from './Modal';

interface AlertModalProps {
  open: boolean;
  title?: string;
  message: string;
  onClose: () => void;
  confirmLabel?: string;
}

/**
 * 범용 알림(alert) 모달 — "제목 + 메시지 + 확인 버튼" 패턴만 담당한다.
 * 도메인 특화 로직은 넣지 않는다(다른 기능에서도 재사용 가능하도록 유지).
 */
export function AlertModal({ open, title, message, onClose, confirmLabel = '확인' }: AlertModalProps) {
  return (
    <Modal open={open} onClose={onClose} title={title}>
      <div className="flex w-80 max-w-full flex-col gap-6">
        <p role="alert" className="m-0 whitespace-pre-line text-sm text-text-default">
          {message}
        </p>
        <div className="flex justify-end">
          <Button type="button" variant="primary" onClick={onClose}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
