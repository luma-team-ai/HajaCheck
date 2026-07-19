import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { PasswordStrengthMeter } from '../components/PasswordStrengthMeter';
import { FIND_PASSWORD_ROUTE, LOGIN_ROUTE } from '../constants';
import {
  ERROR_CLASSES,
  LABEL_CLASSES,
  LOGIN_INPUT_CLASSES,
  PASSWORD_TOGGLE_CLASSES,
} from '../formClasses';
import { useCsrfPrime } from '../hooks/useCsrfPrime';
import { useNoReferrer } from '../hooks/useNoReferrer';
import { usePasswordReset } from '../hooks/usePasswordReset';
import { doPasswordsMatch, getPasswordStrength, isValidPassword } from '../utils/authFormValidators';
import { isResetPasswordFormValid } from '../utils/validateResetPasswordForm';

// 토큰 무효·만료·사용됨 3가지는 계약상 메시지를 통일한다(어느 쪽인지 노출 금지)
const TOKEN_INVALID_MESSAGE = '링크가 만료되었거나 이미 사용되었습니다.';
const INVALID_INPUT_MESSAGE = '입력값을 다시 확인해 주세요.';
const DEFAULT_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_RESET_TOKEN_INVALID: TOKEN_INVALID_MESSAGE,
  INVALID_INPUT: INVALID_INPUT_MESSAGE,
};

// 비밀번호 찾기 2단계 — #301(HAJA-224). 토큰이 URL 쿼리(?token=)에 실리므로 계약 "프론트 보안
// 규약"을 그대로 따른다: useNoReferrer(Referrer-Policy: no-referrer) + 토큰 소비 후 URL에서 제거.
export function ResetPasswordPage() {
  useCsrfPrime();
  useNoReferrer();

  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);
  const [isConfirmPasswordVisible, setIsConfirmPasswordVisible] = useState(false);
  const [showValidation, setShowValidation] = useState(false);

  const { resetPassword, isPending, isSuccess, error } = usePasswordReset();

  const isFormValid = isResetPasswordFormValid(newPassword, confirmPassword);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);
    if (!isFormValid || !token) return;

    resetPassword(
      { token, newPassword },
      {
        // 토큰 소비 후 URL에서 제거 — 브라우저 히스토리·URL 공유로 인한 토큰 유출 방지(계약 고정
        // 요건). 성공이든 AUTH_RESET_TOKEN_INVALID(이미 무효화됨)든 더 이상 이 토큰을 주소창에
        // 노출할 이유가 없다 — react-router 상태(위 token 변수)는 그대로 유지해 화면 분기는 정상 동작.
        onSettled: () => {
          window.history.replaceState(null, '', window.location.pathname);
        },
      },
    );
  };

  if (!token) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-muted p-6">
        <div className="w-full max-w-[440px] rounded-[20px] border border-border bg-surface p-10 text-center shadow-sm">
          <h1 className="m-0 text-2xl font-bold text-heading">유효하지 않은 접근입니다</h1>
          <p className="mt-3 text-sm text-text-muted">
            비밀번호 재설정 링크가 없거나 올바르지 않습니다. 다시 요청해 주세요.
          </p>
          <Link
            to={FIND_PASSWORD_ROUTE}
            className="mt-6 inline-block text-sm font-medium text-heading underline"
          >
            비밀번호 찾기로
          </Link>
        </div>
      </div>
    );
  }

  const isTokenInvalid = error?.code === 'AUTH_RESET_TOKEN_INVALID';
  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted p-6">
      <div className="w-full max-w-[440px] rounded-[20px] border border-border bg-surface p-10 shadow-sm">
        <h1 className="m-0 text-2xl font-bold text-heading">새 비밀번호 설정</h1>

        {isSuccess ? (
          <div className="mt-6 flex flex-col gap-4">
            <p className="m-0 text-sm text-text-default">비밀번호가 변경되었습니다.</p>
            <Link to={LOGIN_ROUTE} className="text-sm font-medium text-heading underline">
              로그인으로
            </Link>
          </div>
        ) : isTokenInvalid ? (
          <div className="mt-6 flex flex-col gap-4">
            <p className={ERROR_CLASSES}>{TOKEN_INVALID_MESSAGE}</p>
            <Link to={FIND_PASSWORD_ROUTE} className="text-sm font-medium text-heading underline">
              비밀번호 찾기 다시 요청하기
            </Link>
          </div>
        ) : (
          <form className="mt-6 flex flex-col gap-5" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="reset-password-new">
                새 비밀번호
              </label>
              <div className="relative flex items-center">
                <input
                  id="reset-password-new"
                  type={isPasswordVisible ? 'text' : 'password'}
                  className={`${LOGIN_INPUT_CLASSES} pr-11`}
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  autoComplete="new-password"
                  placeholder="8자 이상, 영문+숫자 포함"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsPasswordVisible((prev) => !prev)}
                >
                  {isPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              <PasswordStrengthMeter strength={getPasswordStrength(newPassword)} />
              {showValidation && !isValidPassword(newPassword) && (
                <p className={ERROR_CLASSES}>8자 이상, 영문+숫자를 포함해 주세요.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="reset-password-confirm">
                새 비밀번호 확인
              </label>
              <div className="relative flex items-center">
                <input
                  id="reset-password-confirm"
                  type={isConfirmPasswordVisible ? 'text' : 'password'}
                  className={`${LOGIN_INPUT_CLASSES} pr-11`}
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  placeholder="비밀번호를 다시 입력해 주세요"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isConfirmPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsConfirmPasswordVisible((prev) => !prev)}
                >
                  {isConfirmPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              {showValidation && confirmPassword.length > 0 && !doPasswordsMatch(newPassword, confirmPassword) && (
                <p className={ERROR_CLASSES}>비밀번호가 일치하지 않습니다.</p>
              )}
            </div>

            {errorMessage && <p className={ERROR_CLASSES}>{errorMessage}</p>}

            <Button type="submit" size="lg" className="w-full" disabled={isPending}>
              {isPending ? '변경 중...' : '변경'}
            </Button>
          </form>
        )}
      </div>
    </div>
  );
}
