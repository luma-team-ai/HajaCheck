import { useState } from 'react';
import { useUpgradeInquiry } from '../hooks/useUpgradeInquiry';
import type { MyPlanInfo } from '../types';
import { PLAN_NAME_LABEL, PLAN_STATUS_LABEL, formatPriceMonthly } from '../utils/planFormat';

type Props = {
  plan: MyPlanInfo;
};

// 요금제 카드 — Figma "My Page - My Plan Management". 다음 결제일은 데이터 소스 없음(contract.md 범위 제외)
// → placeholder. "플랜 관리"는 후속(비활성), "업그레이드 문의"는 POST upgrade-inquiry 연동.
export function PlanCard({ plan }: Props) {
  const upgradeInquiry = useUpgradeInquiry();
  const [justRequested, setJustRequested] = useState(false);

  const isUpgradeRequested = plan.status === 'UPGRADE_REQUESTED' || justRequested;
  const upgradeError = upgradeInquiry.error as { code?: string } | null;

  const handleUpgradeInquiry = () => {
    upgradeInquiry.mutate(undefined, {
      onSuccess: () => setJustRequested(true),
    });
  };

  return (
    <section className="dashboard-card mypage-plan-card">
      <div className="mypage-plan-card-header">
        <div className="mypage-plan-card-title-group">
          <span className="mypage-plan-name">{PLAN_NAME_LABEL[plan.name] ?? plan.name}</span>
          <span
            className={`mypage-plan-status-badge mypage-plan-status-badge--${plan.status.toLowerCase()}`}
          >
            {PLAN_STATUS_LABEL[plan.status] ?? plan.status}
          </span>
        </div>
        <p className="mypage-plan-price">{formatPriceMonthly(plan.priceMonthly)}</p>
      </div>

      <p className="dashboard-card-status mypage-plan-next-billing">
        다음 결제일 정보는 준비 중입니다.
      </p>

      <div className="mypage-plan-card-actions">
        <button
          type="button"
          className="mypage-btn mypage-btn--secondary"
          disabled
          aria-disabled="true"
        >
          플랜 관리
        </button>
        <button
          type="button"
          className="mypage-btn mypage-btn--primary"
          onClick={handleUpgradeInquiry}
          disabled={isUpgradeRequested || upgradeInquiry.isPending}
        >
          {isUpgradeRequested ? '업그레이드 문의 완료' : '업그레이드 문의'}
        </button>
      </div>

      {upgradeInquiry.isError && (
        <p className="dashboard-card-status mypage-status--error" role="alert">
          {upgradeError?.code === 'PLAN_FORBIDDEN'
            ? '플랜 소유자만 업그레이드를 문의할 수 있습니다.'
            : '업그레이드 문의 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'}
        </p>
      )}
    </section>
  );
}
