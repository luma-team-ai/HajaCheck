export interface PricingTier {
  name: string;
  price: string;
  period?: string;
  badge?: string;
  features: string[];
  ctaLabel: string;
  inverted?: boolean;
}

export const PRICING_TIERS: PricingTier[] = [
  {
    name: 'Starter',
    price: '₩0',
    period: '/월',
    features: ['기본 시설물 관리', '기본 점검 관리'],
    ctaLabel: '무료 시작',
  },
  {
    name: 'Professional',
    price: '₩290,000',
    period: '/월',
    badge: 'MOST POPULAR',
    features: ['무제한 시설물 관리', '고급 AI 분석 및 리포트', '팀 5명까지 초대'],
    ctaLabel: '14일 무료 체험',
    inverted: true,
  },
  {
    name: 'Enterprise',
    price: 'Custom',
    features: ['Professional 모든 기능', '기업 EWM/SSO 시스템 연동', '전담 고객 매니저'],
    ctaLabel: '도입 문의',
  },
];

export const NAV_ITEMS = ['대시보드', '점검 관리', '시설물 정보', 'AI 분석', '고객센터'];

export const PARTNERS = ['HUG', '한국공인중개사협회', '국토교통부', '한국시설안전관리원'];

export const FOOTER_LINKS = [
  { title: '제품', links: ['시설물 관리', '점검 관리', 'AI 분석'] },
  { title: '회사', links: ['회사 소개', '블로그', '채용'] },
  { title: '법적 고지', links: ['이용약관', '개인정보처리방침'] },
];
