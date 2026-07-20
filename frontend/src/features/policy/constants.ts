export type PolicyDocType = 'terms' | 'privacy';

export interface PolicyTabDef {
  docType: PolicyDocType;
  label: string;
  href: string;
  mdPath: string;
}

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
