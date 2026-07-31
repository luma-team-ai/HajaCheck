// @vitest-environment jsdom
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReportContentEditor } from "./ReportContentEditor";
import type { ReportContent } from "../types";
import type { Defect, DefectGrade } from "../../inspection/types";
import type { DefectPhotoGroup } from "./editor/DefectPhoto";

afterEach(() => {
  cleanup();
});

const mockContent: ReportContent = {
  overview: {
    purpose: "점검 목적",
    facility_summary: "시설물 개요",
    scope: "점검 범위",
  },
  summary: {
    overall_opinion: "종합 의견",
    total_count: 1,
    count_by_grade: { A: 1 },
    key_findings: ["발견 1"],
  },
  detail: {
    items: [
      {
        defect_type: "균열",
        location: "1층 외벽",
        severity_grade: "A",
        description: "외벽 마감 균열",
        cause: "건조 수축",
      },
    ],
  },
  recommendation: {
    items: [
      {
        target: "1층 외벽",
        method: "보수",
        priority: "상",
        legal_basis: "건축물관리법 제10조",
        legal_basis_verified: true,
      },
    ],
    monitoring_points: ["균열 진행 여부"],
  },
};

const BOX_SELECTOR = 'span[aria-hidden="true"].absolute';

function defect(id: number, grade: DefectGrade): Defect {
  return {
    id,
    type: "균열",
    grade,
    status: "DETECTED",
    confidence: 0.9,
    bbox: { x: id * 0.1, y: id * 0.1, width: 0.1, height: 0.1 },
    summary: "",
    mediaId: 101,
    imageUrl: "/media/101/thumb",
  } as Defect;
}

describe("ReportContentEditor", () => {
  it("renders recommendation items with (검증됨) label when legal_basis_verified is true", () => {
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={() => {}}
        readOnly={false}
      />,
    );
    expect(screen.getByText("법적 근거 (검증됨)")).not.toBeNull();
  });

  it("resets legal_basis_verified to false when legal_basis text is edited", () => {
    const handleChange = vi.fn();
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={handleChange}
        readOnly={false}
      />,
    );

    const textarea = screen.getByDisplayValue("건축물관리법 제10조");
    fireEvent.change(textarea, { target: { value: "건축물관리법 제12조" } });

    expect(handleChange).toHaveBeenCalledWith(
      expect.objectContaining({
        recommendation: expect.objectContaining({
          items: [
            expect.objectContaining({
              legal_basis: "건축물관리법 제12조",
              legal_basis_verified: false,
            }),
          ],
        }),
      }),
    );
  });

  it("진단 외관조사결과 기본사항에서는 설명과 원인 분석만 수정 가능하다", () => {
    const handleChange = vi.fn();
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={handleChange}
        readOnly={false}
      />,
    );

    const defectType = screen.getByLabelText(
      "하자 1 유형",
    ) as HTMLTextAreaElement;
    const location = screen.getByLabelText(
      "하자 1 위치",
    ) as HTMLTextAreaElement;
    expect(defectType.readOnly).toBe(true);
    expect(location.readOnly).toBe(true);
    expect(screen.queryByRole("textbox", { name: "하자 1 등급" })).toBeNull();
    expect(screen.getByText("A")).not.toBeNull();

    fireEvent.change(defectType, { target: { value: "박리박락" } });
    fireEvent.change(location, { target: { value: "2층 외벽" } });
    expect(handleChange).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("설명"), {
      target: { value: "수정된 설명" },
    });
    expect(handleChange).toHaveBeenCalledWith(
      expect.objectContaining({
        detail: expect.objectContaining({
          items: [expect.objectContaining({ description: "수정된 설명" })],
        }),
      }),
    );
  });

  it("보수ㆍ보강안의 보수 시급성과 대상은 읽기 전용이고 대상은 줄바꿈 입력으로 렌더링한다", () => {
    const handleChange = vi.fn();
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={handleChange}
        readOnly={false}
      />,
    );

    expect(
      screen.queryByRole("textbox", { name: "권고 1 보수 시급성" }),
    ).toBeNull();
    expect(screen.getByLabelText("권고 1 보수 시급성").textContent).toBe(
      "보수 시급성: 고",
    );

    const target = screen.getByLabelText("권고 1 대상") as HTMLTextAreaElement;
    expect(target.tagName).toBe("TEXTAREA");
    expect(target.readOnly).toBe(true);
    expect(target.className).toContain("leading-6");

    fireEvent.change(target, { target: { value: "수정된 대상" } });
    expect(handleChange).not.toHaveBeenCalled();
  });

  it("등급 필터 A~E를 적용하면 각 등급의 항목과 bbox만 렌더링한다", () => {
    const grades: DefectGrade[] = ["A", "B", "C", "D", "E"];
    const gradeContent: ReportContent = {
      ...mockContent,
      summary: {
        ...mockContent.summary,
        total_count: grades.length,
        count_by_grade: { A: 1, B: 1, C: 1, D: 1, E: 1 },
      },
      detail: {
        items: grades.map((grade) => ({
          defect_type: "균열",
          location: `${grade} 위치`,
          severity_grade: grade,
          description: "",
          cause: "",
        })),
      },
    };
    const defects = grades.map((grade, index) => defect(index + 1, grade));
    const sharedGroup: DefectPhotoGroup = {
      mediaId: 101,
      imageUrl: "/media/101/thumb",
      defects,
    };

    const { container } = render(
      <ReportContentEditor
        content={gradeContent}
        onChange={() => {}}
        readOnly={false}
        defectPhotos={defects.map((item) => ({
          ...sharedGroup,
          highlightDefectId: item.id,
        }))}
      />,
    );

    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(10);

    for (const filterGrade of grades) {
      fireEvent.click(
        screen.getByRole("button", { name: `${filterGrade} (1)` }),
      );

      for (const itemGrade of grades) {
        if (itemGrade === filterGrade) {
          expect(screen.getByDisplayValue(`${itemGrade} 위치`)).not.toBeNull();
        } else {
          expect(screen.queryByDisplayValue(`${itemGrade} 위치`)).toBeNull();
        }
      }
      expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(1);
    }
  });

  it("renders narrative fields as non-resizable editable text inputs until finalized", () => {
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={() => {}}
        readOnly={false}
      />,
    );

    const purposeTextarea = screen.getByLabelText(
      "점검 목적",
    ) as HTMLTextAreaElement;
    expect(purposeTextarea.readOnly).toBe(false);
    expect(purposeTextarea.className).toContain("resize-none");
    expect(purposeTextarea.className).not.toContain("resize-y");
  });

  it("서식 섹션 추가 메뉴는 버튼 아래가 아니라 위쪽으로 펼쳐 선택성을 확보한다", () => {
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={() => {}}
        readOnly={false}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "+ 서식 섹션 추가" }));

    const menuItem = screen.getByRole("button", { name: "안전성평가 결과" });
    const menu = menuItem.parentElement as HTMLElement;
    expect(menu.className).toContain("bottom-full");
    expect(menu.className).not.toContain("top-full");
  });

  it("사진 섹션 제목은 DnD 헤더에서만 한 번 렌더링한다", () => {
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={() => {}}
        readOnly={false}
      />,
    );

    expect(screen.getAllByText("부위별 사진")).toHaveLength(1);
  });
});
