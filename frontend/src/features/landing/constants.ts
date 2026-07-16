import partnerLogo1 from '../../assets/brand/landing-partner-1.svg';
import partnerLogo2 from '../../assets/brand/landing-partner-2.svg';
import partnerLogo3 from '../../assets/brand/landing-partner-3.svg';
import partnerLogo4 from '../../assets/brand/landing-partner-4.svg';

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

export interface NavItem {
  label: string;
  targetId: string;
}

// 각 항목은 랜딩페이지 내 실제 섹션(id)과 1:1 대응 — 매칭되는 섹션이 없던 '대시보드'·'고객센터'는
// '파트너사'(#partners)·'요금제'(#pricing)로 대체
export const NAV_ITEMS: NavItem[] = [
  { label: '파트너사', targetId: 'partners' },
  { label: '시설물 정보', targetId: 'facility-info' },
  { label: '점검 관리', targetId: 'inspection' },
  { label: 'AI 분석', targetId: 'ai-analysis' },
  { label: '요금제', targetId: 'pricing' },
];

export interface Partner {
  name: string;
  logo: string;
}

export const PARTNERS: Partner[] = [
  { name: 'HUG', logo: partnerLogo1 },
  { name: '한국공인중개사협회', logo: partnerLogo2 },
  { name: '국토교통부', logo: partnerLogo3 },
  { name: '한국시설안전관리원', logo: partnerLogo4 },
];

export const FOOTER_LINKS = [
  { title: '제품', links: ['시설물 관리', '점검 관리', 'AI 분석'] },
  { title: '회사', links: ['회사 소개', '블로그', '채용'] },
  { title: '법적 고지', links: ['이용약관', '개인정보처리방침'] },
];
