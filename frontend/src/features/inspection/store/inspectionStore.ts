import { create } from 'zustand';

// 마지막으로 활성화된 점검(inspection) 회차의 id와 보고서(report) id를 저장 — React_코드_컨벤션.md §4
// SideNavBar에서 /inspections/ai-analysis, /inspections/:id/viewer, /reports/:id 등의 동적 링크를 생성할 때 사용
interface InspectionState {
  activeInspectionId: number | null;
  setActiveInspectionId: (id: number) => void;
  clearActiveInspectionId: () => void;
  activeReportId: number | null;
  setActiveReportId: (id: number) => void;
  clearActiveReportId: () => void;
}

export const useInspectionStore = create<InspectionState>((set) => ({
  activeInspectionId: null,
  setActiveInspectionId: (id) => set({ activeInspectionId: id }),
  clearActiveInspectionId: () => set({ activeInspectionId: null }),
  activeReportId: null,
  setActiveReportId: (id) => set({ activeReportId: id }),
  clearActiveReportId: () => set({ activeReportId: null }),
}));
