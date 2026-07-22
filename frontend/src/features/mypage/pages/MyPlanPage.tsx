import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { BillingHistoryPlaceholder } from '../components/BillingHistoryPlaceholder';
import { PlanCard } from '../components/PlanCard';
import { SeatsSection } from '../components/SeatsSection';
import { UsageSection } from '../components/UsageSection';
import { useMyPlan } from '../hooks/useMyPlan';
import { MYPAGE_ERROR_CODE } from '../types';

// 마이페이지 — 내 플랜 관리 (HAJA-185, #212 / Figma 전환 #294, HAJA-221 — node 1-2776
// "My Page - My Plan Management"). 흰 카드 하나 안에 섹션을 구분선(divide-y)으로 나눈 구조.
// 공용 dashboard-content/dashboard-card(shared/styles/layout.css)를 참조하지 않고 이 feature
// 전용 Tailwind 마크업으로 자체 셸을 구성해, Dashboard/DefectDetail이 공유하는 클래스와의 결합을
// 끊는다(#293 auth.css와 동일 전략 — 공유 클래스 변경 시 회귀 전파를 원천 차단).
export function MyPlanPage() {
  const { data, isLoading, isError, error } = useMyPlan();
  const errorCode = error?.code;

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <h1 className="text-2xl font-bold text-heading">내 플랜</h1>

      <div className="mx-auto flex w-full max-w-5xl flex-col divide-y divide-border rounded-[20px] border border-border bg-white p-8 shadow-sm">
        {isLoading && <LoadingSpinner className="justify-start py-6 first:pt-0" />}

        {isError && errorCode === MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
          <p className="py-6 text-sm text-danger first:pt-0" role="alert">
            활성 구독이 없습니다. 플랜을 먼저 신청해 주세요.
          </p>
        )}
        {isError && errorCode !== MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
          <p className="py-6 text-sm text-danger first:pt-0" role="alert">
            플랜 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
        )}

        {!isLoading && !isError && data && (
          <>
            <PlanCard plan={data.plan} />
            <UsageSection limits={data.limits} usage={data.usage} />
          </>
        )}

        <SeatsSection />
        <BillingHistoryPlaceholder />
      </div>
    </div>
  );
}
