import { useCallback, useEffect, useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { DEFAULT_PAGE_SIZE } from '../constants';
import type { CounselTicketDetailResponse, CounselTicketSummaryResponse } from '../types';

// 상담원 콘솔 > 대기열(#1001, HAJA-495) — GET /api/counsel/tickets 목록 + POST .../assign 클레임을
// 함께 관리한다. useCounselHistory와 동일한 loading/error 패턴을 따르되, 클레임은 동시성 경합이
// 있는 쓰기 동작이라(백엔드 낙관적 락, 409 COUNSEL_SESSION_ASSIGNMENT_CONFLICT) 별도 claimingId +
// conflictMessage 상태로 UX를 분리한다.
export function useCounselorQueue() {
  const [tickets, setTickets] = useState<CounselTicketSummaryResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [claimingId, setClaimingId] = useState<number | null>(null);
  // 클레임 실패 안내 — 409(경합)와 그 외 오류를 구분해 문구를 다르게 보여준다.
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);

  const loadQueue = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await counselApi.getQueue({ page: 0, size: DEFAULT_PAGE_SIZE });
      setTickets(res.data.content);
    } catch (err) {
      setError(getApiErrorMessage(err, '대기열을 불러오지 못했습니다.'));
      setTickets([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadQueue();
  }, [loadQueue]);

  // 클레임 성공 시 배정된 티켓을 돌려준다(호출부가 채팅 화면으로 navigate) — 실패 시 null.
  // 메시지는 서버 응답 그대로 노출한다(.status 기반 분기, 메시지 매칭 금지 — 프로젝트 리뷰 관례).
  // 409(COUNSEL_SESSION_ASSIGNMENT_CONFLICT, "이미 상담 세션이 배정된 티켓입니다.")면 큐를 자동
  // 새로고침해, 이미 사라진 항목(다른 상담원이 방금 가져간 티켓)이 목록에 남아있지 않도록 한다.
  const claim = useCallback(
    async (ticketId: number): Promise<CounselTicketDetailResponse | null> => {
      setClaimingId(ticketId);
      setConflictMessage(null);
      try {
        const res = await counselApi.assign(ticketId);
        return res.data;
      } catch (err) {
        setConflictMessage(getApiErrorMessage(err, '상담 배정에 실패했습니다.'));
        if ((err as ApiError).status === 409) {
          void loadQueue();
        }
        return null;
      } finally {
        setClaimingId(null);
      }
    },
    [loadQueue],
  );

  return {
    tickets,
    loading,
    error,
    reload: loadQueue,
    claim,
    claimingId,
    conflictMessage,
    dismissConflictMessage: () => setConflictMessage(null),
  };
}
