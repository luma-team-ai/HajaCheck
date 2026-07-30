// @vitest-environment jsdom
// 필수항목 인라인 에러 + 첫 무효 필드 스크롤/포커스 + 제출 버튼 위 요약 알림(#1332) —
// 사용자 실제 제보: 기업 회원가입에서 회사 주소를 비운 채 [가입 신청하기]를 누르면 에러 문구도
// 포커스 이동도 없이 화면이 완전히 무반응으로 보였다. 상호명·대표자명도 같은 결함이 있었다
// (CompanySignupPage.tsx 원인 분석 — 세 필드 모두 에러 렌더 코드 자체가 없었음).
//
// 실제 HTTP 왕복은 CompanySignupPage.test.tsx와 동일하게 authApi 스파이로 대체한다(msw+jsdom+
// undici 환경 한계로 파일 파트가 있는 signup 요청은 이 프로젝트에서 안정 재현되지 않음).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../api/authApi';
import { CompanySignupPage } from './CompanySignupPage';

vi.mock('../hooks/useDaumPostcodeSearch', () => ({
  useDaumPostcodeSearch: () => ({
    openPostcodeSearch: (onComplete: (address: string) => void) => {
      onComplete('서울시 강남구 테헤란로 1');
    },
  }),
}));

beforeEach(() => {
  // jsdom(vitest)에는 scrollIntoView가 구현돼 있지 않다(Element.prototype 미구현) — 실제 호출
  // 여부·인자를 검증하기 위해 스파이로 대체한다(AiAssistantPage.test.tsx·DashboardPage.test.tsx와
  // 동일한 로컬 모킹 패턴).
  Element.prototype.scrollIntoView = vi.fn();
});

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

async function verifyBusinessSuccessfully() {
  vi.spyOn(authApi, 'verifyBusiness').mockResolvedValue({
    data: { result: 'VERIFIED', message: '사업자 정보가 국세청 등록정보와 일치합니다.' },
  } as Awaited<ReturnType<typeof authApi.verifyBusiness>>);

  fireEvent.click(screen.getByRole('button', { name: '진위확인' }));

  await waitFor(() => {
    expect(screen.getByText(/사업자 정보가 국세청 등록정보와 일치합니다\./)).not.toBeNull();
  });
}

type SkipField = 'address' | 'companyName' | 'representativeName';

// 이메일~약관동의까지 전부 채운 "완전 유효" 상태를 만들되, skip에 지정된 필드만 비워 둔다 —
// "이 필드 하나만 비었을 때" 그 필드의 에러·포커스만 단독으로 재현하기 위함이다. representativeName을
// 비우면 [진위확인] 버튼 자체가 비활성화되므로(canVerifyBusiness) 진위확인도 함께 건너뛴다.
async function fillValidFormExcept(skip: SkipField[]) {
  fireEvent.change(screen.getByLabelText('아이디(이메일)'), { target: { value: 'new-company' } });
  fireEvent.change(screen.getByLabelText('이메일 도메인 직접입력'), {
    target: { value: 'check.com' },
  });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'abcd1234' } });
  fireEvent.change(screen.getByLabelText('비밀번호 재입력'), { target: { value: 'abcd1234' } });

  if (!skip.includes('address')) {
    fireEvent.click(screen.getByRole('button', { name: '주소검색' }));
  }

  const file = new File(['dummy'], 'license.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });

  fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1234567890' } });
  if (!skip.includes('companyName')) {
    fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '(주)하자체크' } });
  }
  if (!skip.includes('representativeName')) {
    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });
  }
  fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2015-03-02' } });

  if (!skip.includes('representativeName')) {
    await verifyBusinessSuccessfully();
  }

  fireEvent.click(screen.getByLabelText(/이용약관에 동의합니다/));
  fireEvent.click(screen.getByLabelText(/개인정보 수집 및 이용에 동의합니다/));
}

const REQUIRED_FIELD_SUMMARY_TEXT = '입력하지 않은 필수 항목이 있습니다. 표시된 항목을 확인해 주세요.';

describe('CompanySignupPage — 회사 주소 필수 검증(#1332)', () => {
  it('주소만 비운 채 제출하면 주소 에러 + 요약 알림이 뜨고 주소검색 버튼으로 스크롤/포커스 이동, signup은 호출되지 않는다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');
    renderPage();
    await fillValidFormExcept(['address']);

    const searchButton = screen.getByRole('button', { name: '주소검색' }) as HTMLButtonElement;

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('주소검색으로 회사 주소를 입력해 주세요.')).not.toBeNull();
    expect(screen.getByText(REQUIRED_FIELD_SUMMARY_TEXT)).not.toBeNull();
    expect(document.activeElement).toBe(searchButton);
    expect(searchButton.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'center',
    });
    expect(signupSpy).not.toHaveBeenCalled();
  });
});

