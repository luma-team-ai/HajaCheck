import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCsrfPrime } from '../hooks/useCsrfPrime';
import { usePasswordInquiry } from '../hooks/usePasswordInquiry';
import { isFindPasswordFormValid } from '../utils/validateFindPasswordForm';
import '../auth.css';

// 계정 열거 방지(계약 공통 규약) — 불일치·미존재 통일 메시지, 401 아님
const ERROR_MESSAGES: Record<string, string> = {
  AUTH_VERIFICATION_FAILED: '입력하신 정보와 일치하는 계정을 찾을 수 없습니다.',
};
const DEFAULT_ERROR_MESSAGE = '비밀번호 찾기에 실패했습니다. 잠시 후 다시 시도해 주세요.';

export function FindPasswordPage() {
  useCsrfPrime();

  const [email, setEmail] = useState('');
  const [businessRegistrationNumber, setBusinessRegistrationNumber] = useState('');
  const [showValidation, setShowValidation] = useState(false);

  const { inquiryPassword, isPending, error } = usePasswordInquiry();

  const isFormValid = isFindPasswordFormValid(email, businessRegistrationNumber);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);
    if (!isFormValid) return;

    inquiryPassword({ email: email.trim(), businessRegistrationNumber });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <div className="auth-standalone-page">
      <section className="auth-standalone-panel">
        <h1 className="auth-standalone-title">비밀번호 찾기</h1>
        <p className="auth-panel-guide">기업 정보를 인증하면 비밀번호를 재설정할 수 있어요.</p>

        <form onSubmit={handleSubmit}>
          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="find-password-email">
              아이디(이메일)
            </label>
            <input
              id="find-password-email"
              type="email"
              className="auth-form-input"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
            />
          </div>

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="find-password-business-number">
              사업자등록번호
            </label>
            <input
              id="find-password-business-number"
              type="text"
              className="auth-form-input"
              value={businessRegistrationNumber}
              onChange={(event) => setBusinessRegistrationNumber(event.target.value)}
              placeholder="000-00-00000"
            />
          </div>

          {showValidation && !isFormValid && (
            <p className="auth-form-error">아이디와 사업자등록번호를 정확히 입력해 주세요.</p>
          )}
          {errorMessage && <p className="auth-form-error">{errorMessage}</p>}

          <button type="submit" className="company-login-submit-btn" disabled={isPending}>
            {isPending ? '확인 중...' : '다음 단계로'}
          </button>
        </form>

        <p className="auth-panel-guide">
          <Link to="/login">로그인으로</Link>
        </p>
      </section>
    </div>
  );
}
