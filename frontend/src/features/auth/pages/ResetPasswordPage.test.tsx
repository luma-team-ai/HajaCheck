// @vitest-environment jsdom
// 계약(contract.md) "비밀번호 찾기 2단계" — 토큰 없음/무효·만료·사용됨 통일 안내 + 토큰 소비 후
// URL에서 제거(history.replaceState). 완료기준: Referrer-Policy(useNoReferrer)·history.replaceState 적용 검증.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { EmailAvailabilityResponse } from '../types';
import { MOCK_VALID_RESET_TOKEN, passwordResetHandlers } from '../mocks/passwordReset.mock';
import { ResetPasswordPage } from './ResetPasswordPage';

const csrfPrimeHandler = http.get('/api/auth/email-availability', () => {
  const success: ApiResponse<EmailAvailabilityResponse> = { success: true, data: { available: true } };
  return HttpResponse.json(success);
});

const server = setupServer(...passwordResetHandlers, csrfPrimeHandler);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  // 각 테스트가 손댄 실제 window.location(query string)을 다음 테스트로 새지 않게 초기화
  window.history.replaceState(null, '', '/');
});
afterAll(() => server.close());

function renderPage(path: string) {
  // useSearchParams는 MemoryRouter의 in-memory 위치를 읽고, ResetPasswordPage의
  // history.replaceState 스트립은 실제 window.location을 건드린다 — 실제 앱(BrowserRouter)처럼
  // 두 값이 같은 경로/쿼리를 가리키도록 테스트에서도 window.history를 함께 맞춰준다.
  window.history.pushState({}, '', path);

  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <ResetPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function fillMatchingPasswords(value: string) {
  fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value } });
}

beforeEach(() => {
  window.history.replaceState(null, '', '/');
});

describe('ResetPasswordPage — 토큰 없음', () => {
  it('토큰이 없으면 폼 대신 안내와 재요청 링크를 보여준다', () => {
    renderPage('/reset-password');

    expect(screen.getByText('유효하지 않은 접근입니다')).not.toBeNull();
    expect(screen.queryByLabelText('새 비밀번호')).toBeNull();
    expect(screen.getByRole('link', { name: '비밀번호 찾기로' })).not.toBeNull();
  });

  it('토큰이 빈 값이면 폼 대신 안내를 보여준다', () => {
    renderPage('/reset-password?token=');

    expect(screen.getByText('유효하지 않은 접근입니다')).not.toBeNull();
  });
});

describe('ResetPasswordPage — 토큰 있음', () => {
  it('토큰이 있으면 새 비밀번호 폼을 보여준다', () => {
    renderPage(`/reset-password?token=${MOCK_VALID_RESET_TOKEN}`);

    expect(screen.getByLabelText('새 비밀번호')).not.toBeNull();
    expect(screen.getByLabelText('새 비밀번호 확인')).not.toBeNull();
  });

  it('비밀번호 확인이 일치하지 않으면 제출을 막고 메시지를 보여준다', () => {
    renderPage(`/reset-password?token=${MOCK_VALID_RESET_TOKEN}`);

    fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: 'abcd1234' } });
    fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: 'abcd9999' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    expect(screen.getByText('비밀번호가 일치하지 않습니다.')).not.toBeNull();
    expect(screen.queryByText('비밀번호가 변경되었습니다.')).toBeNull();
  });

  it('유효한 토큰 + 정책을 만족하는 비밀번호면 성공 화면으로 전환되고 URL에서 토큰이 제거된다', async () => {
    renderPage(`/reset-password?token=${MOCK_VALID_RESET_TOKEN}`);

    fillMatchingPasswords('abcd1234');
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    await waitFor(() => {
      expect(screen.getByText('비밀번호가 변경되었습니다.')).not.toBeNull();
    });
    expect(window.location.search).toBe('');
  });

  it('무효·만료·사용된 토큰이면 통일된 안내를 보여주고(어느 경우인지 노출하지 않음) URL에서 토큰을 제거한다', async () => {
    renderPage('/reset-password?token=expired-or-used-token');

    fillMatchingPasswords('abcd1234');
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    await waitFor(() => {
      expect(screen.getByText('링크가 만료되었거나 이미 사용되었습니다.')).not.toBeNull();
    });
    expect(window.location.search).toBe('');
    expect(screen.getByRole('link', { name: '비밀번호 찾기 다시 요청하기' })).not.toBeNull();
  });
});
