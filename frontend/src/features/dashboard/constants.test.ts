import { describe, it, expect } from 'vitest';
import { INSPECTION_NEW_PATH, inspectionReviewPath } from './constants';

// 스토리보드 DASH-01 action 이동 경로 회귀 방지
describe('대시보드 action 경로', () => {
  it('A1: 새 점검 시작은 점검 회차 생성(INSP-01) 경로로 이동한다', () => {
    expect(INSPECTION_NEW_PATH).toBe('/inspections/new');
  });

  it('A2: 검수하기는 해당 점검의 결과 뷰어(INSP-04) 경로로 이동한다', () => {
    expect(inspectionReviewPath(192)).toBe('/inspections/192/viewer');
  });
});
