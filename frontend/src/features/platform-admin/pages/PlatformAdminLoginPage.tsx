import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { Button } from '../../../shared/components/Button';
import { PLATFORM_ADMIN_ROUTE } from '../../../shared/constants/routes';
import { isPlatformAdminRole } from '../../../shared/constants/roles';
import { ERROR_CLASSES, LABEL_CLASSES, LOGIN_INPUT_CLASSES } from '../../auth/formClasses';
import { useCsrfPrime } from '../../auth/hooks/useCsrfPrime';
import { isLoginFormValid } from '../../auth/utils/validateLoginForm';
import { useAuthStore } from '../../auth/store/authStore';
import { usePlatformAdminLogin } from '../hooks/usePlatformAdminLogin';

// 이 화면이 허용하지 않는 role(PLATFORM_ADMIN 외)로 로그인을 시도한 경우(#1513) — 서버가 403
// AUTH_ROLE_NOT_ALLOWED로 거절하며 세션은 발급되지 않는다. 전용 콘솔 로그인 화면이라 기업 탭과
// 달리 "관리자 계정이 아니다"까지는 밝혀도 무방하다(기존 #535 문구를 그대로 재사용).
const ROLE_DENIED_MESSAGE = '플랫폼 관리자 계정이 아닙니다.';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '아이디 또는 비밀번호가 올바르지 않습니다.',
  // CSRF 토큰 누락·만료(#1200) — 이 화면도 useCsrfPrime + 로그인 POST라 403 노출 경로가
  // 기업 로그인(CompanyLoginTab)과 동일하다. 기본 문구로 표시하면 자격 증명 오류로 오인된다.
  // ⚠️ 아래 AUTH_ROLE_NOT_ALLOWED와 혼동 금지 — 둘 다 HTTP 403이지만 코드가 달라 서로 겹치지 않는다.
  FORBIDDEN: '요청이 만료되었습니다. 다시 시도해 주세요.',
  // 포털 role 게이트 거절(#1513) — 매핑이 없으면 DEFAULT_ERROR_MESSAGE로 오안내된다.
  AUTH_ROLE_NOT_ALLOWED: ROLE_DENIED_MESSAGE,
};
const DEFAULT_ERROR_MESSAGE = '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.';

// 플랫폼 관리자 전용 로그인(#535, Figma node 973-2520) — 기업회원 로그인(LoginPage)의 개인/기업
// 탭 없이 아이디/비밀번호 단일 폼. 로그인 요청은 전용 엔드포인트(POST /api/auth/platform-admin/login)로
// 보내며 role 강제는 서버가 한다(#1513 — usePlatformAdminLogin 주석 참조).
export function PlatformAdminLoginPage() {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const navigate = useNavigate();
  const currentUser = useAuthStore((state) => state.user);
  const { login, isPending, error } = usePlatformAdminLogin();

  // 회원가입 외 인증 폼과 동일하게 마운트 시 CSRF 쿠키를 프라이밍한다(POST /auth/login 전 필요).
  useCsrfPrime();

  // AuthGate가 부트스트랩에서 authStore.user를 이미 복원해두므로, 이미 PLATFORM_ADMIN으로
  // 로그인된 사용자가 이 화면에 직접 진입하면(예: 새 탭에서 URL 직접 입력) 재로그인 없이 바로 보낸다.
  useEffect(() => {
    if (currentUser && isPlatformAdminRole(currentUser.role)) {
      navigate(PLATFORM_ADMIN_ROUTE, { replace: true });
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
            — 워드마크가 이미지에 포함돼 있어 별도 텍스트 span을 겹쳐 그리지 않는다. */}
        <img className="h-7 w-auto object-contain" src={brandLogo} alt="HajaCheck" />
        <h1 className="m-0 text-2xl font-medium text-heading">관리자 로그인</h1>

        <form className="flex w-full flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className={LABEL_CLASSES} htmlFor="platform-admin-login-id">
              아이디
            </label>
            <input
              id="platform-admin-login-id"
              type="text"
              className={LOGIN_INPUT_CLASSES}
              value={loginId}
              onChange={(event) => setLoginId(event.target.value)}
              autoComplete="username"
              placeholder="admin@example.com"
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className={LABEL_CLASSES} htmlFor="platform-admin-login-password">
              비밀번호
            </label>
            <input
              id="platform-admin-login-password"
              type="password"
              className={LOGIN_INPUT_CLASSES}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              placeholder="비밀번호"
            />
          </div>

          {/* role="alert" — 로그인 실패 사유를 스크린리더가 즉시 읽도록(기업 로그인 CompanyLoginTab과 동일) */}
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
