import { useEffect, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button/Button';
import { Modal } from '../../../shared/components/Modal';
import type { PlanName, PlanOrder } from '../types';
import { PLAN_NAME_LABEL, formatPriceMonthly } from '../utils/planFormat';

type Props = {
  open: boolean;
  onClose: () => void;
  /** 현재 플랜보다 상위인 플랜만(PlanCard가 계산해 전달). 빈 배열이면 선택지 없음. */
  candidates: PlanName[];
  /** null=플랜 선택 단계. 값이 있으면 주문이 생성된 확인 단계 — 서버가 계산한 실 금액을 보여준다. */
  order: PlanOrder | null;
  onSelectPlan: (planName: PlanName) => void;
  onConfirmPayment: () => void;
  isCreatingOrder?: boolean;
  isRequestingPayment?: boolean;
  errorMessage?: string;
};

// 토스페이먼츠 결제창 연동(#989, HAJA-490) 플랜 업그레이드 모달 — 2단계 흐름(코드 리뷰 P2 픽스:
// "결제창 진입 전 금액 미노출"). 1) 플랜 선택 → POST /me/plan/orders로 주문 생성(useCreatePlanOrder)
// 2) 응답의 order.amount(서버가 계산한 실 금액, source of truth)를 이 확인 화면에 표시 → 사용자가
// "결제 진행"을 눌러야만 토스페이먼츠 결제창(카드)이 열린다(useRequestTossPayment). 이전엔 주문
// 생성 직후 곧바로 결제창을 열어 금액을 확인할 기회가 없었다 — 이제는 여기서 반드시 확인한다.
// 확인 단계에서 "취소"를 누르면 모달만 닫는다 — 이미 생성된 READY 주문을 취소하는 API는 호출하지
// 않는다(백엔드 만료/재사용 정책 미확정, A가 백엔드와 별도로 정리할 영역).
// 열릴 때(닫힘→열림 전환)마다 선택값을 candidates 첫 항목으로 리셋한다(PlanPolicyModal과 동일 패턴 —
// 모달이 열려 있는 동안 부모 리렌더로 candidates 참조가 바뀌어도 사용자 선택이 유지되게).
export function PlanCheckoutModal({
  open,
  onClose,
  candidates,
  order,
  onSelectPlan,
  onConfirmPayment,
  isCreatingOrder = false,
  isRequestingPayment = false,
  errorMessage,
}: Props) {
  const [selected, setSelected] = useState<PlanName | null>(candidates[0] ?? null);
  const prevOpenRef = useRef(open);
  const isBusy = isCreatingOrder || isRequestingPayment;

  useEffect(() => {
    if (open && !prevOpenRef.current) {
      setSelected(candidates[0] ?? null);
    }
    prevOpenRef.current = open;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- candidates는 open 전환 시점에만 읽는다(의도적).
  }, [open]);

  return (
    <Modal open={open} onClose={onClose} closeOnOverlayClick={!isBusy} title="플랜 업그레이드">
      <div className="flex w-full max-w-sm flex-col gap-5">
        {!order && (
          <>
            <p className="m-0 text-sm text-text-muted">
              플랜을 선택하면 정확한 결제 금액을 확인한 뒤 결제를 진행합니다.
            </p>

            {candidates.length === 0 && (
              <p className="m-0 text-sm text-text-muted">업그레이드 가능한 상위 플랜이 없습니다.</p>
            )}

            <div className="flex flex-col gap-2">
              {candidates.map((name) => (
                <label
                  key={name}
                  className={`flex cursor-pointer items-center gap-2 rounded-xl border px-4 py-3 text-sm ${
                    selected === name ? 'border-heading bg-surface-muted' : 'border-border'
                  }`}
                >
                  <input
                    type="radio"
                    name="checkout-plan"
                    value={name}
                    checked={selected === name}
                    onChange={() => setSelected(name)}
                  />
                  {PLAN_NAME_LABEL[name] ?? name}
                </label>
              ))}
            </div>
          </>
        )}

        {order && (
          <div className="flex flex-col gap-2 rounded-xl border border-border bg-surface-muted px-4 py-3 text-sm">
            <span className="font-semibold text-heading">
              {PLAN_NAME_LABEL[order.planName] ?? order.planName} 플랜
            </span>
            <span className="text-text-muted">
              {formatPriceMonthly(order.amount)} 결제를 진행할까요?
            </span>
          </div>
        )}

        {errorMessage && (
          <p role="alert" className="m-0 text-sm text-danger">
            {errorMessage}
          </p>
        )}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose} disabled={isBusy}>
            취소
          </Button>
          {!order ? (
            <Button
              type="button"
              variant="primary"
              onClick={() => selected && onSelectPlan(selected)}
              disabled={!selected || isBusy}
            >
              {isCreatingOrder ? '주문 확인 중...' : '결제하기'}
            </Button>
          ) : (
            <Button type="button" variant="primary" onClick={onConfirmPayment} disabled={isBusy}>
              {isRequestingPayment ? '결제 처리 중...' : '결제 진행'}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
}
