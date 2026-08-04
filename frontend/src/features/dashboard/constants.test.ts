import { describe, it, expect } from 'vitest';
import {
  AI_WEEKLY_BRIEFING_ANCHOR_ID,
  AI_WEEKLY_BRIEFING_PATH,
  INSPECTION_NEW_PATH,
  inspectionDefectsPath,
} from './constants';

// 스토리보드 DASH-01 action 이동 경로 회귀 방지
describe('대시보드 action 경로', () => {
  it('A1: 새 점검 시작은 점검 회차 생성(INSP-01) 경로로 이동한다', () => {
    expect(INSPECTION_NEW_PATH).toBe('/inspections/create');
  });

  it('A2: 검수하기는 해당 점검의 하자 목록 경로로 이동한다', () => {
    expect(inspectionDefectsPath(192)).toBe('/inspections/192/defects');
  });

  // #1117 회귀 수정 — defectId를 함께 넘기면 모달이 자동으로 열리도록 쿼리파라미터로 딥링크한다.
  it('A2: defectId를 함께 전달하면 쿼리파라미터로 실어 하자 상세 모달을 딥링크한다', () => {
    expect(inspectionDefectsPath(192, 908)).toBe('/inspections/192/defects?defectId=908');
  });

  // router.tsx의 라우트 등록과 DashboardPage.tsx의 스크롤 판정이 여기 정의된 같은 값을 참조해야
  // #478 유형(라우트-앵커 불일치)이 재발하지 않는다.
  //
  // #1538: 이전 문구는 "SideNavBar href와 일치한다"였으나, 사이드바 메뉴 항목은 이제 존재하지 않는다
  // (서버 menus·SideNavBar 폴백 양쪽에서 제거 — #1522 결정). 사이드바를 근거로 삼던 단언이 링크 부재를
  // 잡지 못하고 오히려 "링크가 있다"는 오해를 남겼으므로, 실제로 이 값을 쓰는 두 곳으로 근거를 바꾼다.
  it('#478: AI 주간 브리핑 경로/앵커 id는 라우트·스크롤 판정이 공유하는 값으로 고정된다', () => {
    expect(AI_WEEKLY_BRIEFING_PATH).toBe('/dashboard/ai-weekly-briefing');
    expect(AI_WEEKLY_BRIEFING_ANCHOR_ID).toBe('ai-weekly-briefing-card');
  });
});
