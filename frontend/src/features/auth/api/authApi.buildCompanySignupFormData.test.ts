// @vitest-environment jsdom
// 기업 회원가입 multipart 요청 변환 로직 단위 테스트 — 실제 HTTP 라운드트립(파일 파트 포함)은
// msw+jsdom+undici 조합의 환경 한계로 이 리포의 테스트 러너에서 안정적으로 재현되지 않아
// (텍스트 필드만 있는 multipart는 정상 동작 확인됨) 변환 로직만 별도로 검증한다.
import { describe, expect, it } from 'vitest';
import type { CompanySignupRequest } from '../types';
import { toCompanySignupFormData } from './authApi';

function makeRequest(overrides: Partial<CompanySignupRequest> = {}): CompanySignupRequest {
  return {
    email: 'haja@check.com',
    password: 'abcd1234',
    companyName: '(주)하자체크',
    businessRegistrationNumber: '123-45-67890',
    representativeName: '김민수',
    address: '서울시 강남구',
    addressDetail: '1층',
    agreeTermsOfService: true,
    agreePrivacyPolicy: true,
    businessRegistrationFile: new File(['x'], 'license.pdf', { type: 'application/pdf' }),
    ...overrides,
  };
}

describe('toCompanySignupFormData', () => {
  it('계약 필드명과 1:1로 FormData를 구성한다', () => {
    const request = makeRequest();
    const formData = toCompanySignupFormData(request);

    expect(formData.get('email')).toBe('haja@check.com');
    expect(formData.get('password')).toBe('abcd1234');
    expect(formData.get('companyName')).toBe('(주)하자체크');
    expect(formData.get('businessRegistrationNumber')).toBe('123-45-67890');
    expect(formData.get('representativeName')).toBe('김민수');
    expect(formData.get('address')).toBe('서울시 강남구');
    expect(formData.get('addressDetail')).toBe('1층');
    expect(formData.get('agreeTermsOfService')).toBe('true');
    expect(formData.get('agreePrivacyPolicy')).toBe('true');
  });

  it('사업자등록증 파일을 File 파트로 포함한다', () => {
    const request = makeRequest();
    const formData = toCompanySignupFormData(request);

    const file = formData.get('businessRegistrationFile') as File;
    expect(file.name).toBe('license.pdf');
    expect(file.type).toBe('application/pdf');
  });

  it('agree 플래그가 false면 문자열 "false"로 직렬화한다', () => {
    const request = makeRequest({ agreeTermsOfService: false, agreePrivacyPolicy: false });
    const formData = toCompanySignupFormData(request);

    expect(formData.get('agreeTermsOfService')).toBe('false');
    expect(formData.get('agreePrivacyPolicy')).toBe('false');
  });
});
