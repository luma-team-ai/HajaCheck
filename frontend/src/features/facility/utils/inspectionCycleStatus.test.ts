import { describe, expect, it } from 'vitest';
import { deriveInspectionCycleStatus } from './inspectionCycleStatus';

// 기준일 고정 — 2026-07-20 (경계값 계산의 재현성 확보)
const TODAY = new Date(2026, 6, 20);

describe('deriveInspectionCycleStatus', () => {
  it('다음점검일이 오늘보다 과거면 초과(overdue)로 분류하고 D+n을 표기한다', () => {
    const result = deriveInspectionCycleStatus('2026-07-17', TODAY);
    expect(result.kind).toBe('overdue');
    expect(result.label).toBe('D+3');
    expect(result.diffDays).toBe(-3);
  });

  it('7일 이내로 남으면 임박(upcoming)으로 분류하고 D-n을 표기한다', () => {
    const result = deriveInspectionCycleStatus('2026-07-27', TODAY);
    expect(result.kind).toBe('upcoming');
    expect(result.label).toBe('D-7');
  });

  it('8일 남으면 임박 경계를 벗어나 여유이내(grace)로 분류한다', () => {
    const result = deriveInspectionCycleStatus('2026-07-28', TODAY);
    expect(result.kind).toBe('grace');
    expect(result.label).toBe('D-8');
  });

  it('60일 이내면 여유이내(grace)로 분류한다', () => {
    const result = deriveInspectionCycleStatus('2026-09-18', TODAY);
    expect(result.kind).toBe('grace');
    expect(result.label).toBe('D-60');
  });

  it('61일 넘게 남으면 여유(safe)로 분류하고 라벨은 "여유"이다', () => {
    const result = deriveInspectionCycleStatus('2026-09-19', TODAY);
    expect(result.kind).toBe('safe');
    expect(result.label).toBe('여유');
  });

  it('다음점검일이 오늘이면 D-0(임박)이다', () => {
    const result = deriveInspectionCycleStatus('2026-07-20', TODAY);
    expect(result.kind).toBe('upcoming');
    expect(result.label).toBe('D-0');
  });

  it('다음점검일이 없으면 여유(safe)로 취급한다', () => {
    const result = deriveInspectionCycleStatus(null, TODAY);
    expect(result.kind).toBe('safe');
    expect(result.label).toBe('여유');
  });
});
