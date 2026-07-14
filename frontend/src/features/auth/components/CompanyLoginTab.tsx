import { useEffect, useState } from 'react';
import { AuthFooterLinks } from './AuthFooterLinks';
import { useLogin } from '../hooks/useLogin';
import { clearSavedLoginId, getSavedLoginId, setSavedLoginId } from '../utils/savedLoginId';
import { isLoginFormValid } from '../utils/validateLoginForm';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '아이디 또는 비밀번호가 올바르지 않습니다.',
};
const DEFAULT_ERROR_MESSAGE = '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.';

export function CompanyLoginTab() {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);
  const [isSaveIdChecked, setIsSaveIdChecked] = useState(false);
  const { login, isPending, error } = useLogin();

  useEffect(() => {
    const savedLoginId = getSavedLoginId();
    if (savedLoginId) {
      setLoginId(savedLoginId);
      setIsSaveIdChecked(true);
    }
  }, []);

  const handleToggleSaveId = (checked: boolean) => {
    setIsSaveIdChecked(checked);
    if (checked) {
      setSavedLoginId(loginId);
    } else {
      clearSavedLoginId();
    }
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isLoginFormValid(loginId, password)) return;

    if (isSaveIdChecked) {
      setSavedLoginId(loginId);
    }
    login({ loginId, password });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <form className="company-login-tab" onSubmit={handleSubmit}>
      <div className="auth-form-field">
        <label className="auth-form-label" htmlFor="company-login-id">
          아이디
        </label>
        <input
          id="company-login-id"
          type="text"
          className="auth-form-input"
          value={loginId}
          onChange={(event) => setLoginId(event.target.value)}
          autoComplete="username"
        />
      </div>

      <div className="auth-form-field">
        <label className="auth-form-label" htmlFor="company-login-password">
          비밀번호
        </label>
        <div className="auth-password-input-wrap">
          <input
            id="company-login-password"
            type={isPasswordVisible ? 'text' : 'password'}
            className="auth-form-input"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
          <button
            type="button"
            className="auth-password-toggle-btn"
            aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
            onClick={() => setIsPasswordVisible((prev) => !prev)}
          >
            {isPasswordVisible ? '🙈' : '👁'}
          </button>
        </div>
      </div>

      {errorMessage && <p className="auth-form-error">{errorMessage}</p>}

      <label className="auth-save-id-checkbox">
        <input
          type="checkbox"
          checked={isSaveIdChecked}
          onChange={(event) => handleToggleSaveId(event.target.checked)}
        />
        아이디 저장
      </label>

      <button
        type="submit"
        className="company-login-submit-btn"
        disabled={isPending || !isLoginFormValid(loginId, password)}
      >
        {isPending ? '로그인 중...' : '로그인'}
      </button>

      <AuthFooterLinks />
    </form>
  );
}
