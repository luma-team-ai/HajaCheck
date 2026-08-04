import type { InspectionHistoryRow, MyInspectionsSummary, MyReportCard } from '../types';

// 마이페이지 — 내 점검 이력 / 보고서 (HAJA-366/#668, BE 연동 #844/HAJA-442) — MSW 핸들러
// (api/mypageApi.handlers.ts) 전용 예제 데이터. 실 서버 응답 계약(원시값: roundNo/inspectionDate/
// issuedAt/fileSizeBytes)과 동일한 shape을 유지한다 — 표시 포맷 조립은 utils/myInspectionsFormat.ts.
// 프로덕션 경로(훅)에서는 더 이상 참조하지 않는다(mock 폴백 제거, #844).

export const mockMyInspectionsSummary: MyInspectionsSummary = {
  participatedCount: 18,
  reviewConfirmedCount: 12,
  issuedReportCount: 7,
  inProgressCount: 2,
};

// 목록은 8건만 담되, totalElements는 Figma 시안의 "1-8 / 18" 페이지네이션 표기에 맞춰 18로 둔다.
export const mockMyInspectionRows: InspectionHistoryRow[] = [
  {
    id: 1,
    facilityName: '강남 오피스타워 A동',
    roundNo: 3,
    inspectionDate: '2024-03-15',
    role: 'INSPECTOR',
    defectCount: 24,
    status: 'REVIEW_DONE',
  },
  {
    id: 2,
    facilityName: '성수동 지식산업센터 1차',
    roundNo: 1,
    inspectionDate: '2024-03-12',
    role: 'OWNER',
    defectCount: 15,
    status: 'REVIEW_PENDING',
  },
  {
    id: 3,
    facilityName: '분당 테크노밸리 C동',
    roundNo: 4,
    inspectionDate: '2024-02-28',
    role: 'INSPECTOR',
    defectCount: 8,
    status: 'ANALYZING',
  },
  {
    id: 4,
    facilityName: '여의도 스카이라인 타워',
    roundNo: 2,
    inspectionDate: '2024-02-20',
    role: 'INSPECTOR',
    defectCount: 42,
    status: 'REVIEW_DONE',
  },
  {
    id: 5,
    facilityName: '판교 테크노센터 3관',
    roundNo: 1,
    inspectionDate: '2024-02-15',
    role: 'OWNER',
    defectCount: 12,
    status: 'REVIEW_DONE',
  },
  {
    id: 6,
    facilityName: '광화문 비즈니스 스퀘어',
    roundNo: 12,
    inspectionDate: '2024-02-01',
    role: 'INSPECTOR',
    defectCount: 19,
    status: 'REVIEW_DONE',
  },
  {
    id: 7,
    facilityName: '가산 디지털엠파이어 2단지',
    roundNo: 2,
    inspectionDate: '2024-01-20',
    role: 'INSPECTOR',
    defectCount: 5,
    status: 'REVIEW_PENDING',
  },
  {
    id: 8,
    facilityName: '인천 송도 글로벌 캠퍼스',
    roundNo: 1,
    inspectionDate: '2024-01-10',
    role: 'INSPECTOR',
    defectCount: 31,
    status: 'REVIEW_DONE',
  },
];

export const MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS = 18;

export const mockMyReports: MyReportCard[] = [
  {
    id: 1,
    inspectionId: 1,
    facilityName: '강남 오피스타워 A동',
    roundNo: 3,
    issuedAt: '2024-03-16T10:22:00',
    fileSizeBytes: 1258291,
    gradeDots: ['RED', 'ORANGE', 'GREEN'],
    pdfUrl: '/api/reports/1/pdf/mock-1.pdf',
  },
  {
    id: 2,
    inspectionId: 2,
    facilityName: '성수동 지식산업센터 1차',
    roundNo: 1,
    issuedAt: '2024-03-12T09:00:00',
    fileSizeBytes: 838861,
    gradeDots: ['ORANGE', 'GREEN'],
    pdfUrl: '/api/reports/2/pdf/mock-2.pdf',
  },
  {
    id: 3,
    inspectionId: 4,
    facilityName: '여의도 스카이라인 타워',
    roundNo: 2,
    issuedAt: '2024-02-21T14:30:00',
    fileSizeBytes: 2516583,
    gradeDots: ['RED', 'GREEN'],
    pdfUrl: null,
  },
];
