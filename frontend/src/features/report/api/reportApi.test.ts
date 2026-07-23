// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { reportApi } from './reportApi';

describe('reportApi', () => {
  it('should have generateReportDraft method', () => {
    expect(reportApi.generateReportDraft).toBeDefined();
    expect(typeof reportApi.generateReportDraft).toBe('function');
  });

  it('should accept inspectionId parameter', () => {
    const generateReport = reportApi.generateReportDraft;
    expect(() => {
      generateReport(1);
    }).not.toThrow();
  });
});
