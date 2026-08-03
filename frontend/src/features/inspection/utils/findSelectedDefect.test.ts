import { describe, expect, it } from 'vitest';
import type { Defect } from '../types';
import { findSelectedDefect } from './findSelectedDefect';

function makeDefect(overrides: Partial<Defect> & { id: number }): Defect {
  return {
    type: '균열',
    grade: 'C',
    status: 'DETECTED',
    confidence: 0.9,
    bbox: { x: 0.1, y: 0.1, width: 0.1, height: 0.1 },
    mediaId: 1,
    ...overrides,
  } as Defect;
}

describe('findSelectedDefect', () => {
  it('선택 id가 currentDefects에 있으면 그걸 반환한다', () => {
    const confirmed = makeDefect({ id: 1, status: 'CONFIRMED' });
    const pending = makeDefect({ id: 2, status: 'DETECTED' });
    const result = findSelectedDefect([confirmed, pending], [confirmed, pending], 1);
    expect(result?.id).toBe(1);
  });

  // 페이즈7 회귀 — 다음/이전 이미지 이동마다 selectedDefectId가 undefined로 리셋되는데,
  // 이때 배열 첫 항목이 아니라 아직 미확정인 하자를 우선 선택해야 한다.
  it('선택 id가 없으면 배열상 첫 항목이 아니라 미확정 하자를 우선 선택한다', () => {
    const confirmed = makeDefect({ id: 1, status: 'CONFIRMED' });
    const pending = makeDefect({ id: 2, status: 'DETECTED' });
    const result = findSelectedDefect([confirmed, pending], [confirmed, pending], undefined);
    expect(result?.id).toBe(2);
  });

  it('전부 확정됐으면 미확정 후보가 없으니 첫 항목으로 폴백한다', () => {
    const confirmed1 = makeDefect({ id: 1, status: 'CONFIRMED' });
    const confirmed2 = makeDefect({ id: 2, status: 'RESOLVED' });
    const result = findSelectedDefect(
      [confirmed1, confirmed2],
      [confirmed1, confirmed2],
      undefined,
    );
    expect(result?.id).toBe(1);
  });

  it('선택 id가 삭제 등으로 더 이상 없으면 미확정 우선 폴백으로 넘어간다', () => {
    const confirmed = makeDefect({ id: 1, status: 'CONFIRMED' });
    const pending = makeDefect({ id: 2, status: 'DETECTED' });
    const result = findSelectedDefect([confirmed, pending], [confirmed, pending], 999);
    expect(result?.id).toBe(2);
  });

  it('mediaId=null인 수동 추가 하자는 allDefects에서 우선 찾는다', () => {
    const manual = makeDefect({ id: 7, mediaId: null });
    const result = findSelectedDefect([manual], [], 7);
    expect(result?.id).toBe(7);
  });
});
