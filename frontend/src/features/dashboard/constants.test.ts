import { describe, it, expect } from 'vitest';
import {
  AI_WEEKLY_BRIEFING_ANCHOR_ID,
  AI_WEEKLY_BRIEFING_PATH,
  INSPECTION_NEW_PATH,
  defectDetailPath,
} from './constants';

// 스토리보드 DASH-01 action 이동 경로 회귀 방지
describe('대시보드 action 경로', () => {
  it('A1: 새 점검 시작은 점검 회차 생성(INSP-01) 경로로 이동한다', () => {
    expect(INSPECTION_NEW_PATH).toBe('/inspections/create');
  });

  it('A2: 검수하기는 해당 하자의 상세(/defects/:id) 경로로 이동한다', () => {
    expect(defectDetailPath(192)).toBe('/defects/192');
  });

  // SideNavBar(shared, 미터치) href와 router.tsx의 라우트 등록이 여기 정의된 같은 값을 참조해야
  // #478 유형(라우트-메뉴 불일치)이 재발하지 않는다.
  it('#478: AI 주간 브리핑 경로/앵커 id는 SideNavBar href("/dashboard/ai-weekly-briefing")와 일치한다', () => {
    expect(AI_WEEKLY_BRIEFING_PATH).toBe('/dashboard/ai-weekly-briefing');
    expect(AI_WEEKLY_BRIEFING_ANCHOR_ID).toBe('ai-weekly-briefing-card');
  });
});
