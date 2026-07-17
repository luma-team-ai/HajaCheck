// 비밀번호 찾기(이메일 링크 방식) MSW 목 — #301(HAJA-224), 계약(contract.md) "비밀번호 찾기 1·2단계"
// 1단계는 계정 존재 여부와 무관하게 항상 동일 200 응답을 준다(계정 열거 방지) — 목도 동일하게
// 구현해 프론트가 실제로 존재/미존재를 구분하지 않는지 테스트로 고정할 수 있게 한다.
import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type { PasswordResetLinkResponse, PasswordResetResponse } from '../types';

// rate-limit(429) 시나리오 데모/테스트용 더미 이메일
export const MOCK_RATE_LIMITED_EMAIL = 'toomany@check.com';
// 유효한 토큰 시나리오 데모/테스트용 — 그 외 값은 전부 무효 취급
export const MOCK_VALID_RESET_TOKEN = 'mock-reset-token';

export const passwordResetHandlers = [
  http.post('/api/auth/password-reset-request', async ({ request }) => {
    const body = (await request.json()) as { email: string };

    if (body.email === MOCK_RATE_LIMITED_EMAIL) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_TOO_MANY_REQUESTS', message: '잠시 후 다시 시도해 주세요.' },
      };
      return HttpResponse.json(failure, { status: 429 });
    }

    // 계정 존재 여부와 무관하게 항상 동일 응답(계약 §비밀번호 찾기 1단계 — 계정 열거 방지)
    const success: ApiResponse<PasswordResetLinkResponse> = {
      success: true,
      data: { requested: true },
    };
    return HttpResponse.json(success);
  }),

  http.post('/api/auth/password-reset', async ({ request }) => {
    const body = (await request.json()) as { token: string; newPassword: string };

    if (body.token !== MOCK_VALID_RESET_TOKEN) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'AUTH_RESET_TOKEN_INVALID',
          message: '링크가 만료되었거나 이미 사용되었습니다.',
        },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const success: ApiResponse<PasswordResetResponse> = {
      success: true,
      data: { reset: true },
    };
    return HttpResponse.json(success);
  }),
];
