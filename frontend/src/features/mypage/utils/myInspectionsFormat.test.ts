import { describe, expect, it } from 'vitest';
import {
  formatFileSize,
  formatInspectionDate,
  formatIssuedDate,
  formatReportTitle,
  formatRoundLabel,
} from './myInspectionsFormat';

describe('formatRoundLabel', () => {
  it('연도 뒤 2자리 + 회차 2자리(zero-pad)를 조립한다', () => {
    expect(formatRoundLabel('2024-03-15', 3)).toBe('24-03');
  });

  it('회차가 두 자리 이상이면 그대로 둔다', () => {
    expect(formatRoundLabel('2024-03-15', 12)).toBe('24-12');
  });

  it('datetime ISO 문자열도 앞 4자리에서 연도를 뽑는다', () => {
    expect(formatRoundLabel('2024-03-16T10:22:00', 3)).toBe('24-03');
  });
});

describe('formatInspectionDate', () => {
  it("ISO 'yyyy-MM-dd'를 '.'구분 표기로 바꾼다", () => {
    expect(formatInspectionDate('2024-03-15')).toBe('2024.03.15');
  });
});

describe('formatIssuedDate', () => {
  it('ISO datetime에서 날짜 부분만 잘라 표기한다', () => {
    expect(formatIssuedDate('2024-03-16T10:22:00')).toBe('2024.03.16');
  });
});

describe('formatFileSize', () => {
  it('bytes를 MB 문자열(소수 1자리)로 변환한다', () => {
    expect(formatFileSize(1258291)).toBe('1.2MB');
    expect(formatFileSize(838861)).toBe('0.8MB');
    expect(formatFileSize(2516583)).toBe('2.4MB');
  });

  it('null이면 null을 그대로 반환한다(호출부가 크기 표시를 감춤)', () => {
    expect(formatFileSize(null)).toBeNull();
  });

  it('0바이트도 정상 변환한다', () => {
    expect(formatFileSize(0)).toBe('0.0MB');
  });
});

describe('formatReportTitle', () => {
  it("'[{yy-RR}] {시설물명} 점검 보고서' 형태로 조립한다", () => {
    expect(formatReportTitle('강남 오피스타워 A동', '2024-03-16T10:22:00', 3)).toBe(
      '[24-03] 강남 오피스타워 A동 점검 보고서',
    );
  });
});
