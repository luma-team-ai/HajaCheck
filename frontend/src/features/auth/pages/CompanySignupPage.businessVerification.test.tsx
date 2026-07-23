// @vitest-environment jsdom
// 사업자 진위확인(#648 BE, #663 FE) — [진위확인] 버튼 + 결과 뱃지 6종 + 가입 신청 게이팅 + 확인
// 후 입력값을 바꾸면 결과가 무효화되는지(우회 방지) 검증한다. 실제 HTTP 왕복은 authApi.verifyBusiness
// 스파이로 대체한다(CompanySignupPage.test.tsx의 signupCompany 스파이 패턴과 동일 — 파일 파트가
// 있는 signup 요청은 msw+jsdom+undici 환경 한계로 이 프로젝트에서 안정 재현되지 않기 때문).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../api/authApi';
import type { BusinessVerificationResponse } from '../types';
import { CompanySignupPage } from './CompanySignupPage';

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

// 진위확인 버튼 활성화에 필요한 3필드만 채운다(이메일/비밀번호/주소/약관 등은 게이팅 테스트에
// 필요한 경우에만 각 it에서 별도로 채운다).
function fillVerificationFields() {
  fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1234567890' } });
  fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });
  fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2015-03-02' } });
}

// 진위확인 이외 필수 필드까지 모두 채워 [가입 신청하기]가 게이팅(진위확인)만 남은 상태로 만든다.
function fillRestOfRequiredFields() {
  fireEvent.change(screen.getByLabelText('아이디(이메일)'), { target: { value: 'new-company' } });
  fireEvent.change(screen.getByLabelText('이메일 도메인 직접입력'), {
    target: { value: 'check.com' },
  });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'abcd1234' } });
  fireEvent.change(screen.getByLabelText('비밀번호 재입력'), { target: { value: 'abcd1234' } });
  fireEvent.click(screen.getByRole('button', { name: '주소검색' }));
  const file = new File(['dummy'], 'license.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });
  fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '(주)하자체크' } });
  fireEvent.click(screen.getByLabelText(/이용약관에 동의합니다/));
  fireEvent.click(screen.getByLabelText(/개인정보 수집 및 이용에 동의합니다/));
}

function mockVerifyBusiness(response: BusinessVerificationResponse) {
  return vi.spyOn(authApi, 'verifyBusiness').mockResolvedValue({
    data: response,
  } as Awaited<ReturnType<typeof authApi.verifyBusiness>>);
}

// 뱃지는 "{아이콘} {message}"로 렌더되므로(CompanySignupPage BUSINESS_VERIFICATION_BADGE_ICONS),
// 정확 일치 대신 message를 포함하는지로 매칭한다.
function badgeText(message: string) {
  return new RegExp(message.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
}

describe('CompanySignupPage — 사업자 진위확인 버튼 활성화(#663)', () => {
  it('3필드 중 하나라도 비어있으면 [진위확인] 버튼이 비활성 상태다', () => {
    renderPage();
    const verifyButton = screen.getByRole('button', { name: '진위확인' }) as HTMLButtonElement;
    expect(verifyButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1234567890' } });
    expect(verifyButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });
    expect(verifyButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2015-03-02' } });
    expect(verifyButton.disabled).toBe(false);
  });
});

describe('CompanySignupPage — 사업자 진위확인 결과 뱃지 6종(#663)', () => {
  it.each([
    ['VERIFIED', '사업자 정보가 국세청 등록정보와 일치합니다.'],
    ['NOT_REGISTERED', '국세청에 등록되지 않은 사업자입니다.'],
    ['MISMATCH', '사업자번호는 존재하나 대표자명 또는 개업일자가 일치하지 않습니다.'],
    ['SUSPENDED', '휴업 중인 사업자입니다.'],
    ['CLOSED', '폐업한 사업자입니다.'],
    ['UNAVAILABLE', '국세청 서비스 장애로 확인이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'],
  ] as const)('%s 결과의 message가 뱃지로 그대로 노출된다', async (resultCode, message) => {
    mockVerifyBusiness({ result: resultCode, message });

    renderPage();
    fillVerificationFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));

    expect(await screen.findByText(badgeText(message))).not.toBeNull();
  });
});

