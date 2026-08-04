import { describe, expect, it } from "vitest";
import { buildReportPdfFileName } from "./reportPdf";

describe("reportPdf", () => {
  it("buildReportPdfFileName은 inspectionId와 오늘 날짜로 파일명을 만든다", () => {
    expect(buildReportPdfFileName(42)).toMatch(/^점검보고서_42_\d{8}\.pdf$/);
  });
});
