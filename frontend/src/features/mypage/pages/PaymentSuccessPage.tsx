import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getApiErrorCode, getApiErrorMessage } from '../../../shared/api/types';
import { Button } from '../../../shared/components/Button/Button';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useConfirmPayment } from '../hooks/useConfirmPayment';
import { MYPAGE_ERROR_CODE } from '../types';

const MYPAGE_PLAN_PATH = '/mypage/plan';

// 결제 승인 실패를 3가지로 분류한다(백엔드 #988 리뷰 픽스로 계약 확장, 2026-07-27) — error.code로만
// 분기한다(메시지 문자열 매칭 금지).
// - applyPending: PG 승인은 성공했으나 플랜 반영만 실패한 상태. "결제 실패"로 읽히면 절대 안 된다
//   (재결제 시 환불 불가한 중복 청구가 된다) — 재시도/재결제 버튼을 노출하지 않는다.
// - planConflict: 이미 그 플랜인데 확정을 시도해 PG 청구 전에 거절된 경우. 청구 자체가 없었으므로
//   역시 "실패"가 아니라 "플랜 정보 새로고침이 필요한 상태"로 안내한다.
// - other: PAYMENT_ORDER_NOT_FOUND(404)/PAYMENT_AMOUNT_MISMATCH(400)/PAYMENT_GATEWAY_ERROR(502)
//   등 실제 결제 실패 — 기존처럼 서버 메시지를 그대로 보여준다(getApiErrorMessage).
type ConfirmFailureKind = 'applyPending' | 'planConflict' | 'other';

function classifyConfirmError(error: unknown): ConfirmFailureKind {
  const code = getApiErrorCode(error);
  if (code === MYPAGE_ERROR_CODE.PAYMENT_PLAN_APPLY_PENDING) return 'applyPending';
  if (code === MYPAGE_ERROR_CODE.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT) return 'planConflict';
  return 'other';
}

// 토스페이먼츠 결제창 연동(#989, HAJA-490) — successUrl 리다이렉트 진입점(handoff §3). 결제창이
// 넘겨준 쿼리(paymentKey/orderId/amount)로 POST /me/payments/confirm을 호출해 결제를 승인한다.
// 백엔드가 멱등 처리하므로 새로고침·중복 진입 시에도 동일 결과를 200으로 돌려준다 — "이미
// 결제되었습니다" 류 오인 문구는 절대 띄우지 않는다(handoff §3 명시 금지 사항).
// hasSubmittedRef는 동일 쿼리 조합에 대해 confirm mutate를 1회만 트리거한다(React 18 StrictMode
// 개발 모드 effect 이중 실행에서도 같은 쿼리면 재요청하지 않음 — 실제 새로고침/재진입은 쿼리
// 문자열이 그대로라도 페이지 자체가 새로 마운트되므로 ref가 초기화되어 정상적으로 재확인된다,
// 그리고 재확인 자체는 백엔드 멱등성이 보장한다).
export function PaymentSuccessPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const confirmPayment = useConfirmPayment();
  const hasSubmittedRef = useRef<string | null>(null);

  const paymentKey = searchParams.get('paymentKey');
  const orderId = searchParams.get('orderId');
  const amountParam = searchParams.get('amount');
  const amount = amountParam !== null ? Number(amountParam) : null;
  const isValidQuery =
    Boolean(paymentKey) && Boolean(orderId) && amount !== null && !Number.isNaN(amount);

  useEffect(() => {
    if (!isValidQuery || !paymentKey || !orderId || amount === null) return;

    const requestKey = `${orderId}:${paymentKey}:${amount}`;
    if (hasSubmittedRef.current === requestKey) return;
    hasSubmittedRef.current = requestKey;

    confirmPayment.mutate({ paymentKey, orderId, amount });
    // confirmPayment.mutate는 useMutation이 매 렌더 안정적으로 제공하는 참조(react-query v5) — 실제
    // 재실행 트리거는 쿼리 파라미터 값이어야 하므로 이 값들만 의존성으로 둔다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isValidQuery, paymentKey, orderId, amount]);

  useEffect(() => {
    if (confirmPayment.isSuccess) {
      navigate(MYPAGE_PLAN_PATH, { replace: true });
    }
  }, [confirmPayment.isSuccess, navigate]);

  if (!isValidQuery) {
    return (
      <div className="flex min-h-full flex-col items-center justify-center gap-4 p-8 text-center">
        <p role="alert" className="m-0 text-sm text-danger">
          잘못된 접근입니다. 결제 정보를 확인할 수 없습니다.
        </p>
        <Button type="button" variant="secondary" onClick={() => navigate(MYPAGE_PLAN_PATH)}>
          내 플랜으로 돌아가기
        </Button>
      </div>
    );
  }

  const confirmFailureKind = confirmPayment.isError
    ? classifyConfirmError(confirmPayment.error)
    : null;

  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-4 p-8 text-center">
      {(confirmPayment.isIdle || confirmPayment.isPending) && (
        <>
          <LoadingSpinner className="flex items-center justify-center gap-2" />
          <p className="m-0 text-sm text-text-muted">결제를 확인하고 있습니다...</p>
        </>
      )}

      {/* 결제는 성공했으나 플랜 반영만 남은 상태 — "실패"가 아니므로 danger 스타일도, 재시도/재결제
          버튼도 쓰지 않는다. 사용자를 안심시키고 확인 동선만 제공한다. */}
      {confirmFailureKind === 'applyPending' && (
        <>
          <p role="status" className="m-0 text-sm text-text-default">
            결제는 완료되었습니다. 플랜 반영을 처리하고 있습니다. 잠시 후 내 플랜 화면에서 다시
            확인해 주세요.
          </p>
          <Button type="button" variant="secondary" onClick={() => navigate(MYPAGE_PLAN_PATH)}>
            내 플랜에서 확인하기
          </Button>
        </>
      )}

      {/* 이미 그 플랜을 이용 중이라 PG 청구 전에 거절된 경우 — 청구 자체가 없었으므로 역시 실패
          취급하지 않고, 플랜 정보를 새로고침하도록만 안내한다. */}
      {confirmFailureKind === 'planConflict' && (
        <>
          <p role="status" className="m-0 text-sm text-text-default">
            이미 해당 플랜을 이용 중입니다. 내 플랜 화면에서 최신 정보를 확인해 주세요.
          </p>
          <Button type="button" variant="secondary" onClick={() => navigate(MYPAGE_PLAN_PATH)}>
            내 플랜으로 돌아가기
          </Button>
        </>
      )}

      {confirmFailureKind === 'other' && (
        <>
          <p role="alert" className="m-0 text-sm text-danger">
            {getApiErrorMessage(
              confirmPayment.error,
              '결제 확인 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
            )}
          </p>
          <Button type="button" variant="secondary" onClick={() => navigate(MYPAGE_PLAN_PATH)}>
            내 플랜으로 돌아가기
          </Button>
        </>
      )}
    </div>
  );
}
