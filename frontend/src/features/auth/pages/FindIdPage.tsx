import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { AuthGlassPanel } from '../components/AuthGlassPanel';
import { FIND_PASSWORD_ROUTE, LOGIN_ROUTE } from '../constants';
import { ERROR_CLASSES, LABEL_CLASSES, LOGIN_INPUT_CLASSES } from '../formClasses';
import { useCsrfPrime } from '../hooks/useCsrfPrime';
import { useFindLoginId } from '../hooks/useFindLoginId';
import { isFindIdFormValid } from '../utils/validateFindIdForm';

// 계정 열거 방지(계약 공통 규약) — 무매칭 실패 메시지 통일
const ERROR_MESSAGES: Record<string, string> = {
  AUTH_ACCOUNT_NOT_FOUND: '일치하는 계정을 찾을 수 없습니다.',
};
const DEFAULT_ERROR_MESSAGE = '아이디 찾기에 실패했습니다. 잠시 후 다시 시도해 주세요.';

// 레거시 auth.css 클래스(auth-standalone-*·auth-form-*·company-login-submit-btn)에서 다른 계정 찾기
// 화면과 동일한 Tailwind 토큰으로 전환(#906) — 셸은 AuthGlassPanel이 맡는다. auth.css 파일 자체는
// CompanySignupPendingPage가 아직 쓰므로 남겨둔다(이 화면 전용이던 규칙은 사용처가 사라져 dead가
// 되지만, 그 정리는 해당 화면 전환과 함께 하는 편이 안전하다).
export function FindIdPage() {
  useCsrfPrime();

  const [businessRegistrationNumber, setBusinessRegistrationNumber] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [representativeName, setRepresentativeName] = useState('');
  const [showValidation, setShowValidation] = useState(false);

  const { findLoginId, isPending, result, error } = useFindLoginId();

  const isFormValid = isFindIdFormValid(businessRegistrationNumber, companyName, representativeName);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);
    if (!isFormValid) return;

    findLoginId({
      businessRegistrationNumber,
      companyName: companyName.trim(),
      representativeName: representativeName.trim(),
    });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <AuthGlassPanel titleId="find-id-title">
      <h1 id="find-id-title" className="mt-10 text-center text-xl font-semibold text-zinc-900">
        기업 아이디 찾기
      </h1>

      {result ? (
        <div className="mt-4 flex flex-col items-center gap-2">
          <p className="m-0 text-center text-sm text-zinc-500">찾으시는 아이디는 다음과 같습니다.</p>
          <p className="m-0 text-center text-base font-semibold text-zinc-900">
            {result.maskedEmail}
          </p>
        </div>
      ) : (
        <form className="mt-4 flex flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1.5">
            <label className={LABEL_CLASSES} htmlFor="find-id-business-number">
              사업자등록번호
            </label>
            <input
              id="find-id-business-number"
              type="text"
              className={LOGIN_INPUT_CLASSES}
              value={businessRegistrationNumber}
              onChange={(event) => setBusinessRegistrationNumber(event.target.value)}
              placeholder="'-' 제외 10자리"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className={LABEL_CLASSES} htmlFor="find-id-company-name">
              상호명
            </label>
            <input
              id="find-id-company-name"
              type="text"
              className={LOGIN_INPUT_CLASSES}
              value={companyName}
              onChange={(event) => setCompanyName(event.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className={LABEL_CLASSES} htmlFor="find-id-representative-name">
              대표자명
            </label>
            <input
              id="find-id-representative-name"
              type="text"
              className={LOGIN_INPUT_CLASSES}
              value={representativeName}
              onChange={(event) => setRepresentativeName(event.target.value)}
            />
          </div>

          {showValidation && !isFormValid && (
            <p className={ERROR_CLASSES}>사업자등록번호와 상호명(또는 대표자명)을 입력해 주세요.</p>
          )}
          {errorMessage && (
            <p role="alert" className={ERROR_CLASSES}>
              {errorMessage}
            </p>
          )}

          <Button type="submit" size="lg" className="w-full" disabled={isPending}>
            {isPending ? '확인 중...' : '아이디 확인'}
          </Button>
        </form>
      )}

      <div className="mt-6 flex items-center justify-center gap-3 text-sm">
        <Link to={LOGIN_ROUTE} className="font-medium text-zinc-900 underline">
          로그인으로
        </Link>
        <span aria-hidden="true" className="text-zinc-300">
          |
        </span>
        <Link to={FIND_PASSWORD_ROUTE} className="font-medium text-zinc-900 underline">
          비밀번호 찾기
        </Link>
      </div>
    </AuthGlassPanel>
  );
}
