import type { InspectionHistoryRow, MyInspectionsSummary, MyReportCard } from '../types';

// 마이페이지 — 내 점검 이력 / 보고서 (HAJA-366, #668) — BE API 전무(grep 0건), Figma 시안 수치를
// 그대로 예제 데이터로 이식한다. mypage.mock.ts(플랜/좌석)과 도메인이 달라 별도 파일로 분리.

export const mockMyInspectionsSummary: MyInspectionsSummary = {
  participatedCount: 18,
  reviewConfirmedCount: 12,
  issuedReportCount: 7,
  inProgressCount: 2,
};

// 목록은 8건만 담되, totalElements는 Figma 시안의 "1-8 / 18" 페이지네이션 표기에 맞춰 18로 둔다
// (handoff 지시 — 총 18건 느낌을 재현하는 표시 목적. 실 서버 페이징 연동은 후속 BE 몫).
export const mockMyInspectionRows: InspectionHistoryRow[] = [
  {
    id: 1,
    facilityName: '강남 오피스타워 A동',
    round: '24-03',
    inspectedAt: '2024.03.15',
    role: 'INSPECTOR',
    defectCount: 24,
    status: 'REVIEW_DONE',
  },
  {
    id: 2,
    facilityName: '성수동 지식산업센터 1차',
    round: '24-01',
    inspectedAt: '2024.03.12',
    role: 'OWNER',
    defectCount: 15,
    status: 'REVIEW_PENDING',
  },
  {
    id: 3,
    facilityName: '분당 테크노밸리 C동',
    round: '23-04',
    inspectedAt: '2024.02.28',
    role: 'INSPECTOR',
    defectCount: 8,
    status: 'ANALYZING',
  },
  {
    id: 4,
    facilityName: '여의도 스카이라인 타워',
    round: '24-02',
    inspectedAt: '2024.02.20',
    role: 'INSPECTOR',
    defectCount: 42,
    status: 'REVIEW_DONE',
  },
  {
    id: 5,
    facilityName: '판교 테크노센터 3관',
    round: '24-01',
    inspectedAt: '2024.02.15',
    role: 'OWNER',
    defectCount: 12,
    status: 'REVIEW_DONE',
  },
  {
    id: 6,
    facilityName: '광화문 비즈니스 스퀘어',
    round: '23-12',
    inspectedAt: '2024.02.01',
    role: 'INSPECTOR',
    defectCount: 19,
    status: 'REVIEW_DONE',
  },
  {
    id: 7,
    facilityName: '가산 디지털엠파이어 2단지',
    round: '24-02',
    inspectedAt: '2024.01.20',
    role: 'INSPECTOR',
    defectCount: 5,
    status: 'REVIEW_PENDING',
  },
  {
    id: 8,
    facilityName: '인천 송도 글로벌 캠퍼스',
    round: '24-01',
    inspectedAt: '2024.01.10',
    role: 'INSPECTOR',
    defectCount: 31,
    status: 'REVIEW_DONE',
  },
];

export const MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS = 18;

export const mockMyReports: MyReportCard[] = [
  {
    id: 1,
    title: '[24-03] 강남 오피스타워 A동 정밀점검 보고서',
    issuedAt: '2024.03.16',
    fileSizeLabel: '1.2MB',
    gradeDots: ['RED', 'ORANGE', 'GREEN'],
  },
  {
    id: 2,
    title: '[24-01] 성수동 지식산업센터 정기 보고서',
    issuedAt: '2024.03.12',
    fileSizeLabel: '0.8MB',
    gradeDots: ['ORANGE', 'GREEN'],
  },
  {
    id: 3,
    title: '[24-02] 여의도 스카이라인 타워 종합 분석서',
    issuedAt: '2024.02.21',
    fileSizeLabel: '2.4MB',
    gradeDots: ['RED', 'RED'],
  },
];
