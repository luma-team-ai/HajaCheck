import { api } from '../../../shared/api/axios';
import type { MyPlan, SeatsInfo, UpgradeInquiryResult } from '../types';

export const mypageApi = {
  getPlan: () => api.get<MyPlan>('/me/plan'),
  getSeats: () => api.get<SeatsInfo>('/me/seats'),
  requestUpgrade: () => api.post<UpgradeInquiryResult>('/me/plan/upgrade-inquiry'),
};
