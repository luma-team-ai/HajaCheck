import { describe, it, expect } from 'vitest';
import { formatElapsedTime } from './formatElapsedTime';

describe('formatElapsedTime', () => {
  const now = new Date('2026-07-13T12:00:00.000Z');

  it('1분 미만이면 방금 전을 반환한다', () => {
    const occurredAt = new Date('2026-07-13T11:59:30.000Z').toISOString();
    expect(formatElapsedTime(occurredAt, now)).toBe('방금 전');
  });

  it('1시간 미만이면 분 단위로 반환한다', () => {
    const occurredAt = new Date('2026-07-13T11:45:00.000Z').toISOString();
    expect(formatElapsedTime(occurredAt, now)).toBe('15분 전');
  });

  it('24시간 미만이면 시간 단위로 반환한다', () => {
    const occurredAt = new Date('2026-07-13T10:00:00.000Z').toISOString();
    expect(formatElapsedTime(occurredAt, now)).toBe('2시간 전');
  });

  it('시간 경계값(정확히 60분)은 시간 단위로 반환한다', () => {
    const occurredAt = new Date('2026-07-13T11:00:00.000Z').toISOString();
    expect(formatElapsedTime(occurredAt, now)).toBe('1시간 전');
  });

  it('24시간 이상이면 일 단위로 반환한다', () => {
    const occurredAt = new Date('2026-07-11T12:00:00.000Z').toISOString();
    expect(formatElapsedTime(occurredAt, now)).toBe('2일 전');
  });
});
