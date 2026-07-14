import { useLocation, useSearchParams, Link } from 'react-router-dom';
import { SignupStatusStepper } from '../components/SignupStatusStepper';
import { useSignupStatus } from '../hooks/useSignupStatus';
import '../auth.css';

interface SignupPendingLocationState {
  companyName?: string;
  maskedEmail?: string;
}

const SUPPORT_CONTACT_MESSAGE = '문의: support@hajacheck.com (준비 중인 기능입니다)';

// 가입 승인 대기 화면 — signupToken은 URL 쿼리(새로고침 대비), 표시정보는 location.state(제출 시점 값)
export function CompanySignupPendingPage() {
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const token = searchParams.get('token');
  const locationState = (location.state ?? {}) as SignupPendingLocationState;

  const { status, refetch, isRefetching, isError } = useSignupStatus(token);

  if (!token) {
    return (
      <div className="auth-standalone-page">
        <section className="auth-standalone-panel">
          <p className="auth-form-error">잘못된 접근입니다. 회원가입을 다시 진행해 주세요.</p>
          <Link to="/signup/company" className="auth-link-btn">
            회원가입으로
          </Link>
        </section>
      </div>
    );
  }

  const companyName = status?.companyName ?? locationState.companyName ?? '-';
  const maskedEmail = locationState.maskedEmail ?? '-';
  const currentStatus = status?.status ?? 'PENDING_REVIEW';

  return (
    <div className="auth-standalone-page">
      <section className="auth-standalone-panel">
        <div className="auth-signup-pending-icon" aria-hidden="true">
          ⏳
        </div>
        <h1 className="auth-standalone-title">가입 신청이 접수되었어요</h1>

        <SignupStatusStepper status={currentStatus} />

        {isError && (
          <p className="auth-form-error">가입 상태를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.</p>
        )}

        {currentStatus === 'REJECTED' && status?.rejectionReason && (
          <p className="auth-form-error">반려 사유: {status.rejectionReason}</p>
        )}

        <dl className="auth-signup-summary">
          <dt>상호명</dt>
          <dd>{companyName}</dd>
          <dt>이메일</dt>
          <dd>{maskedEmail}</dd>
        </dl>

        <button
          type="button"
          className="company-login-submit-btn"
          onClick={() => refetch()}
          disabled={isRefetching}
        >
          {isRefetching ? '확인 중...' : '가입 상태 새로고침'}
        </button>

        <button
          type="button"
          className="auth-link-btn"
          onClick={() => window.alert(SUPPORT_CONTACT_MESSAGE)}
        >
          문의하기
        </button>

        <p className="auth-panel-guide">
          <Link to="/login">로그인으로</Link>
        </p>
      </section>
    </div>
  );
}
