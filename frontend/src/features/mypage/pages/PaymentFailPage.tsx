import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button/Button';

const MYPAGE_PLAN_PATH = '/mypage/plan';

// 결제 실패 사유 코드 → 안내 문구. 토스페이먼츠 자체 에러 코드(예: PAY_PROCESS_CANCELED)이며
// 우리 서비스 ApiError.code(PAYMENT_* 등)와는 별개 체계다 — code로 분기하되(메시지 문자열 매칭
// 금지 컨벤션 유지) 알 수 없는 코드는 SDK가 함께 내려주는 message를 그대로 보여준다.
const TOSS_FAIL_MESSAGE: Record<string, string> = {
  PAY_PROCESS_CANCELED: '결제를 취소했습니다.',
  PAY_PROCESS_ABORTED: '결제 진행 중 오류가 발생했습니다.',
  REJECT_CARD_COMPANY: '카드사에서 결제를 거절했습니다.',
};

// 토스페이먼츠 결제창 연동(#989, HAJA-490) — failUrl 리다이렉트 진입점(handoff §3). 결제창이 넘겨준
// 쿼리(code/message/orderId)로 실패 사유를 표시하고, 재시도 동선(내 플랜으로 돌아가 다시 시도)만
// 제공한다(웹훅·자동 재시도는 범위 밖 — handoff 배경).
export function PaymentFailPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const code = searchParams.get('code');
  const message = searchParams.get('message');
  const reason = (code && TOSS_FAIL_MESSAGE[code]) || message || '결제가 완료되지 않았습니다.';

  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-4 p-8 text-center">
      <p role="alert" className="m-0 text-sm text-danger">
        {reason}
      </p>
      <p className="m-0 text-sm text-text-muted">플랜은 변경되지 않았습니다. 다시 시도해 주세요.</p>
      <Button type="button" variant="primary" onClick={() => navigate(MYPAGE_PLAN_PATH)}>
        내 플랜으로 돌아가기
      </Button>
    </div>
  );
}
