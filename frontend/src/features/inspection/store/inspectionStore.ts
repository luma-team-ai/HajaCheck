import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

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

// localStorage에 영속화(팀 리뷰 반영, 2026-07-28) — 예전엔 순수 메모리 스토어라 새로고침/탭 종료만
// 해도 activeInspectionId가 사라졌다. 그 값에 의존하는 사이드바 동적 링크(SideNavBar.tsx)가 전부
// "새 점검 생성" 화면으로 튕겨버리고, 그 회차로 돌아갈 다른 진입로도 없어 실질적으로 "돌아갈 길이
// 없는" 버그였다(팀원 리뷰 지적). localStorage라 새로고침으로는 더 이상 안 비워지므로, 공용 PC에서
// 다음 로그인 사용자에게 이전 사용자의 회차 id가 새지 않도록 명시적 로그아웃(useLogout.ts)뿐 아니라
// 401 강제 세션 만료(shared/api/axios.ts)와 로그인 성공 시점(useLogin.ts)에도 clear를 호출한다
// (PR머신 리뷰 P1, #1194).
export const useInspectionStore = create<InspectionState>()(
  persist(
    (set) => ({
      activeInspectionId: null,
      setActiveInspectionId: (id) => set({ activeInspectionId: id }),
      clearActiveInspectionId: () => set({ activeInspectionId: null }),
      activeReportId: null,
      setActiveReportId: (id) => set({ activeReportId: id }),
      clearActiveReportId: () => set({ activeReportId: null }),
    }),
    {
      name: 'hajacheck-inspection-store',
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
