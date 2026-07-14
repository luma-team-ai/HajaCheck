import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { PasswordStrengthMeter } from '../components/PasswordStrengthMeter';
import { usePasswordReset } from '../hooks/usePasswordReset';
import { doPasswordsMatch, isValidPassword } from '../utils/authFormValidators';
import { isResetPasswordFormValid } from '../utils/validateResetPasswordForm';
import '../auth.css';

interface ResetPasswordLocationState {
  resetToken?: string;
  maskedEmail?: string;
}

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_RESET_TOKEN_INVALID: '재설정 링크가 만료되었거나 유효하지 않습니다. 처음부터 다시 시도해 주세요.',
};
const DEFAULT_ERROR_MESSAGE = '비밀번호 변경에 실패했습니다. 잠시 후 다시 시도해 주세요.';

export function ResetPasswordPage() {
  const location = useLocation();
  const { resetToken, maskedEmail } = (location.state ?? {}) as ResetPasswordLocationState;

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showValidation, setShowValidation] = useState(false);

  const { resetPassword, isPending, error } = usePasswordReset();

  if (!resetToken) {
    return (
      <div className="auth-standalone-page">
        <section className="auth-standalone-panel">
          <p className="auth-form-error">잘못된 접근입니다. 비밀번호 찾기를 다시 진행해 주세요.</p>
          <Link to="/find-password" className="auth-link-btn">
            비밀번호 찾기로
          </Link>
        </section>
      </div>
    );
  }

  const isFormValid = isResetPasswordFormValid(newPassword, confirmPassword);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);
    if (!isFormValid) return;

    resetPassword({ resetToken, newPassword });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <div className="auth-standalone-page">
      <section className="auth-standalone-panel">
        <h1 className="auth-standalone-title">새 비밀번호 설정</h1>

        <p className="auth-verified-badge">
          ✓ 기업 정보 인증 완료{maskedEmail ? ` (${maskedEmail})` : ''}
        </p>

        <form onSubmit={handleSubmit}>
          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="reset-new-password">
              새 비밀번호
            </label>
            <input
              id="reset-new-password"
              type="password"
              className="auth-form-input"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              autoComplete="new-password"
            />
            <PasswordStrengthMeter password={newPassword} />
            {showValidation && !isValidPassword(newPassword) && (
              <p className="auth-form-error">8자 이상, 영문+숫자를 포함해 주세요.</p>
            )}
          </div>

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="reset-confirm-password">
              새 비밀번호 확인
            </label>
            <input
              id="reset-confirm-password"
              type="password"
              className="auth-form-input"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              autoComplete="new-password"
            />
            {confirmPassword && (
              <p className={doPasswordsMatch(newPassword, confirmPassword) ? 'auth-form-success' : 'auth-form-error'}>
                {doPasswordsMatch(newPassword, confirmPassword) ? '비밀번호가 일치합니다.' : '비밀번호가 일치하지 않습니다.'}
              </p>
            )}
          </div>

          {errorMessage && <p className="auth-form-error">{errorMessage}</p>}

          <button type="submit" className="company-login-submit-btn" disabled={isPending}>
            {isPending ? '변경 중...' : '비밀번호 변경 완료'}
          </button>
        </form>

        <p className="auth-panel-guide">
          <Link to="/login">로그인으로</Link>
        </p>
      </section>
    </div>
  );
}
