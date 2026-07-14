// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { isCompanySignupFormValid, type CompanySignupFormValues } from './validateCompanySignupForm';

function makeFile(): File {
  return new File(['x'], 'license.pdf', { type: 'application/pdf' });
}

function makeValidForm(overrides: Partial<CompanySignupFormValues> = {}): CompanySignupFormValues {
  return {
    email: 'haja@check.com',
    password: 'abcd1234',
    confirmPassword: 'abcd1234',
    companyName: '(주)하자체크',
    businessRegistrationNumber: '123-45-67890',
    representativeName: '김민수',
    address: '서울시 강남구 테헤란로 1',
    businessRegistrationFile: makeFile(),
    agreeToTerms: true,
    ...overrides,
  };
}

describe('isCompanySignupFormValid', () => {
  it('모든 값이 유효하면 true를 반환한다', () => {
    expect(isCompanySignupFormValid(makeValidForm())).toBe(true);
  });

  it('이메일 형식이 잘못되면 false를 반환한다', () => {
    expect(isCompanySignupFormValid(makeValidForm({ email: 'invalid' }))).toBe(false);
  });

  it('비밀번호가 일치하지 않으면 false를 반환한다', () => {
    expect(isCompanySignupFormValid(makeValidForm({ confirmPassword: 'different1' }))).toBe(false);
  });

  it('사업자등록번호 형식이 잘못되면 false를 반환한다', () => {
    expect(
      isCompanySignupFormValid(makeValidForm({ businessRegistrationNumber: '123' })),
    ).toBe(false);
  });

  it('파일이 없으면 false를 반환한다', () => {
    expect(isCompanySignupFormValid(makeValidForm({ businessRegistrationFile: null }))).toBe(
      false,
    );
  });

  it('약관 미동의면 false를 반환한다', () => {
    expect(isCompanySignupFormValid(makeValidForm({ agreeToTerms: false }))).toBe(false);
  });

  it('회사명이 공백뿐이면 false를 반환한다', () => {
    expect(isCompanySignupFormValid(makeValidForm({ companyName: '   ' }))).toBe(false);
  });
});
