// 점검(회차) 생성 폼 임시저장 — 파일(File 객체)은 직렬화 대상에서 제외하고 텍스트 입력만 보관한다.
// 탭을 닫으면 사라지는 sessionStorage를 쓴다(companySignupSession.ts와 동일 패턴) — 프라이빗 모드
// 등으로 접근이 막혀도 조용히 무시(임시저장 실패해도 폼 작성 자체엔 영향 없음).
import type { InspectionType } from '../types';

const INSPECTION_CREATE_DRAFT_KEY = 'hajacheckInspectionCreateDraft';

export interface InspectionCreateDraft {
  facilityId: string;
  inspectionDate: string;
  inspectionType: InspectionType;
  memo: string;
}

export function saveInspectionCreateDraft(draft: InspectionCreateDraft): void {
  try {
    sessionStorage.setItem(INSPECTION_CREATE_DRAFT_KEY, JSON.stringify(draft));
  } catch {
    // 저장 실패 무시
  }
}

export function loadInspectionCreateDraft(): InspectionCreateDraft | null {
  try {
    const raw = sessionStorage.getItem(INSPECTION_CREATE_DRAFT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<InspectionCreateDraft>;
    return {
      facilityId: parsed.facilityId ?? '',
      inspectionDate: parsed.inspectionDate ?? '',
      inspectionType: parsed.inspectionType ?? 'REGULAR',
      memo: parsed.memo ?? '',
    };
  } catch {
    return null;
  }
}

export function clearInspectionCreateDraft(): void {
  try {
    sessionStorage.removeItem(INSPECTION_CREATE_DRAFT_KEY);
  } catch {
    // 삭제 실패 무시
  }
}
