import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { PlanCard } from '../components/PlanCard';
import { SeatsSection } from '../components/SeatsSection';
import { UsageSection } from '../components/UsageSection';
import { useMyPlan } from '../hooks/useMyPlan';
import { mockInvitedSeatMember } from '../mocks/mypage.mock';
import { MYPAGE_ERROR_CODE } from '../types';

// 마이페이지 — 내 정보(HAJA-361, #659 / Figma "마이페이지 > 내 정보"). MyPlanPage(#212)와 동일한
// useMyPlan/useSeats 데이터 소스를 그대로 재사용하고(PlanCard/UsageSection은 그대로, SeatsSection은
// showActions=true로 "작업" 열만 추가) 셸 구조(흰 카드 하나, 섹션 사이 divide-y)도 동일 전략을 따른다
// (#293 auth.css와 같은 이유로 feature 전용 마크업을 자체 구성 — 공유 클래스 결합 최소화).
//
// '초대됨' 상태는 실 UserStatus에 없는 프론트 전용 값(types.ts 참고)이라, mockInvitedSeatMember를
// SeatsSection의 extraDemoMembers로 얹어 데모로만 보여준다 — 초대·행별 액션 실구현은 후속 #24/#210.
// BillingHistoryPlaceholder는 Figma "내 정보" 시안에 없는 섹션이라 MyPlanPage와 달리 렌더하지 않는다.
export function MyProfilePage() {
  const { data, isLoading, isError, error } = useMyPlan();
  const errorCode = error?.code;

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <h1 className="text-2xl font-bold text-heading">내 정보</h1>

      <div className="mx-auto flex w-full max-w-5xl flex-col divide-y divide-border rounded-[20px] border border-border bg-white p-8 shadow-sm">
        {isLoading && (
          <LoadingSpinner className="flex items-center justify-start gap-2 py-6 first:pt-0" />
        )}

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

        <SeatsSection showActions extraDemoMembers={[mockInvitedSeatMember]} />
      </div>
    </div>
  );
}
