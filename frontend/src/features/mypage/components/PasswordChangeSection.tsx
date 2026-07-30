import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../../shared/components/Button/Button';
import { getApiErrorMessage } from '../../../shared/api/types';
import { PasswordStrengthMeter } from '../../auth/components/PasswordStrengthMeter';
import { useLogout } from '../../auth/hooks/useLogout';
import { doPasswordsMatch, getPasswordStrength, isValidPassword } from '../../auth/utils/authFormValidators';
import { ERROR_CLASSES, INPUT_CLASSES, LABEL_CLASSES } from '../formClasses';
import { useChangePassword } from '../hooks/useChangePassword';
import { PASSWORD_CHANGE_ERROR_CODE } from '../types';

// 성공/세션만료 안내를 사용자가 읽을 시간을 준 뒤 /login으로 이동한다(handoff §5) — useLogout()은
// 호출 즉시 세션을 정리하고 이동시키므로, 그 전에 안내 문구가 잠깐이라도 보이게 지연시킨다.
// 테스트(PasswordChangeSection.test.tsx)에서도 재사용하므로 export한다.
export const REDIRECT_DELAY_MS = 1200;

const CURRENT_PASSWORD_MISMATCH_MESSAGE = '현재 비밀번호가 일치하지 않습니다.';
const SESSION_EXPIRED_MESSAGE = '세션이 만료되었습니다. 다시 로그인해 주세요.';
const RATE_LIMITED_MESSAGE = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
const DEFAULT_BAD_REQUEST_MESSAGE = '요청을 처리할 수 없습니다. 입력 내용을 다시 확인해 주세요.';
const DEFAULT_UNHANDLED_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

type CompletionState = 'success' | 'sessionExpired' | null;

