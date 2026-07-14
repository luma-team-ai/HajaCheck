import { Link } from 'react-router-dom';
import { SignupStatusStepper } from '../components/SignupStatusStepper';
import { COMPANY_SIGNUP_ROUTE, LOGIN_ROUTE } from '../constants';
import { useSignupStatus } from '../hooks/useSignupStatus';
import { getCompanySignupSession } from '../utils/companySignupSession';
import '../auth.css';

const SUPPORT_CONTACT_MESSAGE = '문의: support@hajacheck.com (준비 중인 기능입니다)';

// 가입 승인 대기 화면 — signupToken·표시정보는 sessionStorage(useCompanySignup에서 저장)에서
// 읽는다. URL 쿼리스트링에 opaque 토큰을 노출하지 않기 위함(PR머신 P3) — 새로고침해도 복원되고
// 탭을 닫으면 자동 소거된다.
export function CompanySignupPendingPage() {
  const session = getCompanySignupSession();

  const { status, refetch, isRefetching, isError } = useSignupStatus(session?.signupToken ?? null);

  if (!session) {
    return (
      <div className="auth-standalone-page">
        <section className="auth-standalone-panel">
          <p className="auth-form-error">잘못된 접근입니다. 회원가입을 다시 진행해 주세요.</p>
          <Link to={COMPANY_SIGNUP_ROUTE} className="auth-link-btn">
            회원가입으로
          </Link>
        </section>
      </div>
    );
  }

  const companyName = status?.companyName ?? session.companyName;
  const maskedEmail = session.maskedEmail;
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
          <Link to={LOGIN_ROUTE}>로그인으로</Link>
        </p>
      </section>
    </div>
  );
}
