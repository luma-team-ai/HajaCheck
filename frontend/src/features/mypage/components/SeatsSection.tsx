import { useSeats } from '../hooks/useSeats';
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

// 좌석 현황(조회 전용, contract.md) — "좌석 초대" 버튼은 후속 범위라 비활성만 노출
export function SeatsSection() {
  const { data, isLoading, isError, error } = useSeats();
  const errorCode = error?.code;

  return (
    <section className="dashboard-card mypage-seats-card">
      <div className="dashboard-card-header">
        <h3 className="dashboard-card-title">
          좌석{data ? ` (${data.used}/${formatLimit(data.limit)})` : ''}
        </h3>
        <button
          type="button"
          className="mypage-btn mypage-btn--secondary"
          disabled
          aria-disabled="true"
        >
          좌석 초대
        </button>
      </div>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}

      {isError && errorCode === MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="dashboard-card-status">활성 구독이 없어 좌석 정보를 표시할 수 없습니다.</p>
      )}
      {isError && errorCode !== MYPAGE_ERROR_CODE.PLAN_NOT_FOUND && (
        <p className="dashboard-card-status">좌석 정보를 불러오지 못했습니다.</p>
      )}

      {!isLoading && !isError && data && data.members.length === 0 && (
        <p className="dashboard-card-status">등록된 좌석 멤버가 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.members.length > 0 && (
        <div className="mypage-table-scroll">
          <table className="mypage-seats-table">
            <thead>
              <tr>
                <th>이름</th>
                <th>이메일</th>
                <th>역할</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {data.members.map((member) => (
                <tr key={member.userId}>
                  <td>{member.name}</td>
                  <td>{member.email}</td>
                  <td>{ROLE_LABEL[member.role] ?? member.role}</td>
                  <td>{STATUS_LABEL[member.status] ?? member.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
