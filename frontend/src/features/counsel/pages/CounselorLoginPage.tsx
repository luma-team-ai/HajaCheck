import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { Button } from '../../../shared/components/Button';
import { COUNSELOR_QUEUE_ROUTE } from '../../../shared/constants/routes';
import { isCounselorRole } from '../../../shared/constants/roles';
import { ERROR_CLASSES, LABEL_CLASSES, LOGIN_INPUT_CLASSES } from '../../auth/formClasses';
import { useCsrfPrime } from '../../auth/hooks/useCsrfPrime';
import { isLoginFormValid } from '../../auth/utils/validateLoginForm';
import { useAuthStore } from '../../auth/store/authStore';
import { useCounselorLogin } from '../hooks/useCounselorLogin';

// 이 화면이 허용하지 않는 role(COUNSELOR 외)로 로그인을 시도한 경우(#1513) — 서버가 403
// AUTH_ROLE_NOT_ALLOWED로 거절하며 세션은 발급되지 않는다(PlatformAdminLoginPage와 동일 규약).
const ROLE_DENIED_MESSAGE = '상담원 계정이 아닙니다.';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '아이디 또는 비밀번호가 올바르지 않습니다.',
  // 포털 role 게이트 거절(#1513) — 매핑이 없으면 DEFAULT_ERROR_MESSAGE로 오안내된다.
  AUTH_ROLE_NOT_ALLOWED: ROLE_DENIED_MESSAGE,
};
const DEFAULT_ERROR_MESSAGE = '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.';

// 상담원 전용 로그인 — PlatformAdminLoginPage와 동일 디자인(#535 패턴, Figma node 973-2520)을
// 그대로 재사용하되, 라벨만 "상담원 로그인"으로 바꾼다. 개인/기업 탭 없는 아이디/비밀번호 단일
// 폼이며, 로그인 요청은 전용 엔드포인트(POST /api/auth/counselor/login)로 보내고 role 강제는
// 서버가 한다(#1513 — useCounselorLogin 주석 참조).
export function CounselorLoginPage() {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const navigate = useNavigate();
  const currentUser = useAuthStore((state) => state.user);
  const { login, isPending, error } = useCounselorLogin();

  // 회원가입 외 인증 폼과 동일하게 마운트 시 CSRF 쿠키를 프라이밍한다(POST /auth/login 전 필요).
  useCsrfPrime();

  // AuthGate가 부트스트랩에서 authStore.user를 이미 복원해두므로, 이미 COUNSELOR로 로그인된
  // 사용자가 이 화면에 직접 진입하면(예: 새 탭에서 URL 직접 입력) 재로그인 없이 바로 보낸다.
  useEffect(() => {
    if (currentUser && isCounselorRole(currentUser.role)) {
      navigate(COUNSELOR_QUEUE_ROUTE, { replace: true });
    }
  }, [currentUser, navigate]);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isLoginFormValid(loginId, password)) return;
    login({ loginId: loginId.trim(), password });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted p-6">
      <div className="flex w-full max-w-[440px] flex-col items-center gap-[30px] rounded-[20px] border border-white/40 bg-white p-10 shadow-sm">
        {/* 랜딩 페이지 좌측 상단(LandingHeader)·로그인 후 사이드바(SideNavBar)와 동일 자산·크기 재사용
            — 워드마크가 이미지에 포함돼 있어 별도 텍스트 span을 겹쳐 그리지 않는다(PlatformAdminLoginPage와 동일). */}
        <img className="h-7 w-auto object-contain" src={brandLogo} alt="HajaCheck" />
        <h1 className="m-0 text-2xl font-medium text-heading">상담원 로그인</h1>

        <form className="flex w-full flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className={LABEL_CLASSES} htmlFor="counselor-login-id">
              아이디
            </label>
            <input
              id="counselor-login-id"
              type="text"
              className={LOGIN_INPUT_CLASSES}
              value={loginId}
              onChange={(event) => setLoginId(event.target.value)}
              autoComplete="username"
              placeholder="counselor@example.com"
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className={LABEL_CLASSES} htmlFor="counselor-login-password">
              비밀번호
            </label>
            <input
              id="counselor-login-password"
              type="password"
              className={LOGIN_INPUT_CLASSES}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              placeholder="비밀번호"
            />
          </div>

          {/* role="alert" — 로그인 실패 사유를 스크린리더가 즉시 읽도록(#846 규약, 기업 로그인
              CompanyLoginTab·PlatformAdminLoginPage와 동일). 이 화면에만 빠져 있었다. */}
          {errorMessage && (
            <p role="alert" className={ERROR_CLASSES}>
              {errorMessage}
            </p>
          )}

          <Button
            type="submit"
            size="lg"
            className="w-full"
            disabled={isPending || !isLoginFormValid(loginId, password)}
          >
            {isPending ? '로그인 중...' : '로그인'}
          </Button>
        </form>
      </div>
    </div>
  );
}
