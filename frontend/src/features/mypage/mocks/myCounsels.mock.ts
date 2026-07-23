import type { MyCounselRow } from '../types';

// 마이페이지 — 내 상담 내역 (HAJA-371, #678) — 상담(counsel) BE API 전무(controller/service/repo가
// .gitkeep 빈 스켈레톤, 엔티티만 존재) — Figma 시안 4행을 그대로 예제 데이터로 이식한다.
// myInspections.mock.ts와 도메인이 달라 별도 파일로 분리(동일 컨벤션).

// 목록은 4건만 담되, totalElements는 handoff 지시(총 18건 표기)에 맞춰 18로 둔다 — 실 서버
// 페이징 연동은 후속 BE 몫(BE API 전무).
export const mockMyCounselRows: MyCounselRow[] = [
  {
    id: 1,
    type: 'SCENARIO_BOT',
    topic: '간단한 이용 안내 문의',
    assignee: null,
    status: 'CLOSED',
    waitingNumber: null,
    startedAt: '2026-07-10 14:20',
    lastMessage: '도움이 되셨나요? 언제든 다시 질문...',
    canView: false,
  },
  {
    id: 2,
    type: 'AGENT_CONNECT',
    topic: '분석 결과 오류 확인 요청',
    assignee: { name: '이점검' },
    status: 'CLOSED',
    waitingNumber: null,
    startedAt: '2026-07-10 13:05',
    lastMessage: '요청하신 데이터 수정 완료되었습니다.',
    canView: true,
  },
  {
    id: 3,
    type: 'AGENT_CONNECT',
    topic: 'C등급 조치 관련 긴급 문의',
    assignee: { name: '배정 대기중', textOnly: true },
    status: 'WAITING',
    waitingNumber: 2,
    startedAt: '2026-07-11 09:12',
    lastMessage: '상담원 연결을 기다리고 있습니다...',
    canView: false,
  },
  {
    id: 4,
    type: 'INQUIRY',
    topic: '계정 권한 추가 요청',
    assignee: { name: '관리자 그룹', textOnly: true },
    status: 'ANSWERED',
    waitingNumber: null,
    startedAt: '2026-07-08 16:45',
    lastMessage: '권한 부여가 완료되었습니다. 확인 부...',
    canView: false,
  },
];

export const MOCK_MY_COUNSELS_TOTAL_ELEMENTS = 18;
