import { create } from 'zustand';

// 마지막으로 활성화된 점검(inspection) 회차의 id를 저장 — React_코드_컨벤션.md §4
// SideNavBar에서 /inspections/ai-analysis, /inspections/:id/viewer 등의 동적 링크를 생성할 때 사용
interface InspectionState {
  activeInspectionId: number | null;
  setActiveInspectionId: (id: number) => void;
  clearActiveInspectionId: () => void;
}

export const useInspectionStore = create<InspectionState>((set) => ({
  activeInspectionId: null,
  setActiveInspectionId: (id) => set({ activeInspectionId: id }),
  clearActiveInspectionId: () => set({ activeInspectionId: null }),
}));
