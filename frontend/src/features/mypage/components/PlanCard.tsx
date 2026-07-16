import { useState } from 'react';
import { Button } from '../../../shared/components/Button/Button';
import { useUpgradeInquiry } from '../hooks/useUpgradeInquiry';
import { PLAN_STATUS_BADGE_CLASS } from '../statusClasses';
import { MYPAGE_ERROR_CODE, type MyPlanInfo } from '../types';
import { PLAN_NAME_LABEL, PLAN_STATUS_LABEL, formatPriceMonthly } from '../utils/planFormat';

type Props = {
  plan: MyPlanInfo;
};

// 요금제 카드 — Figma "My Page - My Plan Management"(node 1-2776). 다음 결제일은 데이터 소스
// 없음(contract.md 범위 제외) → placeholder 문구 유지. "플랜 관리"는 후속(비활성), "업그레이드
// 문의"는 POST upgrade-inquiry 연동. 배지 색상은 statusClasses.ts 참조(success/warning 토큰 부재 사유).
export function PlanCard({ plan }: Props) {
  const upgradeInquiry = useUpgradeInquiry();
  const [justRequested, setJustRequested] = useState(false);

  const isUpgradeRequested = plan.status === 'UPGRADE_REQUESTED' || justRequested;
  const upgradeError = upgradeInquiry.error;

  const handleUpgradeInquiry = () => {
    upgradeInquiry.mutate(undefined, {
      onSuccess: () => setJustRequested(true),
    });
  };

  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-[40px] leading-none font-semibold text-heading">
            {PLAN_NAME_LABEL[plan.name] ?? plan.name}
          </span>
          <span
            className={`rounded-full border px-3 py-1 text-xs font-bold tracking-wider uppercase ${PLAN_STATUS_BADGE_CLASS[plan.status]}`}
          >
            {PLAN_STATUS_LABEL[plan.status] ?? plan.status}
          </span>
        </div>

        <div className="flex gap-3">
          <Button type="button" variant="secondary" disabled>
            플랜 관리
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={handleUpgradeInquiry}
            disabled={isUpgradeRequested || upgradeInquiry.isPending}
          >
            {isUpgradeRequested ? '업그레이드 문의 완료' : '업그레이드 문의'}
          </Button>
        </div>
      </div>

      <p className="text-sm text-text-muted">
        {formatPriceMonthly(plan.priceMonthly)} · 다음 결제일 정보는 준비 중입니다.
      </p>

      {upgradeInquiry.isError && (
        <p className="text-sm text-danger" role="alert">
          {upgradeError?.code === MYPAGE_ERROR_CODE.PLAN_FORBIDDEN
            ? '플랜 소유자만 업그레이드를 문의할 수 있습니다.'
            : '업그레이드 문의 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'}
        </p>
      )}
    </section>
  );
}
