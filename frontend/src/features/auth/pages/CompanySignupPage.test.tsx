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

// 이메일 이외 필드(비밀번호~약관 동의) — 직접입력/프리셋 두 이메일 경로 테스트가 공유한다.
function fillNonEmailFields() {
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'abcd1234' } });
  fireEvent.change(screen.getByLabelText('비밀번호 재입력'), { target: { value: 'abcd1234' } });

  fireEvent.click(screen.getByRole('button', { name: '주소검색' }));

  const file = new File(['dummy'], 'license.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });

  fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1234567890' } });
  fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '(주)하자체크' } });
  fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });
  // 개업일자(#600) — 국세청 진위확인(#596)이 요구하는 필수값
  fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2015-03-02' } });

  fireEvent.click(screen.getByLabelText(/이용약관에 동의합니다/));
  fireEvent.click(screen.getByLabelText(/개인정보 수집 및 이용에 동의합니다/));
}

function fillValidForm() {
  // 기본 상태는 도메인 직접입력 모드(#417) — 로컬파트 라벨(기존 계약 유지) + 직접입력 도메인 input
  // 두 곳을 채워야 기존과 동일한 'new-company@check.com' 조합 이메일이 만들어진다.
  fireEvent.change(screen.getByLabelText('아이디(이메일)'), {
    target: { value: 'new-company' },
  });
  fireEvent.change(screen.getByLabelText('이메일 도메인 직접입력'), {
    target: { value: 'check.com' },
  });
  fillNonEmailFields();
}

// PR머신 P3 회귀 위험 대응(#417) — 프리셋 도메인 select 경로도 조합 이메일이 올바르게 만들어지는지
// 통합 테스트로 고정한다(EmailDomainField 단위 테스트는 콜백 발화만 확인, 실제 제출 페이로드는
// 여기서 검증). 프리셋 선택 시 도메인 직접입력 input은 사라지므로 건드리지 않는다.
function fillValidFormWithPresetDomain(presetDomain: string) {
  fireEvent.change(screen.getByLabelText('아이디(이메일)'), {
    target: { value: 'new-company' },
  });
  fireEvent.change(screen.getByLabelText('이메일 도메인 선택'), {
    target: { value: presetDomain },
  });
  fillNonEmailFields();
}

describe('CompanySignupPage — 제출 버튼 계약(shared Button)', () => {
  it('유효한 폼을 채우고 제출하면 signup mutation(authApi.signupCompany)이 발화한다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: {
        companyId: 12,
        maskedEmail: 'n***@c***.com',
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
      businessStartDate: '2015-03-02',
      agreeTermsOfService: true,
      agreePrivacyPolicy: true,
    });
  });

  it('프리셋 도메인(naver.com)을 선택해 제출하면 로컬파트+선택값이 조합된 이메일로 signup이 발화한다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: {
        companyId: 13,
        maskedEmail: 'n***@n***.com',
        status: 'PENDING_REVIEW',
        signupToken: 'token',
      },
    } as Awaited<ReturnType<typeof authApi.signupCompany>>);

    renderPage();
    fillValidFormWithPresetDomain('naver.com');

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    await waitFor(() => {
      expect(signupSpy).toHaveBeenCalledTimes(1);
    });
    expect(signupSpy.mock.calls[0][0]).toMatchObject({
      email: 'new-company@naver.com',
    });
  });

  it('직접입력 도메인을 입력한 뒤 프리셋 선택 → 직접입력 재전환 시 이전 도메인 값이 복원된다', async () => {
    // code-reviewer P2(#417) — 직접입력→프리셋→직접입력 왕복 시 이전 커스텀 도메인 값이
    // 소실되지 않고 복원되는지 고정한다(복원 확인 후 그 값 그대로 제출까지 검증).
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: {
        companyId: 14,
        maskedEmail: 'n***@m***.io',
        status: 'PENDING_REVIEW',
        signupToken: 'token',
      },
    } as Awaited<ReturnType<typeof authApi.signupCompany>>);

    renderPage();

    fireEvent.change(screen.getByLabelText('아이디(이메일)'), {
      target: { value: 'new-company' },
    });
    fireEvent.change(screen.getByLabelText('이메일 도메인 직접입력'), {
      target: { value: 'mycompany.io' },
    });

    // 실수로 프리셋 선택
    fireEvent.change(screen.getByLabelText('이메일 도메인 선택'), {
      target: { value: 'naver.com' },
    });
    expect(screen.queryByLabelText('이메일 도메인 직접입력')).toBeNull();

    // 다시 직접입력으로 전환 — 이전에 입력했던 mycompany.io가 빈 값이 아니라 복원되어야 한다
    fireEvent.change(screen.getByLabelText('이메일 도메인 선택'), {
      target: { value: '__custom__' },
    });
    const restoredInput = screen.getByLabelText('이메일 도메인 직접입력') as HTMLInputElement;
    expect(restoredInput.value).toBe('mycompany.io');

    fillNonEmailFields();
    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    await waitFor(() => {
      expect(signupSpy).toHaveBeenCalledTimes(1);
    });
    expect(signupSpy.mock.calls[0][0]).toMatchObject({
      email: 'new-company@mycompany.io',
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

describe('CompanySignupPage — 약관 동의 링크(#453)', () => {
  it('이용약관·개인정보 링크가 랜딩과 동일한 약관 페이지로 새 탭 연결된다', () => {
    renderPage();

    const termsLink = screen.getByRole('link', { name: '이용약관' }) as HTMLAnchorElement;
    expect(termsLink.getAttribute('href')).toBe('/policy/terms-of-service');
    expect(termsLink.target).toBe('_blank');
    expect(termsLink.rel).toContain('noopener');

    const privacyLink = screen.getByRole('link', {
      name: '개인정보 수집 및 이용',
    }) as HTMLAnchorElement;
    expect(privacyLink.getAttribute('href')).toBe('/policy/privacy');
    expect(privacyLink.target).toBe('_blank');
    expect(privacyLink.rel).toContain('noopener');
  });

  it('약관 링크 클릭은 동의 체크박스를 토글하지 않는다', () => {
    renderPage();

    const termsCheckbox = screen.getByLabelText(/이용약관에 동의합니다/) as HTMLInputElement;
    expect(termsCheckbox.checked).toBe(false);

    fireEvent.click(screen.getByRole('link', { name: '이용약관' }));
    expect(termsCheckbox.checked).toBe(false);
  });
});

describe('CompanySignupPage — 개업일자 필수 검증(#600)', () => {
  it('개업일자를 비운 채 제출하면 signup이 발화하지 않고 인라인 에러가 노출된다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');

    renderPage();
    fillValidForm();
    // 필수값을 다시 비운다 — fillValidForm이 채운 뒤 사용자가 지운 상황을 재현.
    fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('개업일자를 입력해 주세요.')).not.toBeNull();
    expect(signupSpy).not.toHaveBeenCalled();
  });

  it('개업일자가 미래 날짜면 signup이 발화하지 않고 인라인 에러가 노출된다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');
    const future = new Date();
    future.setFullYear(future.getFullYear() + 1);
    const futureValue = future.toISOString().slice(0, 10);

    renderPage();
    fillValidForm();
    fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: futureValue } });

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('개업일자는 오늘 이전이어야 합니다.')).not.toBeNull();
    expect(signupSpy).not.toHaveBeenCalled();
  });
});
