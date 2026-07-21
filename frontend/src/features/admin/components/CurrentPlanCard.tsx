import { PLAN_DETAILS, PLAN_LABEL } from '../planQuota.constants';
import type { AdminUserPlan } from '../planQuota.types';

interface CurrentPlanCardProps {
  /** 로그인한 관리자 소속 회사(company_id)의 현재 플랜 — 표의 행 선택과 무관하게 고정값(#508 확정) */
  plan?: AdminUserPlan | null;
  /** 플랜 CTA 클릭 — 실제 플랜 변경은 백엔드 미구현이라 상위에서 안내 메시지로 처리 */
  onPlanAction: () => void;
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

// "현재 플랜" 카드 — Figma node-id 1197-3519(image 13 placeholder를 실제 카드로 구현).
// 로그인한 관리자의 회사 플랜(PlanQuotaStats.companyPlan)을 그대로 렌더한다 — 표에서 어느 멤버를
// 보고 있는지와는 무관하다(#508 확정: "현재 플랜은 company_id 기준 하나만"). 조회 전(undefined)엔
// 로딩 자리, 활성 구독이 없으면(null) 안내만 표시.
export function CurrentPlanCard({ plan, onPlanAction }: CurrentPlanCardProps) {
  if (plan === undefined) {
    return (
      <div className="flex h-full items-center justify-center rounded-[20px] border border-dashed border-border p-6 text-center text-sm text-text-muted">
        불러오는 중...
      </div>
    );
  }

  if (!plan) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-1 rounded-[20px] border border-border bg-surface-muted p-6 text-center">
        <span className="text-sm font-semibold text-heading">활성 구독 없음</span>
        <span className="text-[13px] text-text-muted">현재 활성 플랜이 없습니다.</span>
      </div>
    );
  }

  const detail = PLAN_DETAILS[plan];
  const priceText = detail.priceMonthly === 0 ? '₩0' : `₩${detail.priceMonthly.toLocaleString('ko-KR')}`;

  return (
    <div className="flex flex-col gap-4 rounded-[20px] border border-border bg-surface p-5">
      <div>
        <p className="text-base font-bold text-heading">{PLAN_LABEL[detail.name]}</p>
        <p className="mt-0.5 text-xs text-text-muted">{detail.tagline}</p>
      </div>

      <p className="flex items-baseline gap-1">
        <span className="text-3xl font-bold text-heading">{priceText}</span>
        <span className="text-sm text-text-muted">/월</span>
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
        onClick={onPlanAction}
      >
        {detail.ctaLabel}
      </button>
    </div>
  );
}
