import type { CompanyStatus } from '../types';

interface SignupStatusStepperProps {
  status: CompanyStatus;
}

// 가입 승인 대기 화면 스테퍼 — 신청완료(항상 완료) → 서류검토중/반려됨 → 승인완료
export function SignupStatusStepper({ status }: SignupStatusStepperProps) {
  const isReviewing = status === 'PENDING_REVIEW';
  const isApproved = status === 'APPROVED';
  const isRejected = status === 'REJECTED';

  return (
    <ol className="auth-signup-stepper">
      <li className="auth-signup-step auth-signup-step--done">
        <span className="auth-signup-step-icon">✓</span>
        신청완료
      </li>
      <li
        className={`auth-signup-step${isReviewing ? ' auth-signup-step--active' : ''}${isRejected ? ' auth-signup-step--rejected' : ''}`}
      >
        <span className="auth-signup-step-icon">{isRejected ? '✕' : '◉'}</span>
        {isRejected ? '반려됨' : '서류검토중'}
      </li>
      <li className={`auth-signup-step${isApproved ? ' auth-signup-step--done' : ''}`}>
        <span className="auth-signup-step-icon">{isApproved ? '✓' : '○'}</span>
        승인완료
      </li>
    </ol>
  );
}
