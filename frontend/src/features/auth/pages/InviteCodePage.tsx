import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { DASHBOARD_ROUTE } from '../../../shared/constants/routes';
import { InviteCodeInput } from '../components/InviteCodeInput';
import { LANDING_ROUTE } from '../constants';
import { useLogout } from '../hooks/useLogout';
import { useRedeemInviteCode } from '../hooks/useRedeemInviteCode';
import { useAuthStore } from '../store/authStore';

const INVITE_CODE_LENGTH = 6;

// 초대 코드 입력 화면(#799, Figma "hajaCheck 초대 코드 입력 페이지") — 소셜 최초 로그인 시
// status=WAITING(company_id 없음)인 사용자가 기업 관리자에게 발급받은 초대 코드로 회사에
// 연결하는 화면. 검증/연결은 #794 backend(PR #801) POST /users/me/invite-code에 연동한다.
export function InviteCodePage() {
  const user = useAuthStore((state) => state.user);
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const { redeemInviteCode, isPending, isSuccess, error, reset } = useRedeemInviteCode();
  // 다른 계정으로 로그인(#799 후속) — WAITING 세션은 "취소"로 랜딩에 가도 로그아웃되지 않아,
  // 다시 로그인해도 이 화면으로 튕겨 돌아온다(같은 세션이 살아있으므로 의도된 동작). 다른
  // 계정으로 시도하려면 명시적 로그아웃이 필요해 별도 버튼으로 노출한다.
  const { logout } = useLogout();

  const isComplete = code.length === INVITE_CODE_LENGTH;
  const errorMessage = localError ?? error?.message ?? null;

  // redeem 성공(status=ACTIVE 전환) 직후 대시보드로 이동 — ProtectedRoute의 WAITING 리다이렉트가
  // authStore.user 갱신(useRedeemInviteCode의 onSuccess)을 이미 반영한 상태라 되돌아오지 않는다.
  useEffect(() => {
    if (isSuccess) {
      navigate(DASHBOARD_ROUTE, { replace: true });
    }
  }, [isSuccess, navigate]);

  // 이미 회사에 연결된(WAITING이 아닌) 사용자가 URL로 직접 들어오면 다시 redeem할 이유가 없다.
  if (user && user.status !== 'WAITING') {
    return <Navigate to={DASHBOARD_ROUTE} replace />;
  }

  const handleChange = (next: string) => {
    setCode(next);
    if (localError) setLocalError(null);
    if (error) reset();
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isComplete) {
      setLocalError('6자리 초대 코드를 모두 입력해 주세요.');
      return;
    }
    redeemInviteCode({ code });
  };

  return (
    <main className="relative flex min-h-screen w-full items-center justify-center bg-[#fafafa] px-6 py-16">
      <Link
        to={LANDING_ROUTE}
        className="absolute left-8 top-10 inline-flex items-center gap-2 rounded-sm text-sm text-[#47464b] transition-colors hover:text-zinc-900"
        aria-label="hajaCheck 홈으로"
      >
        <span aria-hidden="true">←</span>
        hajaCheck 홈으로
      </Link>

      <section
        aria-labelledby="invite-code-title"
        className="relative w-full max-w-[440px] rounded-[20px] border border-white bg-white/70 p-8 shadow-[inset_0px_1px_0px_1px_#ffffff,0px_4px_24px_-4px_#0000000d] backdrop-blur-[10px]"
      >
        <header className="flex items-center justify-center">
          <Link to={LANDING_ROUTE} aria-label="HajaCheck 홈으로" className="inline-flex rounded-sm">
            <img src={brandLogo} alt="HajaCheck" className="h-7 w-auto object-contain" />
          </Link>
        </header>

        <div className="mt-10 flex justify-center" aria-hidden="true">
          {/* 사람 + 열쇠 아이콘 — 디자인팀 제공 원본(Visual Header.svg) 그대로 인라인. 배경(#F1EDED)까지
              아이콘에 포함돼 있어 별도 래퍼 배경을 씌우지 않는다. */}
          <svg width="64" height="64" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect width="64" height="64" rx="32" fill="#F1EDED" />
            <path
              d="M19.3334 40.6666V36.9333C19.3334 36.1777 19.5278 35.4833 19.9167 34.8499C20.3056 34.2166 20.8223 33.7333 21.4667 33.3999C22.8445 32.711 24.2445 32.1944 25.6667 31.8499C27.0889 31.5055 28.5334 31.3333 30 31.3333C30.4445 31.3333 30.8889 31.3499 31.3334 31.3833C31.7778 31.4166 32.2223 31.4666 32.6667 31.5333C32.5778 32.8221 32.8112 34.0388 33.3667 35.1833C33.9223 36.3277 34.7334 37.2666 35.8 37.9999V40.6666H19.3334ZM40.6667 44.6666L38.6667 42.6666V36.4666C37.6889 36.1777 36.8889 35.6277 36.2667 34.8166C35.6445 34.0055 35.3334 33.0666 35.3334 31.9999C35.3334 30.711 35.7889 29.611 36.7 28.6999C37.6112 27.7888 38.7112 27.3333 40 27.3333C41.2889 27.3333 42.3889 27.7888 43.3 28.6999C44.2112 29.611 44.6667 30.711 44.6667 31.9999C44.6667 32.9999 44.3834 33.8888 43.8167 34.6666C43.25 35.4444 42.5334 35.9999 41.6667 36.3333L43.3334 37.9999L41.3334 39.9999L43.3334 41.9999L40.6667 44.6666ZM30 29.9999C28.5334 29.9999 27.2778 29.4777 26.2334 28.4333C25.1889 27.3888 24.6667 26.1333 24.6667 24.6666C24.6667 23.1999 25.1889 21.9444 26.2334 20.8999C27.2778 19.8555 28.5334 19.3333 30 19.3333C31.4667 19.3333 32.7223 19.8555 33.7667 20.8999C34.8112 21.9444 35.3334 23.1999 35.3334 24.6666C35.3334 26.1333 34.8112 27.3888 33.7667 28.4333C32.7223 29.4777 31.4667 29.9999 30 29.9999ZM40 32.6666C40.3778 32.6666 40.6945 32.5388 40.95 32.2833C41.2056 32.0277 41.3334 31.711 41.3334 31.3333C41.3334 30.9555 41.2056 30.6388 40.95 30.3833C40.6945 30.1277 40.3778 29.9999 40 29.9999C39.6223 29.9999 39.3056 30.1277 39.05 30.3833C38.7945 30.6388 38.6667 30.9555 38.6667 31.3333C38.6667 31.711 38.7945 32.0277 39.05 32.2833C39.3056 32.5388 39.6223 32.6666 40 32.6666Z"
              fill="black"
            />
          </svg>
        </div>

        <h1
          id="invite-code-title"
          className="mt-6 text-center text-xl font-semibold text-zinc-900"
        >
          초대 코드 입력
        </h1>
        <p className="mt-2 text-center text-sm text-zinc-500">
          발급받은 6자리 초대 코드를 입력해 주세요.
        </p>

        {/* <form>으로 감싸 6자리를 다 채운 뒤 Enter로도 제출 가능하게 한다(#817 P3) — 별도 키 핸들러
            없이 네이티브 form submit(Enter)을 그대로 활용한다. */}
        <form onSubmit={handleSubmit}>
          <div className="mt-8">
            <InviteCodeInput
              defaultValue={code}
              onChange={handleChange}
              hasError={!!errorMessage}
              disabled={isPending}
            />
          </div>

          <p
            role={errorMessage ? 'alert' : undefined}
            className="mt-4 min-h-[20px] text-center text-sm text-red-500"
          >
            {errorMessage}
          </p>

          <div className="mt-4 flex gap-3">
            <Link
              to={LANDING_ROUTE}
              className="flex h-[50px] flex-1 items-center justify-center rounded-full border border-zinc-300 text-[15px] font-medium text-zinc-700 transition-colors hover:bg-zinc-50"
            >
              취소
            </Link>
            <button
              type="submit"
              disabled={!isComplete || isPending}
              className="flex h-[50px] flex-1 cursor-pointer items-center justify-center gap-1.5 rounded-full bg-zinc-900 text-[15px] font-medium text-white transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {isPending ? '확인 중...' : '확인'}
              {!isPending && <span aria-hidden="true">→</span>}
            </button>
          </div>
        </form>

        <button
          type="button"
          onClick={() => logout()}
          className="mt-4 w-full cursor-pointer text-center text-sm text-zinc-500 underline-offset-2 hover:text-zinc-900 hover:underline"
        >
          다른 계정으로 로그인
        </button>
      </section>
    </main>
  );
}
