import { useAuthStore } from '../../auth/store/authStore';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { PlanCard } from '../components/PlanCard';
import { ProfileSection } from '../components/ProfileSection';
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
// 결제 이력은 Figma "내 정보" 시안에 없는 섹션이라 렌더하지 않는다 — MyPlanPage는 PlanCard 상단
// "결제 내역" 버튼(모달)으로 노출한다(#712 리디자인, 구 BillingHistoryPlaceholder 섹션 대체).
//
// 내 프로필 섹션(HAJA-403, #744) — 요금제 섹션 위 최상단에 로그인 본인 정보(이름·이메일·가입일·
// 소속 기업)를 추가한다. authStore.user를 그대로 쓴다 — 이 화면은 항상 ProtectedRoute(AppShellRoute)
// 하위에서만 렌더되고, AuthGate 부트스트랩(getMe())이 이미 user를 채워둔 뒤라 이 페이지 전용 API
// 재호출이 불필요하다. user가 없는(이론상 불가능하지만 방어적) 경우에도 플랜/사용량/좌석 섹션은
// 계속 렌더되도록 프로필 실패가 화면 전체를 막지 않는다(React_코드_컨벤션.md §5 4상태 처리와 정합).
export function MyProfilePage() {
  const user = useAuthStore((state) => state.user);
  const { data, isLoading, isError, error } = useMyPlan();
  const errorCode = error?.code;

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <h1 className="text-2xl font-bold text-heading">내 정보</h1>

      <div className="mx-auto flex w-full max-w-5xl flex-col divide-y divide-border rounded-[20px] border border-border bg-white p-8 shadow-sm">
        {user ? (
          <ProfileSection user={user} />
        ) : (
          <p className="py-6 text-sm text-danger first:pt-0" role="alert">
            프로필 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
        )}

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
