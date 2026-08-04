// @vitest-environment jsdom
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReportContentEditor } from "./ReportContentEditor";
import { useAuthStore } from "../../auth/store/authStore";
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

  it("기본현황에서 공중이 이용하는 부위의 결함을 입력할 수 있다", () => {
    const handleChange = vi.fn();
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={handleChange}
        readOnly={false}
      />,
    );

    fireEvent.change(screen.getByLabelText("공중이 이용하는 부위의 결함"), {
      target: { value: "3층 보도 난간 파손" },
    });

    expect(handleChange).toHaveBeenCalledWith(
      expect.objectContaining({
        overview: expect.objectContaining({
          public_use_area_defect: "3층 보도 난간 파손",
        }),
      }),
    );
  });

  it("결과 요약에서 책임기술자명을 수동 수정할 수 있다", () => {
    const handleChange = vi.fn();
    render(
      <ReportContentEditor
        content={{
          ...mockContent,
          summary: { ...mockContent.summary, responsible_engineer_name: "김기준" },
        }}
        onChange={handleChange}
        readOnly={false}
      />,
    );

    fireEvent.change(screen.getByLabelText("책임기술자"), {
      target: { value: "박수정" },
    });

    expect(handleChange).toHaveBeenCalledWith(
      expect.objectContaining({
        summary: expect.objectContaining({ responsible_engineer_name: "박수정" }),
      }),
    );
  });

  // 법적 근거는 RAG로 생성되는 값이라 사용자가 직접 고치면 근거와 어긋날 수 있어 항상 읽기
  // 전용이다(readOnly prop과 무관 — content 편집 가능 여부와 별개로 이 필드만 고정).
  it("법적 근거는 readOnly=false인 편집 화면에서도 항상 읽기 전용이다", () => {
    render(
      <ReportContentEditor
        content={mockContent}
        onChange={() => {}}
        readOnly={false}
      />,
    );

    const textarea = screen.getByDisplayValue("건축물관리법 제10조") as HTMLTextAreaElement;
    expect(textarea.readOnly).toBe(true);
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

    screen.getAllByRole("img").forEach((img) => fireEvent.load(img));
    // "전체" 탭도 등급 필터와 동일하게 카드 자신의 하자 하나로만 스코프한다(#1499) —
    // 페이지당 카드 2장 × 카드별 박스 1개.
    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(2);

    for (const filterGrade of grades) {
      fireEvent.click(
        screen.getByRole("button", { name: `${filterGrade} (1)` }),
      );
      screen.getAllByRole("img").forEach((img) => fireEvent.load(img));

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

  // 회귀 테스트 — 같은 사진에 같은 등급 하자가 2건 이상 있으면(#1379), 등급 필터를 걸었을 때
  // 예전엔 그 등급의 모든 박스를 두 카드 모두에 똑같이 그려서 어느 카드가 어느 박스인지
  // 구분이 안 됐다. 이제 카드마다 자기 하자(id) 박스 하나만 그려야 한다.
  it("같은 사진에 같은 등급 하자가 여러 건이면 각 카드는 자신의 bbox 하나만 렌더링한다", () => {
    const sameGradeContent: ReportContent = {
      ...mockContent,
      summary: { ...mockContent.summary, total_count: 2, count_by_grade: { C: 2 } },
      detail: {
        items: [
          { defect_type: "박리", location: "하자 #03", severity_grade: "C", description: "", cause: "" },
          { defect_type: "박리", location: "하자 #04", severity_grade: "C", description: "", cause: "" },
        ],
      },
    };
    const defect3 = defect(3, "C");
    const defect4 = defect(4, "C");
    const sharedGroup: DefectPhotoGroup = {
      mediaId: 101,
      imageUrl: "/media/101/thumb",
      defects: [defect3, defect4],
    };

    const { container } = render(
      <ReportContentEditor
        content={sameGradeContent}
        onChange={() => {}}
        readOnly={false}
        defectPhotos={[
          { ...sharedGroup, highlightDefectId: defect3.id },
          { ...sharedGroup, highlightDefectId: defect4.id },
        ]}
      />,
    );

    screen.getAllByRole("img").forEach((img) => fireEvent.load(img));
    fireEvent.click(screen.getByRole("button", { name: "C (2)" }));

    // 카드 2개 × 박스 1개씩 = 총 2개(예전 버그면 카드마다 2개씩 그려 총 4개가 됨).
    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(2);
  });

  // 회귀 테스트 — "+ 서식 섹션 추가"로 제출문을 넣으면 매번 발신 업체명을 새로 타이핑해야 했다.
  // 로그인 세션의 companyName이 있으면 기본값으로 채워야 한다(#1379).
  it('제출문 섹션 추가 시 로그인 세션의 회사명을 기본값으로 채운다', () => {
    useAuthStore.getState().setUser({
      id: 1,
      email: 'a@b.com',
      name: '홍길동',
      role: 'USER',
      companyId: 1,
      profileImageUrl: null,
      createdAt: '2026-01-01T00:00:00Z',
      companyName: '테스트회사',
      status: 'ACTIVE',
    });

    const handleChange = vi.fn();
    render(<ReportContentEditor content={mockContent} onChange={handleChange} readOnly={false} />);

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '제출문' }));

    expect(handleChange).toHaveBeenCalledWith(
      expect.objectContaining({
        manualSections: [
          expect.objectContaining({
            type: 'submission',
            data: expect.objectContaining({ companyName: '테스트회사' }),
          }),
        ],
      }),
    );

    useAuthStore.getState().clearUser();
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

    expect(screen.getAllByText("결함 사진")).toHaveLength(1);
  });
});
