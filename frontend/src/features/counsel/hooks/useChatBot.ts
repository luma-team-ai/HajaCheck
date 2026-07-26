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
// initialCategory 지정 시(퀵상담 FAB 딥링크) 최상위 4개 버튼을 생략하고 그 카테고리의
// 하위 옵션으로 바로 진입한다 — 존재하지 않는 category면 일반 진입(전체 카테고리)으로 폴백.
export function useChatBot(initialCategory?: string) {
  const [log, setLog] = useState<ChatBotLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);

  const loadRoots = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await counselApi.getScenarioRoots();
      const matchedRoot = initialCategory
        ? res.data.find((b) => b.category === initialCategory)
        : undefined;

      if (matchedRoot) {
        const node = await counselApi.getScenarioNode(matchedRoot.id);
        const text =
          node.data.responseText ??
          `${matchedRoot.buttonLabel}에 대해 구체적으로 어떤 내용이 필요하신가요?`;
        setLog([
          { id: nextId(), kind: 'bot-text', text },
          { id: nextId(), kind: 'bot-buttons', buttons: node.data.children },
        ]);
        return;
      }

      setLog([
        { id: nextId(), kind: 'bot-text', text: GREETING },
        { id: nextId(), kind: 'bot-buttons', buttons: res.data },
      ]);
    } catch (err) {
      setError(getApiErrorMessage(err, '상담 챗봇을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [initialCategory]);

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
      // 최상위 카테고리는 시드 데이터에 responseText가 없다(null) — 하위 옵션만 덩그러니 뜨지 않도록
      // 방금 누른 버튼 라벨을 바탕으로 안내 문구를 프론트에서 생성한다(백엔드 데이터 변경 없이).
      const text = res.data.responseText ?? `${button.buttonLabel}에 대해 구체적으로 어떤 내용이 필요하신가요?`;
      setLog((prev) => [
        ...prev,
        { id: nextId(), kind: 'bot-text' as const, text },
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
