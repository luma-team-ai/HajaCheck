import { buildDefectImagePlaceholder } from '../utils/defectImagePlaceholder';
import type { InspectionComparisonResult } from '../types';

// dev-04-02(Figma "hajaCheck 회차 간 비교 - 강남 오피스타워 A동") 캡처 기준 예제 비교 데이터
export const mockInspectionComparison: InspectionComparisonResult = {
  facilityId: 1,
  facilityName: '강남 오피스타워 A동',
  beforeCycle: { cycle: 7, date: '2026-03-18' },
  afterCycle: { cycle: 8, date: '2026-06-21' },
  beforeImageUrl: buildDefectImagePlaceholder('7회차 (이전)'),
  afterImageUrl: buildDefectImagePlaceholder('8회차 (현재)'),
  kpis: [
    { key: 'newDefects', label: '신규 하자', value: 14, changeValue: 8 },
    { key: 'worsening', label: '진행성 (악화)', value: 2, changeValue: 2 },
    { key: 'resolved', label: '개선/조치 완료', value: 5, changeValue: -5 },
    { key: 'gradeEscalated', label: '등급 상승', value: 3, changeValue: 3 },
  ],
  crackTrend: [
    { cycleLabel: '5회차', avgWidthMm: 0.3 },
    { cycleLabel: '6회차', avgWidthMm: 0.45 },
    { cycleLabel: '7회차', avgWidthMm: 0.6 },
    { cycleLabel: '8회차', avgWidthMm: 0.8 },
  ],
  changes: [
    {
      id: 1,
      location: '외벽 A구간',
      defectType: '균열',
      gradeBefore: 'D',
      gradeAfter: 'E',
      changeType: 'worsened',
      note: '균열 폭 0.2mm 증가, 진행성',
    },
    {
      id: 2,
      location: '지하 1층',
      defectType: '누수',
      gradeBefore: null,
      gradeAfter: 'C',
      changeType: 'new',
      note: '우천 시 미세 누수 발견',
    },
    {
      id: 3,
      location: '로비 기둥',
      defectType: '백화',
      gradeBefore: 'B',
      gradeAfter: 'B',
      changeType: 'unchanged',
      note: '상태 변동 없음',
    },
    {
      id: 4,
      location: '외벽 B구간',
      defectType: '도장탈락',
      gradeBefore: 'C',
      gradeAfter: null,
      changeType: 'resolved',
      note: '재도장 작업 완료 (5월)',
    },
  ],
  availableCycles: [
    { cycle: 5, date: '2025-12-18' },
    { cycle: 6, date: '2026-01-20' },
    { cycle: 7, date: '2026-03-18' },
    { cycle: 8, date: '2026-06-21' },
  ],
};