import { describe, expect, it } from 'vitest';
import { resolveDefectGroupSummary } from './defectGroupSummary';
import type { InspectionDefect } from '../types';

// mockInspectionDefects/defect.mock.ts를 재사용하지 않고 최소 필드만 채운 로컬 fixture를 쓴다 —
// 그룹 판정에 필요한 필드(status, mediaId)만 다양화해 순수 함수를 독립적으로 검증한다.
function makeDefect(overrides: Partial<InspectionDefect> & Pick<InspectionDefect, 'id'>): InspectionDefect {
  return {
    inspectionId: 101,
    type: 'CRACK',
    typeLabel: '균열',
    grade: 'B',
    status: 'CONFIRMED',
    confidence: 0.9,
    reviewed: true,
    bboxX: null,
    bboxY: null,
    bboxW: null,
    bboxH: null,
    crackWidthMm: null,
    crackLengthMm: null,
    areaRatio: null,
    areaMm2: null,
    mediaId: 901,
    imageUrl: null,
    detailUrl: null,
    createdAt: '2026-07-01T09:00:00.000Z',
    ...overrides,
  };
}

describe('resolveDefectGroupSummary', () => {
  it('mediaId가 없으면(수동 추가 하자) 그룹 크기 1, 자기 상태 그대로 반환한다', () => {
    const selected = makeDefect({ id: 1, mediaId: null, status: 'IN_PROGRESS' });
    const result = resolveDefectGroupSummary([selected], selected);
    expect(result).toEqual({ size: 1, status: 'IN_PROGRESS' });
  });

  it('같은 mediaId를 가진 하자가 자신뿐이면 그룹 크기 1, 자기 상태 그대로 반환한다', () => {
    const selected = makeDefect({ id: 1, status: 'CONFIRMED' });
    const result = resolveDefectGroupSummary([selected], selected);
    expect(result).toEqual({ size: 1, status: 'CONFIRMED' });
  });

  it('전부 CONFIRMED면 그룹 상태는 CONFIRMED다', () => {
    const a = makeDefect({ id: 1, status: 'CONFIRMED' });
    const b = makeDefect({ id: 2, status: 'CONFIRMED' });
    const result = resolveDefectGroupSummary([a, b], a);
    expect(result).toEqual({ size: 2, status: 'CONFIRMED' });
  });

  it('하나라도 IN_PROGRESS 이상이면 그룹 상태는 IN_PROGRESS다(백엔드 aggregateGroupStatus와 동일 규칙)', () => {
    const a = makeDefect({ id: 1, status: 'CONFIRMED' });
    const b = makeDefect({ id: 2, status: 'IN_PROGRESS' });
    const c = makeDefect({ id: 3, status: 'CONFIRMED' });
    const result = resolveDefectGroupSummary([a, b, c], a);
    expect(result).toEqual({ size: 3, status: 'IN_PROGRESS' });
  });

  it('전체가 RESOLVED일 때만 그룹 상태가 RESOLVED다', () => {
    const a = makeDefect({ id: 1, status: 'RESOLVED' });
    const b = makeDefect({ id: 2, status: 'RESOLVED' });
    const result = resolveDefectGroupSummary([a, b], a);
    expect(result).toEqual({ size: 2, status: 'RESOLVED' });
  });

  it('RESOLVED가 섞여 있어도 전부는 아니면 IN_PROGRESS로 집계한다', () => {
    const a = makeDefect({ id: 1, status: 'RESOLVED' });
    const b = makeDefect({ id: 2, status: 'CONFIRMED' });
    const result = resolveDefectGroupSummary([a, b], a);
    expect(result).toEqual({ size: 2, status: 'IN_PROGRESS' });
  });

  it('DETECTED(검수 전) 하자는 그룹 대상에서 제외한다(백엔드 GROUP_ELIGIBLE_STATUSES와 동일)', () => {
    const a = makeDefect({ id: 1, status: 'CONFIRMED' });
    const detected = makeDefect({ id: 2, status: 'DETECTED' });
    const result = resolveDefectGroupSummary([a, detected], a);
    expect(result).toEqual({ size: 1, status: 'CONFIRMED' });
  });

  it('다른 mediaId의 하자는 그룹에 포함하지 않는다', () => {
    const a = makeDefect({ id: 1, mediaId: 901, status: 'CONFIRMED' });
    const other = makeDefect({ id: 2, mediaId: 902, status: 'IN_PROGRESS' });
    const result = resolveDefectGroupSummary([a, other], a);
    expect(result).toEqual({ size: 1, status: 'CONFIRMED' });
  });

  it('선택된 하자가 defects 배열에 없어도(예: 방금 CONFIRMED로 전이된 직후) 그룹에 포함해 계산한다', () => {
    const b = makeDefect({ id: 2, status: 'IN_PROGRESS' });
    const selectedNotInList = makeDefect({ id: 1, status: 'CONFIRMED' });
    const result = resolveDefectGroupSummary([b], selectedNotInList);
    expect(result).toEqual({ size: 2, status: 'IN_PROGRESS' });
  });
});
