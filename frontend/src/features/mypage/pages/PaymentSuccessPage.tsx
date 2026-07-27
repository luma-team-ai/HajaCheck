import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getApiErrorMessage } from '../../../shared/api/types';
import { Button } from '../../../shared/components/Button/Button';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useConfirmPayment } from '../hooks/useConfirmPayment';

const MYPAGE_PLAN_PATH = '/mypage/plan';

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

  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-4 p-8 text-center">
      {(confirmPayment.isIdle || confirmPayment.isPending) && (
        <>
          <LoadingSpinner className="flex items-center justify-center gap-2" />
          <p className="m-0 text-sm text-text-muted">결제를 확인하고 있습니다...</p>
        </>
      )}

      {confirmPayment.isError && (
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
