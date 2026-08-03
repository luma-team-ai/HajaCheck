// @vitest-environment jsdom
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { RecommendationSection } from "./RecommendationSection";
import type { ReportContent } from "../../types";

afterEach(() => cleanup());

const createMockContent = (count: number): ReportContent => ({
  overview: { purpose: "", facility_summary: "", scope: "" },
  summary: { overall_opinion: "", total_count: count, count_by_grade: {}, key_findings: [] },
  detail: { items: [] },
  recommendation: {
    items: Array.from({ length: count }, (_, i) => ({
      target: `보수 대상 ${i + 1}`,
      method: `보수 방법 ${i + 1}`,
      priority: "상",
      legal_basis: "건축물관리법",
      legal_basis_verified: true,
    })),
    monitoring_points: [],
  },
});

describe("RecommendationSection", () => {
  it("보수보강 항목이 4개 이하일 때 1페이지로 전체 항목을 보여준다", () => {
    const content = createMockContent(3);
    render(<RecommendationSection content={content} onChange={() => {}} readOnly={false} />);

    expect(screen.getByText("보수 대상 1")).toBeTruthy();
    expect(screen.getByText("보수 대상 2")).toBeTruthy();
    expect(screen.getByText("보수 대상 3")).toBeTruthy();
    expect(screen.getByText("1")).toBeTruthy();
    expect(screen.getByText("/ 1")).toBeTruthy();
  });

  it("보수보강 항목이 5개일 때 4개 단위로 페이지네이션되고 다음 페이지로 이동할 수 있다", () => {
    const content = createMockContent(5);
    render(<RecommendationSection content={content} onChange={() => {}} readOnly={false} />);

    // 1페이지: 항목 1~4 노출, 5 비노출
    expect(screen.getByText("보수 대상 1")).toBeTruthy();
    expect(screen.getByText("보수 대상 4")).toBeTruthy();
    expect(screen.queryByText("보수 대상 5")).toBeNull();
    expect(screen.getByText("1")).toBeTruthy();
    expect(screen.getByText("/ 2")).toBeTruthy();

    // 다음 페이지 버튼 클릭
    const nextBtn = screen.getByRole("button", { name: "다음 페이지" });
    fireEvent.click(nextBtn);

    // 2페이지: 항목 5 노출, 1~4 비노출
    expect(screen.queryByText("보수 대상 1")).toBeNull();
    expect(screen.getByText("보수 대상 5")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("/ 2")).toBeTruthy();
  });
});
