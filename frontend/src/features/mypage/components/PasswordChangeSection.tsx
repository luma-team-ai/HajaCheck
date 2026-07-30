import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../../shared/components/Button/Button';
import { getApiErrorMessage } from '../../../shared/api/types';
import { PasswordStrengthMeter } from '../../auth/components/PasswordStrengthMeter';
import { useLogout } from '../../auth/hooks/useLogout';
import { doPasswordsMatch, getPasswordStrength, isValidPassword } from '../../auth/utils/authFormValidators';
import { ERROR_CLASSES, INPUT_CLASSES, LABEL_CLASSES } from '../formClasses';
import { useChangePassword } from '../hooks/useChangePassword';

// 성공/세션만료 안내를 사용자가 읽을 시간을 준 뒤 /login으로 이동한다(handoff §5) — useLogout()은
// 호출 즉시 세션을 정리하고 이동시키므로, 그 전에 안내 문구가 잠깐이라도 보이게 지연시킨다.
const REDIRECT_DELAY_MS = 1200;

// 백엔드 ErrorCode(#1315)와 1:1 — status만으로는 "현재 비밀번호 불일치"와 "세션 만료·미인증"을
// 구분할 수 없다(둘 다 401). code로 분기한다(보안 리뷰 P2-1 — 메시지 문자열 매칭 금지 컨벤션은
// 표시 문구 비교 금지를 뜻하고, 서버 계약상의 식별자인 code 비교와는 무관하다).
const AUTH_INVALID_CREDENTIALS_CODE = 'AUTH_INVALID_CREDENTIALS';

const CURRENT_PASSWORD_MISMATCH_MESSAGE = '현재 비밀번호가 일치하지 않습니다.';
const SESSION_EXPIRED_MESSAGE = '세션이 만료되었습니다. 다시 로그인해 주세요.';
const RATE_LIMITED_MESSAGE = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
const DEFAULT_BAD_REQUEST_MESSAGE = '요청을 처리할 수 없습니다. 입력 내용을 다시 확인해 주세요.';
const DEFAULT_UNHANDLED_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

