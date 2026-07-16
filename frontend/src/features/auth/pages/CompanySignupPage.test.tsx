// @vitest-environment jsdom
// PR머신 P2 대응(#292 후속) — 제출 버튼이 shared Button 컴포넌트로 바뀌면서 type="submit"·disabled
// prop이 실제로 DOM에 전달되는지 확인하는 테스트가 없었다(계약을 깨도 잡아낼 방법이 없었음). 아래
// 테스트는 그 계약(폼 제출 발화 + isPending 중 중복 클릭 차단)을 고정한다.
//
// 파일(File) 파트를 포함한 signup 요청의 실제 HTTP 라운드트립은 msw+jsdom+undici 조합의 알려진
// 환경 한계로 이 프로젝트에서 안정 재현되지 않는다(authApi.company.test.ts 상단 주석 참고) —
// 여기서도 같은 이유로 authApi.signupCompany를 스파이로 대체해 "발화 여부"만 검증하고, 실제 서버
// 왕복은 하지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../api/authApi';
import { CompanySignupPage } from './CompanySignupPage';

// 회사주소는 다음(카카오) 우편번호 팝업으로만 채워지는 read-only 필드라 실제 팝업(외부 스크립트
// 로딩) 없이 "주소검색" 클릭만으로 즉시 채워지도록 훅을 대체한다.
vi.mock('../hooks/useDaumPostcodeSearch', () => ({
  useDaumPostcodeSearch: () => ({
    openPostcodeSearch: (onComplete: (address: string) => void) => {
      onComplete('서울시 강남구 테헤란로 1');
    },
  }),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/signup/company']}>
        <CompanySignupPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function fillValidForm() {
  fireEvent.change(screen.getByLabelText('아이디(이메일)'), {
    target: { value: 'new-company@check.com' },
  });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'abcd1234' } });
  fireEvent.change(screen.getByLabelText('비밀번호 재입력'), { target: { value: 'abcd1234' } });

  fireEvent.click(screen.getByRole('button', { name: '주소검색' }));

  const file = new File(['dummy'], 'license.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });

  fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1234567890' } });
  fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '(주)하자체크' } });
  fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });

  fireEvent.click(screen.getByLabelText(/이용약관에 동의합니다/));
  fireEvent.click(screen.getByLabelText(/개인정보 수집 및 이용에 동의합니다/));
}

describe('CompanySignupPage — 제출 버튼 계약(shared Button)', () => {
  it('유효한 폼을 채우고 제출하면 signup mutation(authApi.signupCompany)이 발화한다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: {
        companyId: 12,
        maskedEmail: 'ne***@check.com',
        status: 'PENDING_REVIEW',
        signupToken: 'token',
      },
      // authApi.signupCompany는 axios 응답 전체를 반환 — 테스트에 필요한 최소 shape만 채움
    } as Awaited<ReturnType<typeof authApi.signupCompany>>);

    renderPage();
    fillValidForm();

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    await waitFor(() => {
      expect(signupSpy).toHaveBeenCalledTimes(1);
    });
    expect(signupSpy.mock.calls[0][0]).toMatchObject({
      email: 'new-company@check.com',
      businessRegistrationNumber: '1234567890',
      companyName: '(주)하자체크',
      representativeName: '김대표',
      agreeTermsOfService: true,
      agreePrivacyPolicy: true,
    });
  });

  it('제출 버튼은 type="submit"으로 렌더되고, isPending 동안 disabled라 중복 클릭이 발화하지 않는다', async () => {
    // 절대 resolve되지 않는 프라미스로 isPending 상태를 고정해 두 번째 클릭이 막히는지 확인
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockReturnValue(new Promise(() => {}));

    renderPage();
    fillValidForm();

    const submitButton = screen.getByRole('button', { name: '가입 신청하기' }) as HTMLButtonElement;
    expect(submitButton.type).toBe('submit');

    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '신청 중...' })).not.toBeNull();
    });
    const pendingButton = screen.getByRole('button', { name: '신청 중...' }) as HTMLButtonElement;
    expect(pendingButton.disabled).toBe(true);

    // disabled 버튼은 네이티브 동작상 클릭 이벤트가 발화하지 않는다 — 두 번째 클릭 후에도
    // mutate 호출 횟수가 늘지 않아야 "disabled가 실제로 중복 제출을 막는다"는 계약이 성립한다.
    fireEvent.click(pendingButton);
    expect(signupSpy).toHaveBeenCalledTimes(1);
  });
});
