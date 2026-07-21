// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DefectFilterBar } from "./DefectFilterBar";

afterEach(cleanup);

describe("DefectFilterBar", () => {
  it("LLM이 분석한 값만 결과 칩으로 표시한다", () => {
    render(
      <DefectFilterBar
        filters={{
          type: "CRACK",
          grade: "D",
          status: "ACTION_PENDING",
          page: 0,
          size: 10,
        }}
        onChange={vi.fn()}
      />,
    );

    expect(
      screen.getByText("질문을 3개의 검색 조건으로 적용했어요"),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "유형: 균열 필터 제거" }),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "등급: D 이상 필터 제거" }),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "상태: 조치대기 필터 제거" }),
    ).not.toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("개별 필터 제거와 전체 초기화를 상위 상태로 전달한다", () => {
    const onChange = vi.fn();
    render(
      <DefectFilterBar
        filters={{ type: "CRACK", grade: "D", page: 2, size: 20 }}
        onChange={onChange}
      />,
    );

    fireEvent.click(
      screen.getByRole("button", { name: "유형: 균열 필터 제거" }),
    );
    expect(onChange).toHaveBeenNthCalledWith(1, {
      type: undefined,
      grade: "D",
      page: 0,
      size: 20,
    });

    fireEvent.click(screen.getByRole("button", { name: "필터 초기화" }));
    expect(onChange).toHaveBeenNthCalledWith(2, { page: 0, size: 20 });
  });
});
