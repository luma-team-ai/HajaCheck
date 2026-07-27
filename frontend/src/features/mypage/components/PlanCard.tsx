import { useMemo, useState } from 'react';
import { getApiErrorCode } from '../../../shared/api/types';
import { Button } from '../../../shared/components/Button/Button';
import { TossClientKeyMissingError } from '../../../shared/lib/tossPayments/loadTossPaymentsSdk';
import { usePlanCheckout } from '../hooks/usePlanCheckout';
import { MYPAGE_ERROR_CODE, type MyPlanInfo, type PlanName } from '../types';
import { PLAN_NAME_LABEL, formatBillingDate, formatPriceMonthly } from '../utils/planFormat';
import { BillingHistoryModal } from './BillingHistoryModal';
import { PlanCheckoutModal } from './PlanCheckoutModal';

type Props = {
  plan: MyPlanInfo;
};

const PLAN_ORDER: PlanName[] = ['FREE', 'STANDARD', 'ENTERPRISE'];

// 현재보다 상위 플랜만 업그레이드 후보로 노출한다(FREE→STANDARD/ENTERPRISE, STANDARD→ENTERPRISE,
// ENTERPRISE→없음). FREE로의 다운그레이드는 범위 밖(plan.md "범위 밖(후속)")이라 후보에 포함하지 않는다.
function upgradeCandidates(current: PlanName): PlanName[] {
  const currentIndex = PLAN_ORDER.indexOf(current);
  return PLAN_ORDER.slice(currentIndex + 1);
}

// 토스페이먼츠 결제창 연동(#989, HAJA-490) — error.code로 분기한다(메시지 문자열 매칭 금지).
// TossClientKeyMissingError는 ApiError가 아니라(axios 인터셉터를 거치지 않고 SDK 로더가 직접
// throw) 우선 instanceof로 분기해 "결제창이 조용히 안 뜨는" 상태를 명확한 안내로 대체한다
// (KakaoMapKeyMissingError 선례와 동일 원칙).
function checkoutErrorMessage(error: unknown): string {
  if (error instanceof TossClientKeyMissingError) {
    return '결제 서비스 설정이 완료되지 않았습니다. 관리자에게 문의해 주세요.';
  }

  const code = getApiErrorCode(error);
  switch (code) {
    case MYPAGE_ERROR_CODE.PLAN_FORBIDDEN:
    case MYPAGE_ERROR_CODE.PAYMENT_FORBIDDEN:
      return '플랜 소유자만 결제를 진행할 수 있습니다.';
    case 'INVALID_INPUT':
      return '선택할 수 없는 플랜입니다. 다시 선택해 주세요.';
    case MYPAGE_ERROR_CODE.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT:
      return '처리 중인 구독 변경이 있습니다. 잠시 후 다시 시도해 주세요.';
    case MYPAGE_ERROR_CODE.PAYMENT_ORDER_NOT_FOUND:
      return '결제 주문 정보를 찾을 수 없습니다. 다시 시도해 주세요.';
    case MYPAGE_ERROR_CODE.PAYMENT_AMOUNT_MISMATCH:
      return '결제 금액이 일치하지 않습니다. 다시 시도해 주세요.';
    case MYPAGE_ERROR_CODE.PAYMENT_GATEWAY_ERROR:
      return '결제 서비스에 일시적인 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.';
    case MYPAGE_ERROR_CODE.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED:
      return '플랜 하향은 별도 확인이 필요합니다.';
    default:
      return '결제 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
  }
}

// 요금제 카드 — Figma 리디자인(node 1463-2786, #712). 플랜명(40px)+PLAN 고정 배지(상태 배지가
// 아니라 "PLAN" 텍스트 고정 — 기존 PLAN_STATUS_BADGE_CLASS와 무관), 가격·다음 결제일 한 줄,
// 사업자 인증 칩(3분기 — 아래 businessVerified 렌더 참고), "결제 여기서 해요!" 안내. 우측 버튼은
// "결제 내역"(실 결제 이력 모달) + "플랜 업그레이드"(토스페이먼츠 결제창, #989/HAJA-490). 기존
// 업그레이드 문의(useUpgradeInquiry, UPGRADE_REQUESTED 상태전이)는 모의 결제(#712)를 거쳐 이제
// 실 결제창(usePlanCheckout)으로 완전히 대체한다 — PlanStatus.UPGRADE_REQUESTED는 이제 이 화면에서
// 쓰지 않는다(BE 계약상 여전히 유효한 값이라 타입은 유지).
export function PlanCard({ plan }: Props) {
  const checkout = usePlanCheckout();
  const [isCheckoutOpen, setCheckoutOpen] = useState(false);
  const [isBillingOpen, setBillingOpen] = useState(false);

  const candidates = useMemo(() => upgradeCandidates(plan.name), [plan.name]);
  const isTopPlan = candidates.length === 0;

  // 성공 시 onSuccess는 사실상 호출되지 않는다(usePlanCheckout 참고 — redirect 방식이라 브라우저가
  // 결제창으로 이동하며 페이지가 전환된다). 모달을 여기서 닫지 않는 이유도 동일 — 취소/에러일 때만
  // 이 mutation이 정착(settled)되어 에러 UI를 보여줘야 하고, 성공 시엔 화면 자체가 곧 떠난다.
  function handleCheckout(planName: PlanName) {
    checkout.mutate(planName);
  }

  function handleCloseCheckout() {
    setCheckoutOpen(false);
    checkout.reset();
  }

  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-[40px] leading-none font-semibold text-heading">
              {PLAN_NAME_LABEL[plan.name] ?? plan.name}
            </span>
            <span className="rounded-full border border-heading px-3 py-1 text-xs font-bold tracking-wider text-heading uppercase">
              PLAN
            </span>
          </div>

          <p className="m-0 text-sm text-text-muted">
            {formatPriceMonthly(plan.priceMonthly)}
            {plan.nextBillingDate && (
              <> · 다음 결제일 {formatBillingDate(plan.nextBillingDate)}</>
            )}
          </p>

          {/* 사업자 인증 칩 3분기(handoff): true=초록점+완료, false=회색+미완료, null(개인 구독)=렌더 안 함 */}
          {plan.businessVerified !== null && (
            <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-border bg-surface px-3 py-1 text-xs font-semibold text-text-default">
              <span
                className={`h-1.5 w-1.5 rounded-full ${plan.businessVerified ? 'bg-[#16a34a]' : 'bg-neutral-100'}`}
                aria-hidden="true"
              />
              {plan.businessVerified ? '사업자 인증 완료' : '사업자 인증 미완료'}
            </span>
          )}

          <p className="m-0 text-xs text-text-muted">결제 여기서 해요!</p>
        </div>

        <div className="flex gap-3">
          <Button type="button" variant="secondary" onClick={() => setBillingOpen(true)}>
            결제 내역
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={() => setCheckoutOpen(true)}
            disabled={isTopPlan}
          >
            {isTopPlan ? '최상위 플랜 이용 중' : '플랜 업그레이드'}
          </Button>
        </div>
      </div>

      <PlanCheckoutModal
        open={isCheckoutOpen}
        onClose={handleCloseCheckout}
        candidates={candidates}
        onCheckout={handleCheckout}
        isSubmitting={checkout.isPending}
        errorMessage={checkout.isError ? checkoutErrorMessage(checkout.error) : undefined}
      />

      <BillingHistoryModal open={isBillingOpen} onClose={() => setBillingOpen(false)} />
    </section>
  );
}
