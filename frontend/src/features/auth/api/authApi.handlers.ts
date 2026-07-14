import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { companyAuthHandlers } from '../mocks/companyAuth.mock';
import type { LoginRequest, UserResponse } from '../types';

// 기업회원 로그인 목 자격증명 — hajacheck / password1234 (더미, 실제 계정 아님)
const MOCK_LOGIN_ID = 'hajacheck';
const MOCK_PASSWORD = 'password1234';

const mockUser: UserResponse = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
};

export const authHandlers = [
  http.post('/api/auth/login', async ({ request }) => {
    const body = (await request.json()) as LoginRequest;

    if (body.loginId === MOCK_LOGIN_ID && body.password === MOCK_PASSWORD) {
      const success: ApiResponse<UserResponse> = { success: true, data: mockUser };
      return HttpResponse.json(success);
    }

    const failure: ApiResponse<null> = {
      success: false,
      data: null,
      error: {
        code: 'AUTH_INVALID_CREDENTIALS',
        message: '아이디 또는 비밀번호가 올바르지 않습니다.',
      },
    };
    return HttpResponse.json(failure, { status: 401 });
  }),

  // 미로그인 상태 목 — 항상 401 (실제 백엔드는 세션 쿠키 유효 시 200)
  http.get('/api/users/me', () => {
    const failure: ApiResponse<null> = {
      success: false,
      data: null,
      error: { code: 'AUTH_UNAUTHORIZED', message: '로그인이 필요합니다.' },
    };
    return HttpResponse.json(failure, { status: 401 });
  }),

  http.post('/api/auth/logout', () => {
    const success: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(success);
  }),

  // 기업 인증 플로우(HAJA-170, #187) 핸들러 — features/auth/mocks/companyAuth.mock.ts
  ...companyAuthHandlers,
];