describe('CompanySignupPage — 사업자 진위확인 가입 신청 게이팅(#663)', () => {
  it('진위확인을 하지 않고 제출하면 signup이 발화하지 않고 안내 메시지가 노출된다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');

    renderPage();
    fillVerificationFields();
    fillRestOfRequiredFields();

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('사업자 진위확인을 먼저 완료해 주세요.')).not.toBeNull();
    expect(signupSpy).not.toHaveBeenCalled();
  });

  it('VERIFIED면 제출이 통과된다(다른 필드 정상 시 signup 발화)', async () => {
    mockVerifyBusiness({
      result: 'VERIFIED',
      message: '사업자 정보가 국세청 등록정보와 일치합니다.',
    });
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: { companyId: 1, maskedEmail: 'n***@c***.com', status: 'PENDING_REVIEW', signupToken: 't' },
    } as Awaited<ReturnType<typeof authApi.signupCompany>>);

    renderPage();
    fillVerificationFields();
    fillRestOfRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'));

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    await waitFor(() => expect(signupSpy).toHaveBeenCalledTimes(1));
  });

  it('UNAVAILABLE(국세청 장애 fail-open)이면 제출이 통과된다', async () => {
    mockVerifyBusiness({
      result: 'UNAVAILABLE',
      message: '국세청 서비스 장애로 확인이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
    });
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: { companyId: 1, maskedEmail: 'n***@c***.com', status: 'PENDING_REVIEW', signupToken: 't' },
    } as Awaited<ReturnType<typeof authApi.signupCompany>>);

    renderPage();
    fillVerificationFields();
    fillRestOfRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(
      badgeText('국세청 서비스 장애로 확인이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'),
    );

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    await waitFor(() => expect(signupSpy).toHaveBeenCalledTimes(1));
  });

  it.each([
    ['NOT_REGISTERED', '국세청에 등록되지 않은 사업자입니다.'],
    ['MISMATCH', '사업자번호는 존재하나 대표자명 또는 개업일자가 일치하지 않습니다.'],
    ['SUSPENDED', '휴업 중인 사업자입니다.'],
    ['CLOSED', '폐업한 사업자입니다.'],
  ] as const)('%s면 제출이 차단된다(signup 미호출)', async (resultCode, message) => {
    mockVerifyBusiness({ result: resultCode, message });
    const signupSpy = vi.spyOn(authApi, 'signupCompany');

    renderPage();
    fillVerificationFields();
    fillRestOfRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText(message));

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    // 실패 계열 뱃지가 이미 노출 중이므로 별도 게이트 안내 문구는 중복 노출하지 않는다(PR #666 P3).
    // signup 미호출로 게이트가 닫혀 있는지만 확인한다.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByText('사업자 진위확인을 먼저 완료해 주세요.')).toBeNull();
    expect(screen.getByText(badgeText(message))).not.toBeNull();
    expect(signupSpy).not.toHaveBeenCalled();
  });
});

describe('CompanySignupPage — 사업자 진위확인 무효화(우회 방지, #663)', () => {
  it('확인 성공 후 사업자등록번호를 바꾸면 뱃지가 사라진다', async () => {
    mockVerifyBusiness({
      result: 'VERIFIED',
      message: '사업자 정보가 국세청 등록정보와 일치합니다.',
    });

    renderPage();
    fillVerificationFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'));

    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '9999999999' } });

    expect(screen.queryByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'))).toBeNull();
  });

  it('확인 성공 후 대표자명을 바꾸면 뱃지가 사라진다', async () => {
    mockVerifyBusiness({
      result: 'VERIFIED',
      message: '사업자 정보가 국세청 등록정보와 일치합니다.',
    });

    renderPage();
    fillVerificationFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'));

    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '박대표' } });

    expect(screen.queryByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'))).toBeNull();
  });

  it('확인 성공 후 개업일자를 바꾸면 뱃지가 사라진다', async () => {
    mockVerifyBusiness({
      result: 'VERIFIED',
      message: '사업자 정보가 국세청 등록정보와 일치합니다.',
    });

    renderPage();
    fillVerificationFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'));

    fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2016-01-01' } });

    expect(screen.queryByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'))).toBeNull();
  });

  it('무효화 후 재확인해야 가입 신청 게이팅이 다시 풀린다', async () => {
    mockVerifyBusiness({
      result: 'VERIFIED',
      message: '사업자 정보가 국세청 등록정보와 일치합니다.',
    });
    const signupSpy = vi.spyOn(authApi, 'signupCompany').mockResolvedValue({
      data: { companyId: 1, maskedEmail: 'n***@c***.com', status: 'PENDING_REVIEW', signupToken: 't' },
    } as Awaited<ReturnType<typeof authApi.signupCompany>>);

    renderPage();
    fillVerificationFields();
    fillRestOfRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'));

    // 확인 후 값을 바꿔 무효화시킨 뒤 원래 값으로 되돌려도(재확인 없이는) 게이팅은 여전히 막힌다.
    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '박대표' } });
    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));
    expect(await screen.findByText('사업자 진위확인을 먼저 완료해 주세요.')).not.toBeNull();
    expect(signupSpy).not.toHaveBeenCalled();

    // 재확인하면 다시 통과한다.
    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));
    await screen.findByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'));

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));
    await waitFor(() => expect(signupSpy).toHaveBeenCalledTimes(1));
  });
});

