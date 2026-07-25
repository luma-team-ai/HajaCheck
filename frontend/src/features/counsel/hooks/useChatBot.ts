import { useCallback, useEffect, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import type { BotScenarioButtonResponse, CounselTicketSummaryResponse } from '../types';

let idSeq = 0;
function nextId() {
  idSeq += 1;
  return `bot-log-${Date.now()}-${idSeq}`;
}

export type ChatBotLogEntry =
  | { id: string; kind: 'bot-text'; text: string }
  | { id: string; kind: 'bot-buttons'; buttons: BotScenarioButtonResponse[] }
  | { id: string; kind: 'user-choice'; label: string }
  | { id: string; kind: 'ticket-created'; ticket: CounselTicketSummaryResponse };

const GREETING =
  '안녕하세요! 오늘 어떤 도움이 필요하신가요? 아래에서 원하시는 카테고리를 선택해 주세요.';

// 고객지원 > 상담 챗봇(#20, HAJA-33) — 시나리오 트리를 버튼 클릭으로 타고 내려가다가
// leadsToCounselor 리프에서 상담원 연결(티켓 생성)로 이어지는 대화 로그를 관리한다.
export function useChatBot() {
  const [log, setLog] = useState<ChatBotLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);

  const loadRoots = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await counselApi.getScenarioRoots();
      setLog([
        { id: nextId(), kind: 'bot-text', text: GREETING },
        { id: nextId(), kind: 'bot-buttons', buttons: res.data },
      ]);
    } catch (err) {
      setError(getApiErrorMessage(err, '상담 챗봇을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadRoots();
  }, [loadRoots]);

  const selectButton = useCallback(async (button: BotScenarioButtonResponse) => {
    setLog((prev) => [...prev, { id: nextId(), kind: 'user-choice', label: button.buttonLabel }]);

    if (button.leadsToCounselor) {
      setConnecting(true);
      setError(null);
      try {
        const res = await counselApi.createTicket({ scenarioId: button.id });
        setLog((prev) => [...prev, { id: nextId(), kind: 'ticket-created', ticket: res.data }]);
      } catch (err) {
        setError(getApiErrorMessage(err, '상담원 연결에 실패했습니다.'));
      } finally {
        setConnecting(false);
      }
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const res = await counselApi.getScenarioNode(button.id);
      setLog((prev) => [
        ...prev,
        ...(res.data.responseText
          ? [{ id: nextId(), kind: 'bot-text' as const, text: res.data.responseText }]
          : []),
        { id: nextId(), kind: 'bot-buttons' as const, buttons: res.data.children },
      ]);
    } catch (err) {
      setError(getApiErrorMessage(err, '다음 안내를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  return { log, loading, error, connecting, selectButton, retry: loadRoots };
}
