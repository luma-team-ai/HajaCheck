import {
  PLAN_LABEL,
  PLAN_QUOTA_BADGE_CLASS,
  PLAN_QUOTA_EMPTY_CELL,
  PLAN_QUOTA_EXPIRED_LABEL,
  PLAN_QUOTA_STATUS_DOT_CLASS,
  PLAN_QUOTA_STATUS_LABEL,
  PLAN_QUOTA_STATUS_TEXT_CLASS,
  PLAN_QUOTA_UNLIMITED_PERIOD_LABEL,
} from '../planQuota.constants';
import type { PlanQuotaUser } from '../planQuota.types';
import { QuotaUsageBar } from './QuotaUsageBar';
import { StateRow } from './StateRow';

const COL_COUNT = 6;

interface PlanQuotaTableProps {
  users: PlanQuotaUser[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

// 플랜·쿼터 사용자 표 — Figma node-id 1206-2639(플랫폼 관리자 기준 화면). 소속 기업 / 사용자별
// 계정(이름·이메일) / 현재 플랜 / 월 분석 쿼터 사용량 바 / 남은 기간 / 상태(활성·주의·만료)를 한 행에
// 담는다. 전사 스코프라 사이드에 고정된 "현재 플랜" 카드를 두지 않으므로, 이 표가 화면 전체 너비를
// 차지한다. 기업 컬럼은 사용자 지시(#624 후속)로 사용자 컬럼 앞에 추가했다 — 전사 조회에서 어느 회사
// 소속인지가 사용자 이름보다 먼저 눈에 들어와야 한다는 판단.
export function PlanQuotaTable({ users, isLoading, isError, onRetry }: PlanQuotaTableProps) {
  return (
    <table className="w-full border-collapse">
      <thead>
        <tr className="border-b border-border">
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">기업</th>
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">사용자</th>
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">현재 플랜</th>
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">쿼터 사용량</th>
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">남은 기간</th>
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">상태</th>
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
                {user.companyName ? (
                  <span className="text-sm text-text-default">{user.companyName}</span>
                ) : (
                  <span className="text-text-muted">{PLAN_QUOTA_EMPTY_CELL}</span>
                )}
              </td>
              <td className="px-4 py-4 align-middle">
                <p className="text-sm font-semibold text-heading">{user.name}</p>
                <p className="text-[13px] text-text-muted">{user.email}</p>
              </td>
              <td className="px-4 py-4 align-middle">
                {user.plan ? (
                  <span
                    className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-medium ${PLAN_QUOTA_BADGE_CLASS[user.plan]}`}
                  >
                    {PLAN_LABEL[user.plan]}
                  </span>
                ) : (
                  <span className="text-text-muted">{PLAN_QUOTA_EMPTY_CELL}</span>
                )}
              </td>
              <td className="px-4 py-4 align-middle">
                <QuotaUsageBar used={user.quotaUsed} limit={user.quotaLimit} label={user.name} />
              </td>
              <td className="px-4 py-4 align-middle text-sm">
                {/*
                  만료 판정은 status 로만 한다(#1104/HAJA-525) — remainingDays=null 은 V27 이후
                  "만료"와 "무기한(FREE)" 둘 다를 뜻하므로 null 로 판정하면 FREE 회사가
                  "만료됨(빨강) + 상태 활성"으로 모순 표시된다. planQuota.constants 주석 참고.
                */}
                {user.status === 'EXPIRED' ? (
                  <span className="font-semibold text-danger">{PLAN_QUOTA_EXPIRED_LABEL}</span>
                ) : user.remainingDays === null ? (
                  <span className="text-text-muted">{PLAN_QUOTA_UNLIMITED_PERIOD_LABEL}</span>
                ) : (
                  <span className="text-text-default">{user.remainingDays}일</span>
                )}
              </td>
              <td className="px-4 py-4 align-middle">
                <span className="inline-flex items-center gap-1.5 text-sm">
                  <span
                    className={`inline-block h-2 w-2 rounded-full ${PLAN_QUOTA_STATUS_DOT_CLASS[user.status]}`}
                    aria-hidden
                  />
                  <span className={PLAN_QUOTA_STATUS_TEXT_CLASS[user.status]}>
                    {PLAN_QUOTA_STATUS_LABEL[user.status]}
                  </span>
                </span>
              </td>
            </tr>
          ))}
      </tbody>
    </table>
  );
}