// 비밀번호 변경(#1316, HAJA-602) — 마이페이지 "내 정보" 섹션(ProfileSection 아래). BE #1315와 병렬
// 구현이라 계약(handoff docs/_local/handoff/password-change-fe-1316-next.md)이 소스: PATCH
// /api/users/me/password { currentPassword, newPassword }. 비밀번호 정책 검증은
// PasswordStrengthMeter/authFormValidators(auth)를 그대로 재사용한다(중복 금지) — 새 검증 로직을
// 여기서 다시 짜지 않는다.
//
// 에러는 status + code로 분기한다(메시지 문자열 매칭 금지):
// - 401 + code===AUTH_INVALID_CREDENTIALS: 현재 비밀번호 불일치. "현재 비밀번호" 필드 아래 인라인
//   에러로만 보여준다.
// - 401 + 그 외 code: 세션이 실제로 만료된 것(보안 리뷰 P2-1) — axios 인터셉터의 전역 401 하드
//   리다이렉트는 mypageApi.changePassword의 skipAuthRedirect:true로 이 요청 전체에 대해 우회돼
//   있으므로(getMe와 동일 패턴, shared/api/axios.ts 참고), 세션 만료는 여기서 직접 감지해 재로그인
//   시켜야 한다 — 안 그러면 사용자가 맞는 비밀번호를 계속 입력하며 만료된 세션 화면에 갇힌다.
// - 400: 소셜 전용 계정/정책 위반/신·구 동일 — 서버 메시지를 그대로 보여준다(getApiErrorMessage,
//   shared/api/types.ts의 기존 관용구 — 세 원인이 서로 달라 클라이언트가 하나의 고정 문구로
//   뭉뚱그리는 것보다 서버 사유를 그대로 노출하는 편이 사용자에게 더 유용하다).
// - 429: 요청 과다 — "잠시 후 다시 시도" 고정 문구.
// - 그 외(CSRF 403, 계정상태 403, 500, 오프라인 등): 위 네 분기 어디에도 안 걸리는 응답이 화면을
//   무반응으로 만들지 않도록(보안 리뷰 P2-2) else 폴백으로 서버 메시지(또는 기본 문구)를 보여준다.
//
// 성공 시 서버가 현재 세션을 무효화하므로, 안내 노출 후 useLogout()의 클라이언트 정리 순서
// (cancelQueries → setQueryData(null) → clearUser → navigate)를 그대로 태워 /login으로 이동한다.
// 서버 세션은 이미 죽었으므로 useLogout 내부의 authApi.logout() 실패는 무시돼도 무방하다(handoff §5).
// 로그아웃 직전에는 항상 평문 비밀번호가 담긴 폼 state + mutation 캐시(변수)를 함께 정리한다(보안
// 리뷰 P3 — useLogout의 removeQueries는 query 캐시 전용이라 mutation 캐시는 지우지 않는다).
export function PasswordChangeSection() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const { logout } = useLogout();
  const { changePassword, isPending, isSuccess, error, clearSensitiveState } = useChangePassword();

  const isFormValid =
    currentPassword.length > 0 &&
    isValidPassword(newPassword) &&
    doPasswordsMatch(newPassword, confirmPassword);

  const isCurrentPasswordInvalid = error?.status === 401 && error.code === AUTH_INVALID_CREDENTIALS_CODE;
  const isSessionExpired = error?.status === 401 && !isCurrentPasswordInvalid;
  const isRateLimited = error?.status === 429;
  const isBadRequest = error?.status === 400;
  const isUnhandledError =
    Boolean(error) && !isCurrentPasswordInvalid && !isSessionExpired && !isRateLimited && !isBadRequest;

  useEffect(() => {
    if (!isSuccess) return;
    const timer = setTimeout(() => {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      clearSensitiveState();
      void logout();
    }, REDIRECT_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 성공 시점 스냅샷이면 충분(마운트 중 재실행 불필요)
  }, [isSuccess]);

  // 세션이 실제로 만료된 401도 동일하게 안내 후 재로그인시킨다(보안 리뷰 P2-1).
  useEffect(() => {
    if (!isSessionExpired) return;
    const timer = setTimeout(() => {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      clearSensitiveState();
      void logout();
    }, REDIRECT_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 세션 만료 감지 시점 스냅샷이면 충분
  }, [isSessionExpired]);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isFormValid || isPending) return;
    changePassword({ currentPassword, newPassword });
  };

  if (isSuccess) {
    return (
      <section className="flex flex-col gap-2 py-6 first:pt-0 last:pb-0">
        <h3 className="text-xl font-semibold text-heading">비밀번호 변경</h3>
        <p role="status" className="m-0 text-sm text-text-default">
          비밀번호가 변경되었습니다. 다시 로그인해 주세요.
        </p>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <h3 className="text-xl font-semibold text-heading">비밀번호 변경</h3>

      <form className="flex max-w-md flex-col gap-4" onSubmit={handleSubmit} noValidate>
        <div className="flex flex-col gap-1">
          <label htmlFor="password-change-current" className={LABEL_CLASSES}>
            현재 비밀번호
          </label>
          <input
            id="password-change-current"
            type="password"
            className={INPUT_CLASSES}
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
            autoComplete="current-password"
            aria-invalid={isCurrentPasswordInvalid}
          />
          {isCurrentPasswordInvalid && (
            <p role="alert" className={ERROR_CLASSES}>
              {CURRENT_PASSWORD_MISMATCH_MESSAGE}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="password-change-new" className={LABEL_CLASSES}>
            새 비밀번호
          </label>
          <input
            id="password-change-new"
            type="password"
            className={INPUT_CLASSES}
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            autoComplete="new-password"
            placeholder="8자 이상, 영문+숫자 포함"
          />
          <PasswordStrengthMeter strength={getPasswordStrength(newPassword)} />
          {newPassword.length > 0 && !isValidPassword(newPassword) && (
            <p className={ERROR_CLASSES}>8자 이상, 영문+숫자를 포함해 주세요.</p>
          )}
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="password-change-confirm" className={LABEL_CLASSES}>
            새 비밀번호 확인
          </label>
          <input
            id="password-change-confirm"
            type="password"
            className={INPUT_CLASSES}
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            autoComplete="new-password"
          />
          {confirmPassword.length > 0 && !doPasswordsMatch(newPassword, confirmPassword) && (
            <p className={ERROR_CLASSES}>비밀번호가 일치하지 않습니다.</p>
          )}
        </div>

        {isSessionExpired && (
          <p role="alert" className={ERROR_CLASSES}>
            {SESSION_EXPIRED_MESSAGE}
          </p>
        )}
        {isRateLimited && (
          <p role="alert" className={ERROR_CLASSES}>
            {RATE_LIMITED_MESSAGE}
          </p>
        )}
        {isBadRequest && (
          <p role="alert" className={ERROR_CLASSES}>
            {getApiErrorMessage(error, DEFAULT_BAD_REQUEST_MESSAGE)}
          </p>
        )}
        {isUnhandledError && (
          <p role="alert" className={ERROR_CLASSES}>
            {getApiErrorMessage(error, DEFAULT_UNHANDLED_ERROR_MESSAGE)}
          </p>
        )}

        <Button
          type="submit"
          size="md"
          className="w-fit"
          disabled={!isFormValid || isPending || isSessionExpired}
        >
          {isPending ? '변경 중...' : '비밀번호 변경'}
        </Button>
      </form>
    </section>
  );
}
