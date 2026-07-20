export type PolicyDocType = 'terms' | 'privacy';

export interface PolicyTabDef {
  docType: PolicyDocType;
  label: string;
  href: string;
  mdPath: string;
}

// 약관 페이지 라우트 — 랜딩 푸터·회원가입 약관 동의 링크가 공유하는 단일 소스
export const TERMS_OF_SERVICE_ROUTE = '/policy/terms-of-service';
export const PRIVACY_POLICY_ROUTE = '/policy/privacy';

export const POLICY_TABS: PolicyTabDef[] = [
  {
    docType: 'terms',
    label: '이용약관',
    href: '/policy/terms-of-service',
    mdPath: '/policies/terms-of-service.md',
  },
  {
    docType: 'privacy',
    label: '개인정보처리방침',
    href: '/policy/privacy',
    mdPath: '/policies/privacy-policy.md',
  },
];
