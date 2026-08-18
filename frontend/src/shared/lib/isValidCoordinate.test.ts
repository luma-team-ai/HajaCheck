import { describe, expect, it } from 'vitest';
import { hasValidCoordinate, isValidCoordinate } from './isValidCoordinate';

describe('isValidCoordinate', () => {
  it('유효 범위 내 좌표는 true를 반환한다', () => {
    expect(isValidCoordinate(37.5, 127.0)).toBe(true);
    expect(isValidCoordinate(-90, -180)).toBe(true);
    expect(isValidCoordinate(90, 180)).toBe(true);
  });

  it('(0,0)은 EXIF GPS 결측 센티널로 간주해 false를 반환한다', () => {
    expect(isValidCoordinate(0, 0)).toBe(false);
  });

  it('null/undefined 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(null, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, null)).toBe(false);
    expect(isValidCoordinate(undefined, undefined)).toBe(false);
  });

  it('NaN 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(NaN, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, NaN)).toBe(false);
  });

  it('Infinity 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(Infinity, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, -Infinity)).toBe(false);
  });

  it('범위를 벗어난 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(91, 127.0)).toBe(false);
    expect(isValidCoordinate(-91, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, 181)).toBe(false);
    expect(isValidCoordinate(37.5, -181)).toBe(false);
  });
});

describe('hasValidCoordinate', () => {
  it('유효한 좌표를 가진 객체는 true를 반환하고 타입을 좁힌다', () => {
    const facility: { latitude: number | null; longitude: number | null } = {
      latitude: 37.5,
      longitude: 127.0,
    };
    expect(hasValidCoordinate(facility)).toBe(true);
  });

  it('좌표가 null인 객체는 false를 반환한다', () => {
    expect(hasValidCoordinate({ latitude: null, longitude: null })).toBe(false);
    expect(hasValidCoordinate({ latitude: 37.5, longitude: null })).toBe(false);
  });
});
