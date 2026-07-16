import { useEffect, useState } from 'react';
import { AuthFooterLinks } from './AuthFooterLinks';
import { Button } from '../../../shared/components/Button';
import { ERROR_CLASSES, LABEL_CLASSES, LOGIN_INPUT_CLASSES, PASSWORD_TOGGLE_CLASSES } from '../formClasses';
import { useLogin } from '../hooks/useLogin';
import {
  clearSavedLoginId,
  getSavedLoginId,
  resolveSavedLoginId,
  setSavedLoginId,
} from '../utils/savedLoginId';
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
    // 체크했더라도 아이디가 빈 값(공백만 포함)이면 저장할 것이 없으므로 저장소는 정리 상태로 둔다
    const idToSave = resolveSavedLoginId(checked, loginId);
    if (idToSave) {
      setSavedLoginId(idToSave);
    } else {
      clearSavedLoginId();
    }
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isLoginFormValid(loginId, password)) return;

    // 검증(isLoginFormValid)과 동일하게 trim된 값을 저장·전송에 사용 — 원본과 불일치 방지
    const trimmedLoginId = loginId.trim();
    const idToSave = resolveSavedLoginId(isSaveIdChecked, loginId);
    if (idToSave) {
      setSavedLoginId(idToSave);
    }
    login({ loginId: trimmedLoginId, password });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <form className="flex w-full flex-col gap-4" onSubmit={handleSubmit}>
      <div className="flex flex-col gap-1.5">
        <label className={LABEL_CLASSES} htmlFor="company-login-id">
          아이디
        </label>
        {/* LOGIN_INPUT_CLASSES — 로그인 시안(Figma node 53-390)은 회원가입 시안과 실제로 다르게
            그려져 있어(rounded-xl·흰 배경·15px) formClasses.INPUT_CLASSES와 분리된 전용 상수를
            쓴다. 근거는 formClasses.ts LOGIN_INPUT_CLASSES 선언부 주석 참고. */}
        <input
          id="company-login-id"
          type="text"
          className={LOGIN_INPUT_CLASSES}
          value={loginId}
          onChange={(event) => setLoginId(event.target.value)}
          autoComplete="username"
          placeholder="아이디"
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <label className={LABEL_CLASSES} htmlFor="company-login-password">
          비밀번호
        </label>
        <div className="relative flex items-center">
          <input
            id="company-login-password"
            type={isPasswordVisible ? 'text' : 'password'}
            className={`${LOGIN_INPUT_CLASSES} pr-11`}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            placeholder="비밀번호"
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
      </div>

      {errorMessage && <p className={ERROR_CLASSES}>{errorMessage}</p>}

      <label className="flex cursor-pointer items-center gap-2 text-[13px] text-text-default">
        <input
          type="checkbox"
          checked={isSaveIdChecked}
          onChange={(event) => handleToggleSaveId(event.target.checked)}
        />
        아이디 저장
      </label>

      <Button
        type="submit"
        size="lg"
        className="w-full"
        disabled={isPending || !isLoginFormValid(loginId, password)}
      >
        {isPending ? '로그인 중...' : '로그인'}
      </Button>

      <AuthFooterLinks />
    </form>
  );
}
