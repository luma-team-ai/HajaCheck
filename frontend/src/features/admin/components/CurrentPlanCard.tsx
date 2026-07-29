import { useNavigate } from 'react-router-dom';
import { getApiErrorMessage } from '../../../shared/api/types';
import { useCancelScheduledPlanChange } from '../hooks/useCancelScheduledPlanChange';
import { buildPlanDetail, formatScheduledDate, PLAN_LABEL } from '../planQuota.constants';
import type { AdminPlanCatalogItem, AdminScheduledPlanChange, AdminUserPlan } from '../planQuota.types';

interface CurrentPlanCardProps {
  /** 로그인한 관리자 소속 회사(company_id)의 현재 플랜 — 표의 행 선택과 무관하게 고정값(#508 확정) */
  plan?: AdminUserPlan | null;
  /** GET /api/admin/plans 카탈로그 — 가격·기능 한도를 여기서 조회해 렌더한다(plans 테이블이 SOT). */
  catalog?: AdminPlanCatalogItem[];
  /** 대기 중인 하향 예약(#1105 / HAJA-526, #1191). 없으면 null — 있으면 배너+취소 버튼을 보여준다. */
  scheduledChange?: AdminScheduledPlanChange | null;
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

// 대기 중 하향 예약 배너(#1105 / HAJA-526, #1191) — "다음 결제일 적용"을 선택하면 신청 시점엔
// 아무것도 바뀌지 않고 effectiveAt(=신청 시점 currentPeriodEnd)에만 실행된다. 취소는 되돌릴 수 있는
// 동작이라(다시 예약·즉시 변경 모두 가능) 별도 확인 모달 없이 바로 요청한다.
function ScheduledChangeBanner({ scheduledChange }: { scheduledChange: AdminScheduledPlanChange }) {
  const { cancelScheduledChange, isPending, error, resetError } = useCancelScheduledPlanChange();
  const effectiveDate = formatScheduledDate(scheduledChange.effectiveAt);

  function handleCancel() {
    resetError();
    cancelScheduledChange().catch(() => {
      // 에러는 아래 error(mutation.error)로 표시한다 — unhandled rejection 콘솔 노출 방지.
    });
  }

  return (
    <div className="flex flex-col gap-2 rounded-2xl border border-[#f97316]/40 bg-[#fff7ed] p-3">
      <p className="m-0 text-[13px] font-semibold text-[#9a3412]">
        {PLAN_LABEL[scheduledChange.targetPlanName]}로 변경 예정{effectiveDate ? ` (${effectiveDate})` : ''}
      </p>
      <p className="m-0 text-xs text-[#9a3412]">
        그때까지 현재 요금제를 그대로 사용합니다. 좌석도 그날 정리됩니다.
      </p>
      {error && (
        <p role="alert" className="m-0 text-xs text-danger">
          {getApiErrorMessage(error, '예약 취소에 실패했습니다.')}
        </p>
      )}
      <button
        type="button"
        className="self-start rounded-full border border-[#f97316]/50 bg-surface px-3 py-1.5 text-xs font-semibold text-[#9a3412] hover:bg-[#fff7ed] disabled:cursor-not-allowed disabled:opacity-50"
        onClick={handleCancel}
        disabled={isPending}
      >
        {isPending ? '취소 중...' : '예약 취소'}
      </button>
    </div>
  );
}

// "현재 플랜" 카드 — Figma node-id 1197-3519(image 13 placeholder를 실제 카드로 구현).
// 로그인한 관리자의 회사 플랜(PlanQuotaStats.companyPlan)을 그대로 렌더한다 — 표에서 어느 멤버를
// 보고 있는지와는 무관하다(#508 확정: "현재 플랜은 company_id 기준 하나만"). 조회 전(undefined)엔
// 로딩 자리, 활성 구독이 없으면(null) 안내만 표시.
export function CurrentPlanCard({ plan, catalog, scheduledChange }: CurrentPlanCardProps) {
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

      {scheduledChange && <ScheduledChangeBanner scheduledChange={scheduledChange} />}

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
