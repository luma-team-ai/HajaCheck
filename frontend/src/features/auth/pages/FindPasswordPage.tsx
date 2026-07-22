import { useState } from 'react';
import { Link } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { Button } from '../../../shared/components/Button';
import { LANDING_ROUTE, LOGIN_ROUTE } from '../constants';
import { ERROR_CLASSES, LABEL_CLASSES, LOGIN_INPUT_CLASSES } from '../formClasses';
import { useCsrfPrime } from '../hooks/useCsrfPrime';
import { usePasswordResetRequest } from '../hooks/usePasswordResetRequest';
import { isValidEmail } from '../utils/authFormValidators';

// 계정 열거 방지(계약 공통 규약) — 백엔드가 계정 존재 여부와 무관하게 항상 동일 200 응답을 주므로,
// 프론트도 절대 존재/미존재로 화면을 가르지 않는다. 429(rate-limit)만 별도 안내한다.
const RATE_LIMIT_MESSAGE = '잠시 후 다시 시도해 주세요.';
const DEFAULT_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const SUCCESS_MESSAGE = '입력하신 주소로 재설정 링크를 보냈습니다. 메일함을 확인해 주세요.';

export function FindPasswordPage() {
  useCsrfPrime();

  const [email, setEmail] = useState('');
  const [showValidation, setShowValidation] = useState(false);

  const { requestPasswordReset, isPending, isSuccess, error } = usePasswordResetRequest();

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);
    if (!isValidEmail(email)) return;

    requestPasswordReset({ email: email.trim() });
  };

  // ⚠️ error.code로 분기하는 건 429(rate-limit)뿐이다 — 그 외에는 전부 동일한 일반 오류 문구를
  // 쓴다. 계정 존재 여부에 따른 백엔드 에러가 애초에 없으므로(항상 200) 여기서 새로 만들어내지 않는다.
  const errorMessage = error
    ? error.code === 'AUTH_TOO_MANY_REQUESTS'
      ? RATE_LIMIT_MESSAGE
      : DEFAULT_ERROR_MESSAGE
    : null;

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted p-6">
      <div className="w-full max-w-[440px] rounded-[20px] border border-border bg-surface p-10 shadow-sm">
        <Link
          to={LANDING_ROUTE}
          className="mb-6 flex justify-center"
          aria-label="HajaCheck 홈으로"
        >
          <img src={brandLogo} alt="HajaCheck" className="h-8 w-auto object-contain" />
        </Link>
        <h1 className="m-0 text-2xl font-bold text-heading">비밀번호 찾기</h1>

        {isSuccess ? (
          <div className="mt-6 flex flex-col gap-4">
            <p className="m-0 text-sm text-text-default">{SUCCESS_MESSAGE}</p>
            <Link to={LOGIN_ROUTE} className="text-sm font-medium text-heading underline">
              로그인으로
            </Link>
          </div>
        ) : (
          <form className="mt-6 flex flex-col gap-5" onSubmit={handleSubmit}>
            <p className="m-0 text-sm text-text-muted">
              가입하신 이메일 주소를 입력하시면 비밀번호 재설정 링크를 보내드립니다.
            </p>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="find-password-email">
                이메일
              </label>
              <input
                id="find-password-email"
                type="email"
                className={LOGIN_INPUT_CLASSES}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                placeholder="HajaCheck@check.com"
              />
              {showValidation && !isValidEmail(email) && (
                <p className={ERROR_CLASSES}>올바른 이메일 형식을 입력해 주세요.</p>
              )}
            </div>

            {errorMessage && <p className={ERROR_CLASSES}>{errorMessage}</p>}

            <Button type="submit" size="lg" className="w-full" disabled={isPending}>
              {isPending ? '전송 중...' : '재설정 링크 보내기'}
            </Button>

            <p className="m-0 text-center text-sm text-text-muted">
              <Link to={LOGIN_ROUTE} className="font-medium text-heading underline">
                로그인으로 돌아가기
              </Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