describe('CompanySignupPage — 진위확인 in-flight 무효화(PR #666 P2)', () => {
  it('응답 대기 중 필드를 바꾸면, 이전 값 기준 응답이 뒤늦게 VERIFIED로 와도 게이트가 열리지 않는다', async () => {
    // authApi.verifyBusiness를 수동 제어 deferred promise로 대체 — resolveVerify를 호출하기
    // 전까지는 mutation이 pending 상태(result·error 모두 undefined)로 유지된다. 이 창에서
    // 필드를 바꾸는 시나리오를 재현한다(onChange의 reset()은 result/error가 있어야만 걸리므로
    // pending 중엔 걸리지 않는다 — verifiedSnapshot 비교가 이 구멍을 막아야 한다).
    let resolveVerify: (value: BusinessVerificationResponse) => void = () => {};
    const deferred = new Promise<{ data: BusinessVerificationResponse }>((resolve) => {
      resolveVerify = (value) => resolve({ data: value });
    });
    vi.spyOn(authApi, 'verifyBusiness').mockReturnValue(
      deferred as unknown as ReturnType<typeof authApi.verifyBusiness>,
    );
    const signupSpy = vi.spyOn(authApi, 'signupCompany');

    renderPage();
    fillVerificationFields();
    fillRestOfRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));

    // 응답이 아직 오지 않은(in-flight) 상태에서 사업자등록번호를 다른 값으로 바꾼다.
    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '9999999999' } });

    // 이전 값(1234567890) 기준으로 확인했던 요청이 VERIFIED로 뒤늦게 응답한다.
    resolveVerify({ result: 'VERIFIED', message: '사업자 정보가 국세청 등록정보와 일치합니다.' });
    await waitFor(() => {
      expect(screen.getByText(badgeText('사업자 정보가 국세청 등록정보와 일치합니다.'))).not.toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    // 현재 사업자등록번호(9999999999)가 확인 시점 스냅샷(1234567890)과 달라 게이트는 여전히
    // 닫혀 있어야 한다 — signup이 발화하지 않는다.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(signupSpy).not.toHaveBeenCalled();
  });
});

describe('CompanySignupPage — 사업자 진위확인 에러(429/400, #663)', () => {
  it('429 rate limit 에러 메시지가 노출된다', async () => {
    vi.spyOn(authApi, 'verifyBusiness').mockRejectedValue({
      code: 'AUTH_TOO_MANY_REQUESTS',
      message: '잠시 후 다시 시도해 주세요.',
    });

    renderPage();
    fillVerificationFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));

    expect(await screen.findByText('잠시 후 다시 시도해 주세요.')).not.toBeNull();
  });

  it('400 INVALID_INPUT 에러 메시지가 노출된다', async () => {
    vi.spyOn(authApi, 'verifyBusiness').mockRejectedValue({
      code: 'INVALID_INPUT',
      message: '입력값을 다시 확인해 주세요.',
    });

    renderPage();
    fillVerificationFields();
    fireEvent.click(screen.getByRole('button', { name: '진위확인' }));

    expect(await screen.findByText('입력값을 다시 확인해 주세요.')).not.toBeNull();
  });
});
