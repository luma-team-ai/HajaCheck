import { describe, expect, it } from 'vitest';
import { formatElapsedTime } from './formatElapsedTime';

describe('formatElapsedTime', () => {
  const now = new Date('2026-07-21T12:00:00.000Z');

  it('1분 미만이면 "방금 전"을 반환한다', () => {
    expect(formatElapsedTime('2026-07-21T11:59:30.000Z', now)).toBe('방금 전');
  });

  it('1시간 미만이면 분 단위로 반환한다', () => {
    expect(formatElapsedTime('2026-07-21T11:48:00.000Z', now)).toBe('12분 전');
  });

  it('24시간 미만이면 시간 단위로 반환한다', () => {
    expect(formatElapsedTime('2026-07-21T09:00:00.000Z', now)).toBe('3시간 전');
  });

  it('24시간 이상이면 일 단위로 반환한다', () => {
    expect(formatElapsedTime('2026-07-19T12:00:00.000Z', now)).toBe('2일 전');
  });
});
