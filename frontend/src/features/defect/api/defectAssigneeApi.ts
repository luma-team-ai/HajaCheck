import { api } from '../../../shared/api/axios';
import type { DefectAssignee } from '../types';

// 하자 상세 모달 "담당자" select 옵션 — facility feature의 facilityAssigneeApi.ts와 동일 패턴을
// defect feature 안에 자체 복제한다(feature 간 직접 import 금지, React_코드_컨벤션.md §1).
// 실 API 계약 없음(2026-07-23 조사, #629) — #690 "배정 가능한 담당자(회사 소속 사용자) 목록 조회
// API"를 재사용 대상으로 추정(contract.md §엔드포인트 매핑 ③).
export const defectAssigneeApi = {
  listAssignableUsers: () => api.get<DefectAssignee[]>('/facilities/assignable-users'),
};
