// 점검(회차) 생성 폼 임시저장 — 파일(File 객체)은 직렬화 대상에서 제외하고 텍스트 입력만 보관한다.
// localStorage를 쓴다(#1703) — 원래는 탭을 닫으면 사라지는 sessionStorage였는데, 첨부 사진 초안은
// IndexedDB(inspectionCreateDraftFiles.ts)에 저장돼 브라우저 재시작 후에도 남아 있어 수명이 서로
// 어긋났다. 텍스트 초안만 먼저 사라지면 InspectionCreatePage의 복원 로직이 "텍스트 초안 없음"을
// "이전 세션의 고아 데이터"로 오인해 살아있던 사진 초안까지 지워버렸다(재시작 후 전부 소실).
// localStorage로 옮겨 두 저장소의 수명을 맞춘다. 다만 localStorage는 명시적으로 지우기 전까지
// 무기한 남으므로, 저장 시각(savedAt)을 함께 기록하고 아래 DRAFT_TTL_MS를 넘긴 오래된 초안은
// 복원하지 않고 폐기한다(무기한 방치 방지). 프라이빗 모드 등으로 접근이 막혀도 조용히 무시
// (임시저장 실패해도 폼 작성 자체엔 영향 없음).
import type { InspectionType } from '../types';

const INSPECTION_CREATE_DRAFT_KEY = 'hajacheckInspectionCreateDraft';

// TTL 7일 — 회사 스코프 정책상 점검은 보통 며칠~1주 내로 등록되므로, 그보다 오래 방치된 초안은
// 더 이상 유효하지 않은 것으로 본다(요구사항 명시값).
const DRAFT_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export interface InspectionCreateDraft {
  facilityId: string;
  inspectionDate: string;
  inspectionType: InspectionType;
  memo: string;
}

// localStorage에 실제로 저장되는 레코드 — 공개 InspectionCreateDraft에 savedAt(TTL 판정용)을 더한다.
interface StoredInspectionCreateDraft extends InspectionCreateDraft {
  savedAt: number;
}

export function saveInspectionCreateDraft(draft: InspectionCreateDraft): void {
  try {
    const stored: StoredInspectionCreateDraft = { ...draft, savedAt: Date.now() };
    localStorage.setItem(INSPECTION_CREATE_DRAFT_KEY, JSON.stringify(stored));
  } catch {
    // 저장 실패 무시
  }
}

export function loadInspectionCreateDraft(): InspectionCreateDraft | null {
  try {
    const raw = localStorage.getItem(INSPECTION_CREATE_DRAFT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredInspectionCreateDraft>;

    // savedAt이 없는 레코드는 TTL 도입(#1703) 이전에 저장된 옛 포맷이다 — 언제 저장됐는지 알 수
    // 없는 채로 무기한 유효하다고 취급하면 TTL을 도입한 의미가 없으므로, 만료된 것과 동일하게
    // 취급해 폐기한다(무한 유효로 두지 않음).
    const isExpired =
      typeof parsed.savedAt !== 'number' || Date.now() - parsed.savedAt > DRAFT_TTL_MS;
    if (isExpired) {
      clearInspectionCreateDraft();
      return null;
    }

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
    localStorage.removeItem(INSPECTION_CREATE_DRAFT_KEY);
  } catch {
    // 삭제 실패 무시
  }
}
