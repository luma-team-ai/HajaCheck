import { useEffect, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button/Button';
import { Modal } from '../../../shared/components/Modal';
import type { PlanName } from '../types';
import { PLAN_NAME_LABEL } from '../utils/planFormat';

type Props = {
  open: boolean;
  onClose: () => void;
  /** 현재 플랜보다 상위인 플랜만(PlanCard가 계산해 전달). 빈 배열이면 선택지 없음. */
  candidates: PlanName[];
  onCheckout: (planName: PlanName) => void;
  isSubmitting?: boolean;
  errorMessage?: string;
};

// 토스페이먼츠 결제창 연동(#989, HAJA-490) 플랜 업그레이드 모달 — "결제하기" 클릭 시
// POST /me/plan/orders로 주문을 생성하고 곧바로 토스페이먼츠 결제창(카드)을 띄운다(#712 모의
// 결제를 대체). 정확한 결제 금액은 서버 응답이 source of truth라 이 목록에서는 표시하지 않는다
// (구 UPGRADE_PLAN_PRICE 하드코딩 삭제 — handoff §2) — 결제창에서 확인 후 결제를 진행한다.
// 열릴 때(닫힘→열림 전환)마다 선택값을 candidates 첫 항목으로 리셋한다(PlanPolicyModal과 동일 패턴 —
// 모달이 열려 있는 동안 부모 리렌더로 candidates 참조가 바뀌어도 사용자 선택이 유지되게).
export function PlanCheckoutModal({
  open,
  onClose,
  candidates,
  onCheckout,
  isSubmitting = false,
  errorMessage,
}: Props) {
  const [selected, setSelected] = useState<PlanName | null>(candidates[0] ?? null);
  const prevOpenRef = useRef(open);

  useEffect(() => {
    if (open && !prevOpenRef.current) {
      setSelected(candidates[0] ?? null);
    }
    prevOpenRef.current = open;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- candidates는 open 전환 시점에만 읽는다(의도적).
  }, [open]);

  return (
    <Modal open={open} onClose={onClose} closeOnOverlayClick={!isSubmitting} title="플랜 업그레이드">
      <div className="flex w-full max-w-sm flex-col gap-5">
        <p className="m-0 text-sm text-text-muted">
          플랜을 선택하고 결제하기를 누르면 토스페이먼츠 결제창이 열립니다. 정확한 결제 금액은
          결제창에서 확인할 수 있습니다.
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

        {errorMessage && (
          <p role="alert" className="m-0 text-sm text-danger">
            {errorMessage}
          </p>
        )}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose} disabled={isSubmitting}>
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={() => selected && onCheckout(selected)}
            disabled={!selected || isSubmitting}
          >
            {isSubmitting ? '결제 처리 중...' : '결제하기'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
