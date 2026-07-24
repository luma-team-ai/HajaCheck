import type { User } from '../../auth/types';
import { formatJoinedDate } from '../utils/profileFormat';

type Props = {
  user: User;
};

// 내 프로필 섹션(HAJA-403, #744) — 마이페이지 "내 정보" 최상단에 로그인 본인 정보(이름·이메일·
// 가입일·소속 기업)를 라벨-값 나열 형태로 보여준다. 기존 PlanCard/SeatsSection과 같은 카드 셸 안에서
// divide-y로 구분되는 섹션 하나이므로, 별도 카드 테두리 없이 동일한 py-6 섹션 패턴만 따른다.
// companyName이 null이면 회사 미소속(개인 회원)이라는 뜻이라 "개인 회원"으로 표기한다
// (BE 계약 nullable 사유 — docs/api-contract/openapi.yaml UserResponse.companyName).
// user는 authStore(zustand)를 그대로 재사용한다 — AuthGate 부트스트랩(getMe())이 이미 채워둔
// 값이라 이 화면 전용 API 재호출이 불필요하다(features/platform-admin에서도 동일하게 auth/store를
// 직접 import하는 선례가 있다: PlatformAdminLoginPage.tsx).
const PROFILE_FIELDS: Array<{ label: string; getValue: (user: User) => string }> = [
  { label: '이름', getValue: (user) => user.name },
  { label: '이메일', getValue: (user) => user.email },
  { label: '가입일', getValue: (user) => formatJoinedDate(user.createdAt) },
  { label: '소속 기업', getValue: (user) => user.companyName ?? '개인 회원' },
];

export function ProfileSection({ user }: Props) {
  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <h3 className="text-xl font-semibold text-heading">내 프로필</h3>

      <dl className="grid grid-cols-1 gap-x-8 gap-y-3 sm:grid-cols-2">
        {PROFILE_FIELDS.map(({ label, getValue }) => (
          <div key={label} className="flex items-baseline gap-3">
            <dt className="w-20 shrink-0 text-sm font-medium text-text-muted">{label}</dt>
            <dd className="m-0 text-sm text-text-default">{getValue(user)}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
