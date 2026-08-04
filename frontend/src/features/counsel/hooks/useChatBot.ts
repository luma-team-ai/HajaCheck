import { useCallback, useEffect, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { SCENARIO_ACTION_OVERRIDES } from '../constants';
import { useCounselSocket } from './useCounselSocket';
import type {
  BotScenarioButtonResponse,
  BotScenarioNodeResponse,
  ChatMessageResponse,
  ChatMessageSender,
  CounselTicketSummaryResponse,
} from '../types';

let idSeq = 0;
function nextId() {
  idSeq += 1;
  return `bot-log-${Date.now()}-${idSeq}`;
}

export type ChatBotLogEntry =
  | { id: string; kind: 'bot-text'; text: string; actionRoute?: string; actionLabel?: string }
  | { id: string; kind: 'bot-buttons'; buttons: BotScenarioButtonResponse[] }
  | { id: string; kind: 'user-choice'; label: string }
  | { id: string; kind: 'ticket-created'; ticket: CounselTicketSummaryResponse };

// 노드 응답 문구를 만들 때 #1434 오버라이드(있으면 문구 교체 + 바로가기 버튼 정보)를 적용한다.
function resolveNodeText(node: BotScenarioNodeResponse, fallbackText: string) {
  const override = SCENARIO_ACTION_OVERRIDES[node.id];
  return {
    text: override?.responseText ?? node.responseText ?? fallbackText,
    actionRoute: override?.actionRoute,
    actionLabel: override?.actionLabel,
  };
}

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

  // 실시간 상담(#1000 후속) — 생성된 티켓을 상담원 연결/종료 이벤트로 계속 최신화해, 챗봇 화면
  // 이탈 없이(#1000 리포트: "왜 상담 이력으로 보내는지") 그 자리에서 대화를 이어간다.
  const [activeTicket, setActiveTicket] = useState<CounselTicketSummaryResponse | null>(null);
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [counselorTyping, setCounselorTyping] = useState(false);
  const [ending, setEnding] = useState(false);
  const [endError, setEndError] = useState<string | null>(null);

  const isSocketActive =
    activeTicket !== null && (activeTicket.status === 'WAITING' || activeTicket.status === 'IN_PROGRESS');
  const socketTicketId = isSocketActive ? activeTicket.id : null;

  const handleSocketMessage = useCallback((message: ChatMessageResponse) => {
    setMessages((prev) => (prev.some((m) => m.id === message.id) ? prev : [...prev, message]));
  }, []);
  const handleTicketUpdate = useCallback((ticket: CounselTicketSummaryResponse) => {
    setActiveTicket(ticket);
  }, []);
  const handleTyping = useCallback((sender: ChatMessageSender) => {
    if (sender !== 'COUNSELOR') return;
    setCounselorTyping(true);
  }, []);

  useEffect(() => {
    if (!counselorTyping) return;
    const timer = window.setTimeout(() => setCounselorTyping(false), 3000);
    return () => window.clearTimeout(timer);
  }, [counselorTyping]);

  const { connected, sendMessage, sendTyping } = useCounselSocket(socketTicketId, {
    onMessage: handleSocketMessage,
    onAssigned: handleTicketUpdate,
    onEnded: handleTicketUpdate,
    onTyping: handleTyping,
  });

  const endCounsel = useCallback(async () => {
    if (activeTicket === null) return;
    setEnding(true);
    setEndError(null);
    try {
      const res = await counselApi.resolve(activeTicket.id);
      setActiveTicket(res.data);
    } catch (err) {
      setEndError(getApiErrorMessage(err, '상담 종료에 실패했습니다.'));
    } finally {
      setEnding(false);
    }
  }, [activeTicket]);

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
        const { text, actionRoute, actionLabel } = resolveNodeText(
          node.data,
          `${matchedRoot.buttonLabel}에 대해 구체적으로 어떤 내용이 필요하신가요?`,
        );
        setLog([
          { id: nextId(), kind: 'bot-text', text, actionRoute, actionLabel },
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
        setActiveTicket(res.data);
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
      // #1434: 경로 안내가 있는 노드는 SCENARIO_ACTION_OVERRIDES로 문구 보정 + 바로가기 버튼 부착.
      const { text, actionRoute, actionLabel } = resolveNodeText(
        res.data,
        `${button.buttonLabel}에 대해 구체적으로 어떤 내용이 필요하신가요?`,
      );
      setLog((prev) => [
        ...prev,
        { id: nextId(), kind: 'bot-text' as const, text, actionRoute, actionLabel },
        { id: nextId(), kind: 'bot-buttons' as const, buttons: res.data.children },
      ]);
    } catch (err) {
      setError(getApiErrorMessage(err, '다음 안내를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  return {
    log,
    loading,
    error,
    connecting,
    selectButton,
    retry: loadRoots,
    activeTicket,
    messages,
    socketConnected: connected,
    sendMessage,
    sendTyping,
    counselorTyping,
    endCounsel,
    ending,
    endError,
  };
}