// 비밀번호 변경(#1316, HAJA-602) — 마이페이지 "내 정보" 섹션(ProfileSection 아래). BE #1315와 병렬
// 구현이라 계약(handoff docs/_local/handoff/password-change-fe-1316-next.md)이 소스: PATCH
// /api/users/me/password { currentPassword, newPassword }. 비밀번호 정책 검증은
// PasswordStrengthMeter/authFormValidators(auth)를 그대로 재사용한다(중복 금지) — 새 검증 로직을
// 여기서 다시 짜지 않는다.
//
// 에러는 status + code로 분기한다(메시지 문자열 매칭 금지). code는 types.ts의
// PASSWORD_CHANGE_ERROR_CODE(실 백엔드 ErrorCode(#1315) 확인 완료 — RestAuthenticationEntryPoint→
// UNAUTHORIZED, PasswordChangeService:90→AUTH_INVALID_CREDENTIALS)를 참조한다:
// - 401 + code===SESSION_EXPIRED('UNAUTHORIZED'): 세션이 실제로 만료된 것 — 이 코드일 때만
//   화이트리스트로 로그아웃시킨다(보안 리뷰 P2-2). "모르는 401은 전부 세션 만료"로 취급하면 가장
//   흔한 정상 재시도(현재 비밀번호 오타)까지 강제 로그아웃으로 이어질 수 있어 반대 방향이 안전하다.
// - 401 + 그 외(코드가 SESSION_EXPIRED가 아닌 모든 경우, INVALID_CREDENTIALS 포함): 현재 비밀번호
//   불일치로 취급해 "현재 비밀번호" 필드 아래 인라인 에러로만 보여준다(로그아웃하지 않음). axios
//   인터셉터의 전역 401 하드 리다이렉트는 mypageApi.changePassword의 skipAuthRedirect:true로 이
//   요청 전체에 대해 우회돼 있다(getMe와 동일 패턴, shared/api/axios.ts 참고) — 그래서 세션 만료
//   판정은 여기서 직접 해야 한다.
// - 400: 소셜 전용 계정/정책 위반/신·구 동일 — 서버 메시지를 그대로 보여준다(getApiErrorMessage,
//   shared/api/types.ts의 기존 관용구 — 세 원인이 서로 달라 클라이언트가 하나의 고정 문구로
//   뭉뚱그리는 것보다 서버 사유를 그대로 노출하는 편이 사용자에게 더 유용하다).
// - 429: 요청 과다 — "잠시 후 다시 시도" 고정 문구.
// - 그 외(CSRF 403, 계정상태 403, 500, 오프라인 등): 위 분기 어디에도 안 걸리는 응답이 화면을
//   무반응으로 만들지 않도록(보안 리뷰 P2-2) else 폴백으로 서버 메시지(또는 기본 문구)를 보여준다.
//
// 완료 화면(성공/세션만료)은 라이브 mutation 상태(isSuccess/error)가 아니라 로컬 `completed`
// 플래그로 구동한다(보안 리뷰 P2-1) — 리다이렉트 타이머 콜백이 clearSensitiveState()로
// mutation.reset()을 호출하면 라이브 isSuccess/error는 즉시 idle로 뒤집히는데, logout()은 그 안의
// authApi.logout() 네트워크 왕복을 기다린 뒤에야 navigate하므로, 그 사이 완료 화면이 사라지고 빈
// 폼이 재노출되는 깜빡임이 생긴다. `completed`는 한 번 세팅되면 reset()과 무관하게 유지되므로 이
// 문제가 없다. 성공/세션만료 두 경로가 동일한 "지연 후 정리+로그아웃" 절차를 공유하므로 단일
// useEffect로 합쳤다(P3-1).
//
// 사용자가 화면에 머무는 에러 경로(401 현재비번불일치/400/429/500)에서도 에러가 settle되는 즉시
// removeCachedVariables()로 MutationCache의 평문 variables를 제거한다(보안 리뷰 P3-2) —
// clearSensitiveState()(mutation.reset() 포함)와 달리 화면에 노출 중인 에러 메시지는 지우지 않는다.
//
// 성공 안내(REDIRECT_DELAY_MS) 노출 중 SideNavBar 등으로 화면을 벗어나 언마운트되면, 위 리다이렉트
// useEffect의 cleanup이 setTimeout을 취소해 clearSensitiveState()가 발화하지 않는다 — 그래서
// 마운트 스코프 cleanup에서 removeCachedVariables()를 한 번 더 호출한다(PR머신 재검수 P3).
export function PasswordChangeSection() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [completed, setCompleted] = useState<CompletionState>(null);

  const { logout } = useLogout();
  const { changePassword, isPending, isSuccess, error, clearSensitiveState, removeCachedVariables } =
    useChangePassword();

  const isFormValid =
    currentPassword.length > 0 &&
    isValidPassword(newPassword) &&
    doPasswordsMatch(newPassword, confirmPassword);

  const isSessionExpired = error?.status === 401 && error.code === PASSWORD_CHANGE_ERROR_CODE.SESSION_EXPIRED;
  const isCurrentPasswordInvalid = error?.status === 401 && !isSessionExpired;
  const isRateLimited = error?.status === 429;
  const isBadRequest = error?.status === 400;
  const isUnhandledError =
    Boolean(error) && !isCurrentPasswordInvalid && !isSessionExpired && !isRateLimited && !isBadRequest;

  // 완료 상태를 한 번 래치한다 — isSuccess/isSessionExpired는 이후 clearSensitiveState()의
  // mutation.reset()으로 되돌아가지만 completed는 유지된다(P2-1).
  useEffect(() => {
    if (isSuccess) setCompleted('success');
  }, [isSuccess]);
  useEffect(() => {
    if (isSessionExpired) setCompleted('sessionExpired');
  }, [isSessionExpired]);

  // 사용자가 화면에 머무는 에러(현재비번불일치/400/429/미분류)가 발생하면, 에러 메시지는 그대로
  // 둔 채 MutationCache의 평문 variables만 제거한다(P3-2). completed 경로는 아래 리다이렉트
  // useEffect가 clearSensitiveState()로 이미 처리하므로 여기서는 건너뛴다.
  useEffect(() => {
    if (!error || completed) return;
    removeCachedVariables();
  }, [error, completed, removeCachedVariables]);

  // 성공/세션만료 공통 리다이렉트(P3-1로 단일화) — 안내를 잠시 보여준 뒤 폼/평문/mutation 상태를
  // 정리하고 useLogout()의 클라이언트 정리 순서(cancelQueries → setQueryData(null) → clearUser →
  // navigate)를 그대로 태워 /login으로 이동한다. 서버 세션은 이미 죽었으므로 useLogout 내부의
  // authApi.logout() 실패는 무시돼도 무방하다(handoff §5).
  useEffect(() => {
    if (!completed) return;
    const timer = setTimeout(() => {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      clearSensitiveState();
      void logout();
    }, REDIRECT_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- completed 세팅 시점 스냅샷이면 충분(마운트 중 재실행 불필요)
  }, [completed]);

  // 언마운트 시 평문 정리(PR머신 재검수 P3) — 성공 안내(REDIRECT_DELAY_MS) 노출 중 SideNavBar 등으로
  // 화면을 벗어나면 위 리다이렉트 useEffect의 cleanup이 setTimeout을 clearTimeout으로 취소해
  // clearSensitiveState()가 발화하지 않는다. removeCachedVariables()는 mutation.reset()을 부르지
  // 않아 화면 상태(에러 메시지 등)를 건드리지 않으므로, 정상 발화 경로와 별개로 마운트 스코프
  // cleanup에서도 안전하게 한 번 더 호출해 MutationCache에 평문 variables가 gcTime(5분) 동안
  // 남지 않게 한다.
  useEffect(() => () => removeCachedVariables(), [removeCachedVariables]);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isFormValid || isPending) return;
    changePassword({ currentPassword, newPassword });
  };

  if (completed === 'success') {
    return (
      <section className="flex flex-col gap-2 py-6 first:pt-0 last:pb-0">
        <h3 className="text-xl font-semibold text-heading">비밀번호 변경</h3>
        <p role="status" className="m-0 text-sm text-text-default">
          비밀번호가 변경되었습니다. 다시 로그인해 주세요.
        </p>
      </section>
    );
  }

  if (completed === 'sessionExpired') {
    return (
      <section className="flex flex-col gap-2 py-6 first:pt-0 last:pb-0">
        <h3 className="text-xl font-semibold text-heading">비밀번호 변경</h3>
        <p role="alert" className="m-0 text-sm text-danger">
          {SESSION_EXPIRED_MESSAGE}
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

        <Button type="submit" size="md" className="w-fit" disabled={!isFormValid || isPending}>
          {isPending ? '변경 중...' : '비밀번호 변경'}
        </Button>
      </form>
    </section>
  );
}
