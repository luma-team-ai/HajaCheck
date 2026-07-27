import { useEffect, useState } from 'react';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { counselApi } from '../api/counselApi';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { getApiErrorMessage } from '../../../shared/api/types';
import type { CounselTicketDetailResponse, CounselTicketSummaryResponse } from '../types';

type Ticket = CounselTicketSummaryResponse | CounselTicketDetailResponse;

type Props = {
  ticket: Ticket | null;
};

type TabKey = 'info' | 'history';

// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 우측 정보 패널.
// 원 디자인엔 "상담 태그"/"비공개 메모 저장"/"매크로 안내 박스"가 있으나, 백엔드에 그 기능이 전혀
// 없다(CounselTicketController 확인 — 태그·메모 저장 API 없음, 매크로 기능 없음) — 동작하지 않는
// 가짜 UI를 만들지 않기 위해 해당 섹션은 생략한다. 고객 상담 이력은 GET .../customer-history(#1001
// 후속)로 실제 연동한다.
export function CounselorInfoPanel({ ticket }: Props) {
  const [tab, setTab] = useState<TabKey>('info');
  const [history, setHistory] = useState<CounselTicketSummaryResponse[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  useEffect(() => {
    if (tab !== 'history' || !ticket) {
      return;
    }
    let cancelled = false;
    setHistoryLoading(true);
    setHistoryError(null);
    counselApi
      .getCustomerHistory(ticket.id)
      .then((res) => {
        if (!cancelled) setHistory(res.data);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setHistoryError(getApiErrorMessage(err, '상담 이력을 불러오지 못했습니다.'));
          setHistory([]);
        }
      })
      .finally(() => {
        if (!cancelled) setHistoryLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // ticket.id 변경 시(다른 티켓 선택) 재조회 — tab을 'history'로 유지한 채 티켓만 바뀌는 경우 포함.
  }, [tab, ticket?.id]);

  return (
    <div className="flex w-72 shrink-0 flex-col border-l border-border bg-surface-muted">
      <div className="flex gap-1 p-4 pb-2">
        <button
          type="button"
          onClick={() => setTab('info')}
          aria-pressed={tab === 'info'}
          className={`flex-1 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            tab === 'info' ? 'bg-white text-primary shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]' : 'text-text-muted'
          }`}
        >
          정보/메모
        </button>
        <button
          type="button"
          onClick={() => setTab('history')}
          aria-pressed={tab === 'history'}
          className={`flex-1 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            tab === 'history' ? 'bg-white text-primary shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]' : 'text-text-muted'
          }`}
        >
          이력
        </button>
      </div>

      {!ticket && (
        <p className="px-5 py-4 text-sm text-text-muted">티켓을 선택하면 정보가 표시됩니다.</p>
      )}

      {ticket && tab === 'info' && (
        <div className="flex flex-col gap-4 overflow-y-auto px-4 pb-4">
          {/* 고객 프로필 — 백엔드에 고객 이름·회사·직함 필드가 없어(userId만 존재) 실제로 있는
              필드(고객 ID·티켓 정보)만 표시한다. */}
          <div className="flex flex-col items-center gap-2 rounded-2xl bg-white px-4 py-5 text-center shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" className="size-12" />
            <div>
              <p className="m-0 text-sm font-semibold text-primary">고객 #{ticket.userId}</p>
              <p className="m-0 mt-0.5 text-xs text-text-muted">{ticket.category}</p>
            </div>
          </div>

          <div className="flex flex-col gap-2 rounded-2xl bg-white px-4 py-4 text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <p className="m-0 text-xs font-semibold uppercase tracking-wide text-text-muted">티켓 정보</p>
            <dl className="m-0 flex flex-col gap-1.5">
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">티켓 번호</dt>
                <dd className="m-0 font-medium text-primary">{ticket.ticketNumber}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">제목</dt>
                <dd className="m-0 truncate font-medium text-primary">{ticket.title}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">접수일</dt>
                <dd className="m-0 font-medium text-primary">
                  {new Date(ticket.createdAt).toLocaleDateString('sv-SE').replaceAll('-', '.')}
                </dd>
              </div>
            </dl>
          </div>

          {/* 상담 태그(types.ts에 tag 관련 필드 없음)·비공개 메모 저장(저장 API 없음) 섹션은
              실제 기능이 없어 생략 — 가짜 저장 버튼을 두지 않는다. */}
        </div>
      )}

      {ticket && tab === 'history' && (
        <div className="flex flex-col gap-2 overflow-y-auto px-4 pb-4">
          {historyLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
          {historyError && <p className="px-1 text-sm text-red-600">{historyError}</p>}
          {!historyLoading && !historyError && history.length === 0 && (
            <p className="px-1 py-4 text-sm text-text-muted">이 고객의 다른 상담 이력이 없습니다.</p>
          )}
          {!historyLoading &&
            !historyError &&
            history.map((past) => (
              <div
                key={past.id}
                className="flex flex-col gap-1 rounded-2xl bg-white px-4 py-3 text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]"
              >
                <div className="flex items-center justify-between gap-2">
                  <p className="m-0 truncate text-sm font-semibold text-primary">{past.title}</p>
                  <span className="shrink-0 text-[11px] text-text-muted">
                    {new Date(past.createdAt).toLocaleDateString('sv-SE').replaceAll('-', '.')}
                  </span>
                </div>
                <p className="m-0 truncate text-text-muted">
                  {past.category} · #{past.ticketNumber} · {past.status}
                </p>
              </div>
            ))}
        </div>
      )}

      {/* 원 디자인의 매크로 안내("/답변 명령어로...") 박스는 매크로 기능 자체가 없어 생략. */}
    </div>
  );
}
