import { describe, expect, it } from 'vitest';
import { extractFacilityCategory } from './facilityCategory';

describe('extractFacilityCategory', () => {
  it('복합 문자열("건물-정기-4개월")에서 접두어 "건물"을 반환한다', () => {
    expect(extractFacilityCategory('건물-정기-4개월')).toBe('건물');
  });

  it('다른 복합 문자열("교량-정밀-12개월")에서 접두어 "교량"을 반환한다', () => {
    expect(extractFacilityCategory('교량-정밀-12개월')).toBe('교량');
  });

  it('단일 값("건물")은 그대로 반환한다', () => {
    expect(extractFacilityCategory('건물')).toBe('건물');
  });

  it('단일 값("기타")은 그대로 반환한다', () => {
    expect(extractFacilityCategory('기타')).toBe('기타');
  });

  it('빈 문자열은 빈 문자열을 반환한다', () => {
    expect(extractFacilityCategory('')).toBe('');
  });

  it('null은 빈 문자열을 반환한다', () => {
    expect(extractFacilityCategory(null)).toBe('');
  });

  it('undefined는 빈 문자열을 반환한다', () => {
    expect(extractFacilityCategory(undefined)).toBe('');
  });
});
