import { Button } from '../../../shared/components/Button/Button';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { Modal } from '../../../shared/components/Modal';
import { usePayments } from '../hooks/usePayments';
import type { PaymentHistoryItem, PaymentStatus } from '../types';
import { formatAmount, PLAN_NAME_LABEL } from '../utils/planFormat';

type Props = {
  open: boolean;
  onClose: () => void;
};

const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = {
  READY: '결제 대기',
  PAID: '결제 완료',
  FAILED: '결제 실패',
  CANCELED: '결제 취소',
};

function formatApprovedAt(approvedAt: string | null): string {
  if (!approvedAt) return '-';
  const date = new Date(approvedAt);
  if (Number.isNaN(date.getTime())) return approvedAt;
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function PaymentRow({ payment }: { payment: PaymentHistoryItem }) {
  const isFailed = payment.status === 'FAILED' || payment.status === 'CANCELED';

  return (
    <div className="flex items-center justify-between rounded-xl border border-border px-4 py-3 text-sm">
      <div className="flex flex-col gap-1">
        <span className="font-semibold text-heading">
          {PLAN_NAME_LABEL[payment.planName] ?? payment.planName} 플랜 구독
        </span>
        <span className={isFailed ? 'text-danger' : 'text-text-muted'}>
          {formatApprovedAt(payment.approvedAt)} · {PAYMENT_STATUS_LABEL[payment.status]}
        </span>
      </div>
      <span className="font-semibold text-heading">{formatAmount(payment.amount)}</span>
    </div>
  );
}

// 결제 내역 실연동(#864, 토스페이먼츠 #989/HAJA-490) — GET /me/payments 실데이터를 최신순으로
// 보여준다(previousBillingDate 1개월 역산 근사치 로직 삭제 — handoff §4). FAILED/CANCELED 건은
// 문구 색상으로 구분 표기한다. 모달이 열려 있을 때만 조회한다(enabled=open, 불필요한 선조회 방지).
export function BillingHistoryModal({ open, onClose }: Props) {
  const { data: payments, isLoading, isError } = usePayments(open);

  return (
    <Modal open={open} onClose={onClose} title="결제 내역">
      <div className="flex w-full max-w-sm flex-col gap-5">
        {isLoading && <LoadingSpinner className="flex items-center justify-center gap-2 py-6" />}

        {isError && (
          <p role="alert" className="m-0 text-sm text-danger">
            결제 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
        )}

        {!isLoading && !isError && payments?.length === 0 && (
          <p className="m-0 text-sm text-text-muted">결제 이력이 없습니다.</p>
        )}

        {!isLoading && !isError && payments && payments.length > 0 && (
          <div className="flex flex-col gap-2">
            {payments.map((payment) => (
              <PaymentRow key={payment.id} payment={payment} />
            ))}
          </div>
        )}

        <div className="flex justify-end">
          <Button type="button" variant="secondary" onClick={onClose}>
            닫기
          </Button>
        </div>
      </div>
    </Modal>
  );
}
