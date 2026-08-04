import { useCallback, useEffect, useRef, useState } from 'react';
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

// #1506 — activeTicket.status 전이를 대화 로그 안에서 알리는 시스템 메시지. WAITING→IN_PROGRESS는
// "연결됨", (WAITING|IN_PROGRESS)→(RESOLVED|OFFLINE_LEFT)는 "종료됨"으로 구분해 렌더 위치를 다르게 쓴다
// (ChatBotPage에서 kind로 실시간 대화 시작 직전/메시지 목록 다음 위치를 나눔).
export type ChatSystemNotice = { id: string; kind: 'assigned' | 'ended'; text: string };

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
  const [systemNotices, setSystemNotices] = useState<ChatSystemNotice[]>([]);

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

  // #1506 — 소켓 연결(재연결 포함) 시점에 REST로 최신 티켓 상태를 백필한다. WS push(onAssigned/onEnded)를
  // 놓쳐도(연결 전에 상담원이 배정했거나 순간 끊김 등) "연결됨" 표시가 확정적으로 맞아진다.
  useEffect(() => {
    if (!connected || activeTicket === null) return;
    if (activeTicket.status === 'RESOLVED' || activeTicket.status === 'OFFLINE_LEFT') return;

    let cancelled = false;
    counselApi
      .getTicket(activeTicket.id)
      .then((res) => {
        if (!cancelled) setActiveTicket(res.data);
      })
      .catch(() => {
        // 백필 실패는 조용히 무시 — WS push가 정상 도착하면 어차피 최신화된다.
      });
    return () => {
      cancelled = true;
    };
    // activeTicket 전체가 아니라 id만 의존성으로 둔다 — 이 effect 자체가 setActiveTicket으로 그 객체를
    // 갱신하므로, 객체 전체를 넣으면 매 갱신마다 재요청이 도는 루프가 생긴다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected, activeTicket?.id]);

  // #1506 — activeTicket.status가 바뀌는 모든 경로(WS onAssigned/onEnded, 위 REST 백필)에 공통으로
  // 걸리도록 activeTicket?.status 하나만 watch한다. 최초 마운트(prevStatusRef.current===undefined)에는
  // 알림을 띄우지 않는다 — 이미 IN_PROGRESS/RESOLVED로 시작한 케이스까지 "지금 막 전이됐다"고 오인시키지
  // 않기 위함(이 훅은 챗봇 화면 진입 시 매번 새로 생성되므로 activeTicket은 항상 null에서 시작한다).
  const prevStatusRef = useRef<string | undefined>(undefined);
  useEffect(() => {
    const prevStatus = prevStatusRef.current;
    const nextStatus = activeTicket?.status;
    prevStatusRef.current = nextStatus;
    if (prevStatus === undefined || nextStatus === undefined || prevStatus === nextStatus) return;

    if (prevStatus === 'WAITING' && nextStatus === 'IN_PROGRESS') {
      setSystemNotices((prev) => [
        ...prev,
        { id: nextId(), kind: 'assigned', text: '상담원과 연결되었습니다.' },
      ]);
      return;
    }
    if (
      (prevStatus === 'WAITING' || prevStatus === 'IN_PROGRESS') &&
      (nextStatus === 'RESOLVED' || nextStatus === 'OFFLINE_LEFT')
    ) {
      setSystemNotices((prev) => [...prev, { id: nextId(), kind: 'ended', text: '상담이 종료되었습니다.' }]);
    }
  }, [activeTicket?.status]);

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
    systemNotices,
    socketConnected: connected,
    sendMessage,
    sendTyping,
    counselorTyping,
    endCounsel,
    ending,
    endError,
  };
}
