import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { companyAuthHandlers } from '../mocks/companyAuth.mock';
import { passwordResetHandlers } from '../mocks/passwordReset.mock';
import type { LoginRequest, UserResponse } from '../types';

// 목 자격증명 — 모두 더미이며 실제 계정이 아니다(비밀번호는 세 계정 공통).
const MOCK_LOGIN_ID = 'hajacheck';
const MOCK_PLATFORM_ADMIN_LOGIN_ID = 'platform-admin';
const MOCK_COUNSELOR_LOGIN_ID = 'counselor';
const MOCK_PASSWORD = 'password1234';

const mockUser: UserResponse = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '하자체크',
  status: 'ACTIVE',
};

const mockPlatformAdminUser: UserResponse = {
  ...mockUser,
  id: 900,
  email: 'platform-admin@example.com',
  name: '플랫폼 운영진',
  role: 'PLATFORM_ADMIN',
  companyId: null,
  companyName: null,
};

const mockCounselorUser: UserResponse = {
  ...mockUser,
  id: 901,
  email: 'counselor@example.com',
  name: '하자체크 상담원',
  role: 'COUNSELOR',
  companyId: null,
  companyName: null,
};

// 목 계정부 — loginId로 조회한다. 세션 유지 목(GET /api/users/me)도 이 표를 써서 "로그인한 계정
// 그대로"를 돌려준다(예전엔 항상 mockUser라, 목 모드에서 플랫폼 관리자로 로그인한 뒤 새로고침하면
// 일반 USER로 되살아나 콘솔에서 튕겼다).
const MOCK_ACCOUNTS: Record<string, UserResponse> = {
  [MOCK_LOGIN_ID]: mockUser,
  [MOCK_PLATFORM_ADMIN_LOGIN_ID]: mockPlatformAdminUser,
  [MOCK_COUNSELOR_LOGIN_ID]: mockCounselorUser,
};

// 로그인한 목 계정의 loginId를 담는다(과거엔 'true' 고정값이었다 — 이 파일 밖 사용처 없음).
const MOCK_SESSION_KEY = 'msw_authenticated';

const INVALID_CREDENTIALS: ApiResponse<null> = {
  success: false,
  data: null,
  error: {
    code: 'AUTH_INVALID_CREDENTIALS',
    message: '아이디 또는 비밀번호가 올바르지 않습니다.',
  },
};

const ROLE_NOT_ALLOWED: ApiResponse<null> = {
  success: false,
  data: null,
  error: {
    code: 'AUTH_ROLE_NOT_ALLOWED',
    message: '이 화면으로는 로그인할 수 없는 계정입니다.',
  },
};

// 포털별 로그인 목 — 실서버(AuthController.authenticateForPortal, #1514/PR #1533)와 같은 순서로
// ① 자격증명 검증(실패 401) → ② 포털 role 게이트(실패 403, 세션 미발급) → ③ 세션 발급.
// 순서가 이 계약의 핵심이라 목도 같은 순서를 지킨다 — 비밀번호를 모르는 쪽은 role 정보를 못 얻는다.
function portalLoginHandler(path: string, allowedRoles: readonly UserResponse['role'][]) {
  return http.post(path, async ({ request }) => {
    const body = (await request.json()) as LoginRequest;
    const account = MOCK_ACCOUNTS[body.loginId];

    if (!account || body.password !== MOCK_PASSWORD) {
      return HttpResponse.json(INVALID_CREDENTIALS, { status: 401 });
    }

    if (!allowedRoles.includes(account.role)) {
      // 세션을 발급하지 않는다 — 프론트가 되돌릴 세션이 없다는 것이 #1513 설계의 전제다.
      return HttpResponse.json(ROLE_NOT_ALLOWED, { status: 403 });
    }

    if (typeof window !== 'undefined') {
      window.sessionStorage.setItem(MOCK_SESSION_KEY, body.loginId);
    }
    const success: ApiResponse<UserResponse> = { success: true, data: account };
    return HttpResponse.json(success);
  });
}

export const authHandlers = [
  portalLoginHandler('/api/auth/login', ['ADMIN', 'INSPECTOR', 'USER']),
  portalLoginHandler('/api/auth/platform-admin/login', ['PLATFORM_ADMIN']),
  portalLoginHandler('/api/auth/counselor/login', ['COUNSELOR']),

  // MSW 세션 유지 목 — 로그인 성공 시 sessionStorage에 로그인한 목 계정이 저장되어
  // 주소창 직접 입력 및 새로고침(F5) 시에도 세션이 유지된다.
  http.get('/api/users/me', () => {
    const loginId =
      typeof window !== 'undefined' ? window.sessionStorage.getItem(MOCK_SESSION_KEY) : null;
    const authenticatedUser = loginId ? MOCK_ACCOUNTS[loginId] : undefined;

    if (authenticatedUser) {
      const success: ApiResponse<UserResponse> = { success: true, data: authenticatedUser };
      return HttpResponse.json(success);
    }

    const failure: ApiResponse<null> = {
      success: false,
      data: null,
      error: { code: 'AUTH_UNAUTHORIZED', message: '로그인이 필요합니다.' },
    };
    return HttpResponse.json(failure, { status: 401 });
  }),

  http.post('/api/auth/logout', () => {
    if (typeof window !== 'undefined') {
      window.sessionStorage.removeItem(MOCK_SESSION_KEY);
    }
    const success: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(success);
  }),

  // 기업 인증 플로우(HAJA-170, #187) 핸들러 — features/auth/mocks/companyAuth.mock.ts
  ...companyAuthHandlers,
  // 비밀번호 찾기(이메일 링크 방식, #301/HAJA-224) 핸들러 — features/auth/mocks/passwordReset.mock.ts
  ...passwordResetHandlers,
];
