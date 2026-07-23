import { useNavigate } from 'react-router-dom';
import { buildPlanDetail, PLAN_LABEL } from '../planQuota.constants';
import type { AdminPlanCatalogItem, AdminUserPlan } from '../planQuota.types';

interface CurrentPlanCardProps {
  plan?: AdminUserPlan | null;
  /** GET /api/platform-admin/plans 카탈로그 — 가격·기능 한도를 여기서 조회해 렌더한다(plans 테이블이 SOT). */
  catalog?: AdminPlanCatalogItem[];
}

function CheckIcon({ muted }: { muted: boolean }) {
  return (
    <svg
      className={muted ? 'text-neutral-300' : 'text-primary'}
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      aria-hidden
    >
      <circle cx="8" cy="8" r="8" fill="currentColor" opacity={muted ? 0.4 : 0.12} />
      <path
        d="M4.5 8.2l2.2 2.2 4.3-4.6"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  );
}

// "현재 플랜" 카드 — features/admin/components/CurrentPlanCard.tsx(#508)를 그대로 옮긴 것(#625).
// 조회 전(undefined)엔 로딩 자리, 활성 구독이 없으면(null) 안내만 표시.
export function CurrentPlanCard({ plan, catalog }: CurrentPlanCardProps) {
  const navigate = useNavigate();

  if (plan === undefined || catalog === undefined) {
    return (
      <div className="flex h-full items-center justify-center rounded-[20px] border border-dashed border-border p-6 text-center text-sm text-text-muted">
        불러오는 중...
      </div>
    );
  }

  const catalogItem = plan ? catalog.find((item) => item.name === plan) : undefined;

  if (!plan || !catalogItem) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-1 rounded-[20px] border border-border bg-surface-muted p-6 text-center">
        <span className="text-sm font-semibold text-heading">활성 구독 없음</span>
        <span className="text-[13px] text-text-muted">현재 활성 플랜이 없습니다.</span>
      </div>
    );
  }

  const detail = buildPlanDetail(catalogItem);
  const priceText =
    detail.priceMonthly === null
      ? '가격 문의'
      : detail.priceMonthly === 0
        ? '₩0'
        : `₩${detail.priceMonthly.toLocaleString('ko-KR')}`;

  return (
    <div className="flex flex-col gap-4 rounded-[20px] border border-border bg-surface p-5">
      <div>
        <p className="text-base font-bold text-heading">{PLAN_LABEL[detail.name]}</p>
        <p className="mt-0.5 text-xs text-text-muted">{detail.tagline}</p>
      </div>

      <p className="flex items-baseline gap-1">
        <span className="text-3xl font-bold text-heading">{priceText}</span>
        {detail.priceMonthly !== null && <span className="text-sm text-text-muted">/월</span>}
      </p>

      <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
        {detail.features.map((feature) => (
          <li
            key={feature.label}
            className={`flex items-center gap-2 text-[13px] ${
              feature.included ? 'text-text-default' : 'text-text-muted line-through'
            }`}
          >
            <CheckIcon muted={!feature.included} />
            {feature.label}
          </li>
        ))}
      </ul>

      <button
        type="button"
        className="mt-1 w-full rounded-full border border-border bg-surface py-2.5 text-[13px] font-semibold text-text-default hover:border-primary hover:text-primary"
        onClick={() => navigate('/mypage/plan')}
      >
        {detail.ctaLabel}
      </button>
    </div>
  );
}
