import { Button } from '../../../shared/components/Button/Button';
import { Modal } from '../../../shared/components/Modal';
import type { MyPlanInfo } from '../types';
import { PLAN_NAME_LABEL, formatBillingDate, formatPriceMonthly } from '../utils/planFormat';

type Props = {
  open: boolean;
  onClose: () => void;
  plan: MyPlanInfo;
};

// nextBillingDate(다음 결제 예정일)에서 한 달을 역산해 "직전 결제일"을 표시용으로만 만든다 —
// 실 결제 이력 레코드가 없어(아래 주석 참고) 현재 활성 구독 기준 근사치다.
function previousBillingDate(nextBillingDate: string | null): string | null {
  if (!nextBillingDate) return null;
  const date = new Date(`${nextBillingDate.slice(0, 10)}T00:00:00`);
  if (Number.isNaN(date.getTime())) return null;
  date.setMonth(date.getMonth() - 1);
  return formatBillingDate(date.toISOString());
}

// 결제 내역(#712 Figma 리디자인, PlanCard "결제 내역" 버튼) — 실 결제 이력 API/스키마가 없다(PG
// 실결제 Out-of-scope, plan.md "범위 밖(후속)" — 기존 BillingHistoryPlaceholder와 동일 사유).
// 대신 현재 활성 구독을 "모의 결제 1건"으로 보여준다. FREE(유료 이력 없음)는 빈 상태로 표시.
export function BillingHistoryModal({ open, onClose, plan }: Props) {
  const paidAt = previousBillingDate(plan.nextBillingDate);

  return (
    <Modal open={open} onClose={onClose} title="결제 내역">
      <div className="flex w-full max-w-sm flex-col gap-5">
        {paidAt ? (
          <div className="flex items-center justify-between rounded-xl border border-border px-4 py-3 text-sm">
            <div className="flex flex-col gap-1">
              <span className="font-semibold text-heading">
                {PLAN_NAME_LABEL[plan.name] ?? plan.name} 플랜 구독
              </span>
              <span className="text-text-muted">{paidAt} 결제 완료</span>
            </div>
            <span className="font-semibold text-heading">{formatPriceMonthly(plan.priceMonthly)}</span>
          </div>
        ) : (
          <p className="m-0 text-sm text-text-muted">결제 이력이 없습니다.</p>
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
