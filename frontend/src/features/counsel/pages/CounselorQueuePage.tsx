import { useNavigate } from 'react-router-dom';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { STATUS_BADGE } from '../constants';
import { useCounselorQueue } from '../hooks/useCounselorQueue';

// 상담원 콘솔 > 대기열(#1001, HAJA-495) — GET /api/counsel/tickets 목록 + 클레임 버튼.
// 앱 셸(AppLayout: SideNavBar+Header)은 CounselorShellRoute가 감싸므로 여기서는 카드 본문만 렌더한다.
// 피그마 디자인이 아직 없어(디자인 링크 미확정) 기존 CounselHistoryPage 카드 레이아웃 스타일을
// 최소 변형으로 재사용했다 — 실 디자인 확정 시 마크업 재검토 필요.
export function CounselorQueuePage() {
  const navigate = useNavigate();
  const { tickets, loading, error, claim, claimingId, conflictMessage, dismissConflictMessage } =
    useCounselorQueue();

  async function handleClaim(ticketId: number) {
    const assigned = await claim(ticketId);
    if (assigned) {
      navigate(`/counsel-console/tickets/${assigned.id}`, { state: { ticket: assigned } });
    }
  }

  return (
    <div className="flex h-full flex-col bg-surface-muted p-5">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[20px] border border-border bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h1 className="m-0 text-lg font-semibold text-primary">상담 대기열 ({tickets.length}건)</h1>
        </div>

        {conflictMessage && (
          <div
            role="alert"
            className="mx-6 mt-4 flex items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-800"
          >
            <span>{conflictMessage}</span>
            <button
              type="button"
              onClick={dismissConflictMessage}
              className="shrink-0 text-xs font-medium text-amber-700 underline"
            >
              닫기
            </button>
          </div>
        )}

        <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4">
          {loading && <LoadingSpinner className="flex items-center justify-center py-6" />}
          {error && <p className="px-2 text-sm text-red-600">{error}</p>}
          {!loading && !error && tickets.length === 0 && (
            <p className="px-2 text-sm text-text-muted">대기 중인 상담 티켓이 없습니다.</p>
          )}
          {!loading &&
            !error &&
            tickets.map((ticket) => {
              const badge = STATUS_BADGE[ticket.status];
              const isClaiming = claimingId === ticket.id;
              return (
                <div
                  key={ticket.id}
                  className="flex items-center justify-between gap-3 rounded-2xl border border-border bg-white px-4 py-3 shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]"
                >
                  <div className="flex min-w-0 flex-col gap-1.5">
                    <div className="flex items-center gap-2">
                      <span className="rounded-full bg-surface-sunken px-2.5 py-0.5 text-xs font-medium text-text-muted">
                        {ticket.category}
                      </span>
                      <span className={`flex items-center gap-1 text-xs font-medium ${badge.textClassName}`}>
                        <span className={`size-1.5 rounded-full ${badge.dotClassName}`} aria-hidden="true" />
                        {badge.label}
                      </span>
                      {ticket.queuePosition !== null && (
                        <span className="text-xs text-text-muted">대기 순번 {ticket.queuePosition}</span>
                      )}
                    </div>
                    <p className="m-0 truncate text-sm font-semibold text-primary">{ticket.title}</p>
                    <p className="m-0 truncate text-xs text-text-muted">#{ticket.ticketNumber}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => void handleClaim(ticket.id)}
                    disabled={isClaiming}
                    className="shrink-0 rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                  >
                    {isClaiming ? '배정 중...' : '배정받기'}
                  </button>
                </div>
              );
            })}
        </div>
      </div>
    </div>
  );
}
