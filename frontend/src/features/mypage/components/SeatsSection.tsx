import { Button } from '../../../shared/components/Button/Button';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useSeats } from '../hooks/useSeats';
import { SEAT_ROLE_BADGE_CLASS, SEAT_STATUS_DOT_CLASS } from '../statusClasses';
import {
  MYPAGE_ERROR_CODE,
  type SeatMember,
  type SeatMemberRole,
  type SeatMemberStatus,
} from '../types';
import { formatLimit } from '../utils/planFormat';

// SeatMemberRole/SeatMemberStatus 유니온 기준 Record — 백엔드 enum에 값이 추가되면 여기서 컴파일 에러로 드러남
const ROLE_LABEL: Record<SeatMemberRole, string> = {
  ADMIN: '관리자',
  INSPECTOR: '점검자',
  USER: '일반',
  COUNSELOR: '상담원',
};

const STATUS_LABEL: Record<SeatMemberStatus, string> = {
  ACTIVE: '활성',
  SUSPENDED: '정지',
  INVITED: '초대됨',
};

type Props = {
  /**
   * 행별 "작업"(⋯ 메뉴·초대 취소) 열 노출 여부. 기본 false — 기존 /mypage/plan(MyPlanPage)은
   * 이 prop 없이 호출되어 종전과 동일하게 렌더된다(#659 회귀 방지). true는 MyProfilePage 전용.
   * 백엔드에 초대·행별 액션 API가 없어(grep 0건, #294 handoff §4, 후속 #24/#210) 열은 렌더하되
   * 버튼은 항상 disabled로 둔다.
   */
  showActions?: boolean;
  /**
   * 실 API(useSeats) 응답에 없는 데모 전용 멤버를 표시 목록 끝에 덧붙인다(#659 — '초대됨' 상태
   * 데모). data.used/limit 등 실 사용량 집계에는 포함하지 않는다. 기본 빈 배열.
   */
  extraDemoMembers?: SeatMember[];
};

// 좌석 현황(조회 전용, contract.md) — "점검자 초대" 버튼은 백엔드 초대 API가 없어 항상 비활성
// (grep 0건, #294 handoff §4, 후속 #24/#210). "작업"(행별 액션) 열은 showActions=true(MyProfilePage
// 전용)일 때만 렌더하며, 마찬가지로 근거 API가 없어 버튼은 항상 disabled로 둔다.
export function SeatsSection({ showActions = false, extraDemoMembers = [] }: Props) {
  const { data, isLoading, isError, error } = useSeats();
  const errorCode = error?.code;
  const members = data ? [...data.members, ...extraDemoMembers] : [];

  return (
    <section className="flex flex-col gap-4 py-6 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-xl font-semibold text-heading">
          점검자 좌석{data ? ` (${data.used}/${formatLimit(data.limit)})` : ''}
        </h3>
        <Button type="button" variant="secondary" disabled>
          점검자 초대
        </Button>
      </div>

      {isLoading && <LoadingSpinner className="flex items-center justify-start gap-2" />}

      {isError && errorCode === MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="text-sm text-text-muted">활성 구독이 없어 좌석 정보를 표시할 수 없습니다.</p>
      )}
      {isError && errorCode !== MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="text-sm text-text-muted">좌석 정보를 불러오지 못했습니다.</p>
      )}

      {!isLoading && !isError && data && members.length === 0 && (
        <p className="text-sm text-text-muted">등록된 좌석 멤버가 없습니다.</p>
      )}

      {!isLoading && !isError && data && members.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[480px] border-collapse text-sm">
            <thead>
              <tr>
                <th className="border-b border-border bg-surface-muted px-3 py-2.5 text-left text-xs font-semibold whitespace-nowrap text-text-muted">
                  사용자
                </th>
                <th className="border-b border-border bg-surface-muted px-3 py-2.5 text-left text-xs font-semibold whitespace-nowrap text-text-muted">
                  이메일
                </th>
                <th className="border-b border-border bg-surface-muted px-3 py-2.5 text-left text-xs font-semibold whitespace-nowrap text-text-muted">
                  역할
                </th>
                <th className="border-b border-border bg-surface-muted px-3 py-2.5 text-left text-xs font-semibold whitespace-nowrap text-text-muted">
                  상태
                </th>
                {showActions && (
                  <th className="border-b border-border bg-surface-muted px-3 py-2.5 text-right text-xs font-semibold whitespace-nowrap text-text-muted">
                    작업
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {members.map((member) => (
                <tr key={member.userId}>
                  <td className="border-b border-border px-3 py-3 whitespace-nowrap">{member.name}</td>
                  <td className="border-b border-border px-3 py-3 whitespace-nowrap text-text-muted">
                    {member.email}
                  </td>
                  <td className="border-b border-border px-3 py-3 whitespace-nowrap">
                    <span
                      className={`rounded-full px-2.5 py-1 text-xs font-semibold ${SEAT_ROLE_BADGE_CLASS[member.role]}`}
                    >
                      {ROLE_LABEL[member.role] ?? member.role}
                    </span>
                  </td>
                  <td className="border-b border-border px-3 py-3 whitespace-nowrap">
                    <span className="inline-flex items-center gap-1.5">
                      <span
                        className={`h-1.5 w-1.5 rounded-full ${SEAT_STATUS_DOT_CLASS[member.status]}`}
                        aria-hidden="true"
                      />
                      {STATUS_LABEL[member.status] ?? member.status}
                    </span>
                  </td>
                  {showActions && (
                    <td className="border-b border-border px-3 py-3 text-right whitespace-nowrap">
                      <div className="inline-flex items-center gap-3">
                        {member.status === 'INVITED' && (
                          // BE 미구현 — 초대 취소 API 없음(후속 #24/#210), 렌더만 하고 클릭은 비활성
                          <button
                            type="button"
                            className="cursor-not-allowed text-sm font-medium text-danger opacity-60"
                            disabled
                          >
                            초대 취소
                          </button>
                        )}
                        {/* BE 미구현 — 행별 액션 메뉴 API 없음(후속 #24/#210), 렌더만 하고 클릭은 비활성 */}
                        <button
                          type="button"
                          className="inline-flex h-8 w-8 cursor-not-allowed items-center justify-center rounded-full text-text-muted opacity-60"
                          disabled
                          aria-label={`${member.name} 관리 메뉴`}
                        >
                          ⋯
                        </button>
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
