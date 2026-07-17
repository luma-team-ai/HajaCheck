// @vitest-environment jsdom
// 계약(contract.md) "비밀번호 찾기 1단계" — 계정 존재 여부와 무관하게 항상 동일 응답·동일 화면.
// 완료기준: "계정 존재 여부가 UI에서 드러나지 않음을 테스트로 고정".
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { EmailAvailabilityResponse } from '../types';
import { MOCK_RATE_LIMITED_EMAIL, passwordResetHandlers } from '../mocks/passwordReset.mock';
import { FindPasswordPage } from './FindPasswordPage';

// useCsrfPrime이 마운트 시 호출하는 GET(더미 이메일 중복확인) — 실제 CSRF 로직과 무관, 에러만 안 나면 됨
const csrfPrimeHandler = http.get('/api/auth/email-availability', () => {
  const success: ApiResponse<EmailAvailabilityResponse> = { success: true, data: { available: true } };
  return HttpResponse.json(success);
});

const server = setupServer(...passwordResetHandlers, csrfPrimeHandler);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/find-password']}>
        <FindPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function submitEmail(email: string) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));
  await waitFor(() => {
    expect(screen.getByText(/재설정 링크를 보냈습니다/)).not.toBeNull();
  });
}

const EXISTENCE_LEAK_PATTERN = /가입되지 않|일치하는 계정을 찾을 수 없|존재하지 않는 계정|등록되지 않은/;

describe('FindPasswordPage — 계정 열거 방지', () => {
  it('가입된 것으로 가정한 이메일과 미가입으로 가정한 이메일이 동일한 성공 안내를 보여준다', async () => {
    const { unmount } = renderPage();
    await submitEmail('registered@check.com');
    const registeredMessage = screen.getByText(/재설정 링크를 보냈습니다/).textContent;
    unmount();

    renderPage();
    await submitEmail('never-signed-up@check.com');
    const unknownMessage = screen.getByText(/재설정 링크를 보냈습니다/).textContent;

    expect(registeredMessage).toBe(unknownMessage);
  });

  it('성공 화면 어디에도 계정 존재 여부를 드러내는 문구가 없다', async () => {
    renderPage();
    await submitEmail('anything@check.com');

    expect(screen.queryByText(EXISTENCE_LEAK_PATTERN)).toBeNull();
  });

  it('429(rate-limit)면 별도 안내를 보여주고 성공 화면으로 전환하지 않는다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('이메일'), {
      target: { value: MOCK_RATE_LIMITED_EMAIL },
    });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));

    await waitFor(() => {
      expect(screen.getByText('잠시 후 다시 시도해 주세요.')).not.toBeNull();
    });
    expect(screen.queryByText(/재설정 링크를 보냈습니다/)).toBeNull();
  });

  it('올바르지 않은 이메일 형식이면 제출을 막고 클라이언트 검증 메시지를 보여준다', () => {
    renderPage();

    // input type="email"의 브라우저(jsdom) 기본 제약검증은 '@' + 도메인만 있으면 통과시키므로,
    // 그 자체로는 걸러지지 않고 isValidEmail(도메인에 '.' 포함 필수)에서만 걸리는 값을 사용한다.
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'test@localhost' } });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));

    expect(screen.getByText('올바른 이메일 형식을 입력해 주세요.')).not.toBeNull();
    expect(screen.queryByText(/재설정 링크를 보냈습니다/)).toBeNull();
  });
});
