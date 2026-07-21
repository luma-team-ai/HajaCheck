import type { PlanQuotaUser } from '../planQuota.types';
import { QuotaUsageBar } from './QuotaUsageBar';
import { StateRow } from './StateRow';

const COL_COUNT = 2;

interface PlanQuotaTableProps {
  users: PlanQuotaUser[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

// 플랜·쿼터 사용자 표(좌측) — Figma node-id 1197-3519. 계정(이름·이메일) + 월 분석 쿼터 사용량 바.
// 우측 "현재 플랜" 카드는 로그인한 관리자의 회사 플랜 고정값이라(#508 확정) 행 선택과 무관하다 —
// 그래서 이 표는 조회 전용이고 행 클릭/선택 상태를 갖지 않는다.
export function PlanQuotaTable({ users, isLoading, isError, onRetry }: PlanQuotaTableProps) {
  return (
    <table className="w-full border-collapse">
      <thead>
        <tr className="border-b border-border">
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">사용자</th>
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">쿼터 사용량</th>
        </tr>
      </thead>
      <tbody>
        {isLoading && (
          <StateRow colSpan={COL_COUNT}>
            <span className="text-text-muted">불러오는 중...</span>
          </StateRow>
        )}

        {!isLoading && isError && (
          <StateRow colSpan={COL_COUNT}>
            <span className="flex flex-col items-center gap-3">
              <span className="text-danger" role="alert">
                목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
              </span>
              <button
                type="button"
                className="rounded-full border border-border px-4 py-1.5 text-sm text-text-default hover:border-primary hover:text-primary"
                onClick={onRetry}
              >
                다시 시도
              </button>
            </span>
          </StateRow>
        )}

        {!isLoading && !isError && users.length === 0 && (
          <StateRow colSpan={COL_COUNT}>
            <span className="text-text-muted">조건에 맞는 사용자가 없습니다</span>
          </StateRow>
        )}

        {!isLoading &&
          !isError &&
          users.map((user) => (
            <tr key={user.id} className="border-b border-border last:border-b-0">
              <td className="px-4 py-4 align-middle">
                <p className="text-sm font-semibold text-heading">{user.name}</p>
                <p className="text-[13px] text-text-muted">{user.email}</p>
              </td>
              <td className="px-4 py-4 align-middle">
                <QuotaUsageBar used={user.quotaUsed} limit={user.quotaLimit} label={user.name} />
              </td>
            </tr>
          ))}
      </tbody>
    </table>
  );
}