describe('CompanySignupPage — 상호명 필수 검증(#1332)', () => {
  it('상호명만 비운 채 제출하면 상호명 에러 + 요약 알림이 뜨고 상호명 입력으로 스크롤/포커스 이동, signup은 호출되지 않는다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');
    renderPage();
    await fillValidFormExcept(['companyName']);

    const companyNameInput = screen.getByLabelText('상호명') as HTMLInputElement;

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('상호명을 입력해 주세요.')).not.toBeNull();
    expect(screen.getByText(REQUIRED_FIELD_SUMMARY_TEXT)).not.toBeNull();
    expect(document.activeElement).toBe(companyNameInput);
    expect(companyNameInput.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'center',
    });
    expect(signupSpy).not.toHaveBeenCalled();
  });
});

describe('CompanySignupPage — 대표자명 필수 검증(#1332)', () => {
  it('대표자명만 비운 채 제출하면 대표자명 에러 + 요약 알림이 뜨고 대표자명 입력으로 스크롤/포커스 이동, signup은 호출되지 않는다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');
    renderPage();
    await fillValidFormExcept(['representativeName']);

    const representativeNameInput = screen.getByLabelText('대표자명') as HTMLInputElement;

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('대표자명을 입력해 주세요.')).not.toBeNull();
    expect(screen.getByText(REQUIRED_FIELD_SUMMARY_TEXT)).not.toBeNull();
    expect(document.activeElement).toBe(representativeNameInput);
    expect(representativeNameInput.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'center',
    });
    expect(signupSpy).not.toHaveBeenCalled();
  });
});

describe('CompanySignupPage — 첫 무효 필드 우선순위(#1332)', () => {
  it('아무것도 입력하지 않고 제출하면 폼에 가장 먼저 나타나는 이메일 필드로 포커스가 이동한다', () => {
    renderPage();
    const emailInput = screen.getByLabelText('아이디(이메일)') as HTMLInputElement;

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(document.activeElement).toBe(emailInput);
    expect(emailInput.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
  });

  it('회사 주소와 상호명이 모두 비어 있으면 DOM상 더 앞선 주소검색 버튼이 먼저 포커스된다', async () => {
    renderPage();
    await fillValidFormExcept(['address', 'companyName']);

    const searchButton = screen.getByRole('button', { name: '주소검색' }) as HTMLButtonElement;
    const companyNameInput = screen.getByLabelText('상호명') as HTMLInputElement;

    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(document.activeElement).toBe(searchButton);
    expect(document.activeElement).not.toBe(companyNameInput);
  });
});

describe('CompanySignupPage — 요약 알림 노출 범위(#1332)', () => {
  it('필수 입력을 모두 채우고 진위확인만 미완료인 상태로 제출하면 요약 알림은 뜨지 않고 기존 진위확인 게이트 문구만 노출된다', async () => {
    const signupSpy = vi.spyOn(authApi, 'signupCompany');
    renderPage();

    fireEvent.change(screen.getByLabelText('아이디(이메일)'), { target: { value: 'new-company' } });
    fireEvent.change(screen.getByLabelText('이메일 도메인 직접입력'), {
      target: { value: 'check.com' },
    });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'abcd1234' } });
    fireEvent.change(screen.getByLabelText('비밀번호 재입력'), { target: { value: 'abcd1234' } });
    fireEvent.click(screen.getByRole('button', { name: '주소검색' }));
    const file = new File(['dummy'], 'license.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });
    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1234567890' } });
    fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '(주)하자체크' } });
    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '김대표' } });
    fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2015-03-02' } });
    fireEvent.click(screen.getByLabelText(/이용약관에 동의합니다/));
    fireEvent.click(screen.getByLabelText(/개인정보 수집 및 이용에 동의합니다/));

    // 진위확인은 시도하지 않은 채(canVerifyBusiness는 통과하지만 버튼을 누르지 않음) 제출한다.
    fireEvent.click(screen.getByRole('button', { name: '가입 신청하기' }));

    expect(await screen.findByText('사업자 진위확인을 먼저 완료해 주세요.')).not.toBeNull();
    expect(screen.queryByText(REQUIRED_FIELD_SUMMARY_TEXT)).toBeNull();
    expect(signupSpy).not.toHaveBeenCalled();
  });
});
