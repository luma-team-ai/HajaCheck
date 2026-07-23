import { api } from '../../../shared/api/axios';
import type { CompanyOption } from '../types';

// 사용자 등록 모달의 기업명 selectbox(#576 백엔드 구현 완료) — 심사 승인된 기업만 응답한다.
export const platformAdminCompanyApi = {
  getCompanies: () => api.get<CompanyOption[]>('/platform-admin/companies'),
};
