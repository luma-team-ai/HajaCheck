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
    name: 'Free',
    subtitle: '체험·개인 사용자',
    price: '₩0',
    period: '/월',
    features: [
      { label: '시설물 1개 · 월 분석 50장' },
      { label: '점검자 좌석 없음(1인 계정)' },
      { label: 'AI 부가 기능(설명·브리핑)', included: false },
    ],
    ctaLabel: '무료 시작',
  },
  {
    name: 'Standard',
    subtitle: '소규모 점검 업체',
    price: '₩29,000',
    period: '/월',
    badge: 'MOST POPULAR',
    features: [
      { label: '시설물 10개 · 월 분석 1,000장' },
      { label: '점검자 좌석 3명 · 정식 PDF 보고서' },
      { label: 'AI 부가 기능 · 상담원 연결' },
    ],
    ctaLabel: '업그레이드 문의',
    inverted: true,
  },
  {
    name: 'Enterprise',
    subtitle: '관리업체·공공기관',
    price: '₩59,000',
    period: '/월',
    features: [
      { label: '시설물 무제한 · 월 분석 협의' },
      { label: '점검자 좌석 무제한 · 데이터 반출' },
      { label: 'AI 부가 기능 · 우선 응대' },
    ],
    ctaLabel: '도입 문의',
  },
];

export function formatPricingTiersFromApi(
  plans: Array<{
    id: number;
    name: string;
    maxFacilities: number | null;
    maxMonthlyAnalyses: number | null;
    maxSeats: number | null;
    hasPdfWatermark: boolean;
    hasCounselorAccess: boolean;
    hasAiAddon: boolean;
    priceMonthly: number;
  }>,
): PricingTier[] {
  if (!plans || plans.length === 0) return PRICING_TIERS;

  return plans.map((plan) => {
    const isFree = plan.name === 'FREE';
    const isStandard = plan.name === 'STANDARD';
    const isEnterprise = plan.name === 'ENTERPRISE';

    let name = plan.name;
    let subtitle = '';
    let ctaLabel = '무료 시작';
    let badge: string | undefined;
    let inverted = false;

    if (isFree) {
      name = 'Free';
      subtitle = '체험·개인 사용자';
      ctaLabel = '무료 시작';
    } else if (isStandard) {
      name = 'Standard';
      subtitle = '소규모 점검 업체';
      ctaLabel = '업그레이드 문의';
      badge = 'MOST POPULAR';
      inverted = true;
    } else if (isEnterprise) {
      name = 'Enterprise';
      subtitle = '관리업체·공공기관';
      ctaLabel = '도입 문의';
    }

    const price = plan.priceMonthly === 0 ? '₩0' : `₩${Math.floor(plan.priceMonthly).toLocaleString()}`;
    const period = '/월';

    const facilitiesText = plan.maxFacilities === null ? '시설물 무제한' : `시설물 ${plan.maxFacilities}개`;
    const analysesText = plan.maxMonthlyAnalyses === null ? '월 분석 협의' : `월 분석 ${plan.maxMonthlyAnalyses.toLocaleString()}장`;
    const feature1 = `${facilitiesText} · ${analysesText}`;

    let feature2 = '점검자 좌석 없음(1인 계정)';
    if (plan.maxSeats === null || plan.maxSeats >= 1000) {
      feature2 = '점검자 좌석 무제한 · 데이터 반출';
    } else if (plan.maxSeats > 1) {
      feature2 = `점검자 좌석 ${plan.maxSeats}명 · 정식 PDF 보고서`;
    }

    const features: PricingFeature[] = [
      { label: feature1 },
      { label: feature2 },
      {
        label: isFree ? 'AI 부가 기능(설명·브리핑)' : 'AI 부가 기능 · 상담원 연결',
        included: isFree ? false : plan.hasAiAddon || plan.hasCounselorAccess,
      },
    ];

    return {
      name,
      subtitle,
      price,
      period,
      badge,
      features,
      ctaLabel,
      inverted,
    };
  });
}

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
  {
    title: '제품',
    links: NAV_ITEMS.filter((item) => item.targetId !== 'partners').map((item) => ({
      label: item.label,
      href: `/#${item.targetId}`,
    })),
  },
  {
    title: '법적 고지',
    links: [
      { label: '이용약관', href: '/policy/terms-of-service' },
      { label: '개인정보처리방침', href: '/policy/privacy' },
    ],
  },
];
