import '../../../shared/styles/layout.css';
import { BillingHistoryPlaceholder } from '../components/BillingHistoryPlaceholder';
import { PlanCard } from '../components/PlanCard';
import { SeatsSection } from '../components/SeatsSection';
import { UsageSection } from '../components/UsageSection';
import { useMyPlan } from '../hooks/useMyPlan';
import { MYPAGE_ERROR_CODE } from '../types';
import '../mypage.css';

// 마이페이지 — 내 플랜 관리 (HAJA-185, #212). Figma "My Page - My Plan Management".
export function MyPlanPage() {
  const { data, isLoading, isError, error } = useMyPlan();
  const errorCode = error?.code;

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">내 플랜</h1>
      </div>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}

      {isError && errorCode === MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="dashboard-card-status" role="alert">
          활성 구독이 없습니다. 플랜을 먼저 신청해 주세요.
        </p>
      )}
      {isError && errorCode !== MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="dashboard-card-status" role="alert">
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
  );
}
