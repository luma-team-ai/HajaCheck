import partnerLogo1 from '../../assets/brand/landing-partner-1.svg';
import partnerLogo2 from '../../assets/brand/landing-partner-2.svg';
import partnerLogo3 from '../../assets/brand/landing-partner-3.svg';
import partnerLogo4 from '../../assets/brand/landing-partner-4.svg';

export interface PricingFeature {
  label: string;
  included?: boolean;
}

export interface PricingTier {
  name: string;
  subtitle: string;
  price: string;
  period?: string;
  badge?: string;
  features: PricingFeature[];
  ctaLabel: string;
  inverted?: boolean;
}

export const PRICING_TIERS: PricingTier[] = [
  {
    name: 'Starter',
    subtitle: '소규모 단일 시설물 관리에 적합',
    price: '₩0',
    period: '/월',
    features: [
      { label: '기본 시설물 정보 등록' },
      { label: '기본 점검 캘린더' },
      { label: 'AI 하자 분석', included: false },
    ],
    ctaLabel: '무료 시작',
  },
  {
    name: 'Professional',
    subtitle: '복수 시설물 및 AI 분석이 필요한 기업',
    price: '₩290,000',
    period: '/월',
    badge: 'MOST POPULAR',
    features: [
      { label: '무제한 시설물 등록' },
      { label: '모바일 점검 앱 제공' },
      { label: '월 500회 AI 하자 분석' },
    ],
    ctaLabel: '14일 무료 체험',
    inverted: true,
  },
  {
    name: 'Enterprise',
    subtitle: '대규모 인프라 및 맞춤형 구축',
    price: 'Custom',
    features: [
      { label: 'Professional 모든 기능' },
      { label: '기존 ERP/BIM 시스템 연동' },
      { label: '전담 엔지니어 지원' },
    ],
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
  { name: '한국도로공사', logo: partnerLogo2 },
  { name: '국토교통부', logo: partnerLogo3 },
  { name: '한국시설안전공단', logo: partnerLogo4 },
];

export const FOOTER_LINKS = [
  { title: '제품', links: ['시설물 정보 관리', '검사(점검) 관리', 'AI 하자 분석', '요금제'] },
  { title: '회사', links: ['소개', '블로그', '채용', '문의하기'] },
  { title: '법적 고지', links: ['이용약관', '개인정보처리방침'] },
];
