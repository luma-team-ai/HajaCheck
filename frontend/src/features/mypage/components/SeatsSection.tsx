import { Button } from '../../../shared/components/Button/Button';
import { useSeats } from '../hooks/useSeats';
import { SEAT_ROLE_BADGE_CLASS, SEAT_STATUS_DOT_CLASS } from '../statusClasses';
import { MYPAGE_ERROR_CODE, type SeatMemberRole, type SeatMemberStatus } from '../types';
import { formatLimit } from '../utils/planFormat';

// SeatMemberRole/SeatMemberStatus 유니온 기준 Record — 백엔드 enum에 값이 추가되면 여기서 컴파일 에러로 드러남
const ROLE_LABEL: Record<SeatMemberRole, string> = {
  ADMIN: '관리자',
  INSPECTOR: '검사자',
  USER: '일반',
  COUNSELOR: '상담사',
};

const STATUS_LABEL: Record<SeatMemberStatus, string> = {
  ACTIVE: '활성',
  SUSPENDED: '정지',
};

// 좌석 현황(조회 전용, contract.md) — "점검자 초대" 버튼은 후속 범위라 비활성만 노출.
// 백엔드에 초대 API·INVITED 상태가 존재하지 않는다(grep 0건, #294 handoff §4). Figma가 보여주는
// "작업"(행별 액션) 열은 이 초대 기능과 짝을 이루는 UI라 함께 범위 밖으로 두고 구현하지 않는다 —
// 근거 API 없이 버튼만 그리면 눌러도 아무 일도 안 하는 가짜 UI가 되기 때문. 좌석 초대·행별 액션은
// 별도 이슈로 분리 예정.
export function SeatsSection() {
  const { data, isLoading, isError, error } = useSeats();
  const errorCode = error?.code;

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

      {isLoading && <p className="text-sm text-text-muted">불러오는 중...</p>}

      {isError && errorCode === MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="text-sm text-text-muted">활성 구독이 없어 좌석 정보를 표시할 수 없습니다.</p>
      )}
      {isError && errorCode !== MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="text-sm text-text-muted">좌석 정보를 불러오지 못했습니다.</p>
      )}

      {!isLoading && !isError && data && data.members.length === 0 && (
        <p className="text-sm text-text-muted">등록된 좌석 멤버가 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.members.length > 0 && (
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
              </tr>
            </thead>
            <tbody>
              {data.members.map((member) => (
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
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
