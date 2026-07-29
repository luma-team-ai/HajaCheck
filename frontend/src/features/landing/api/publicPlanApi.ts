import { api } from '../../../shared/api/axios';
import type { ApiResponse } from '../../../shared/api/types';

export interface PublicPlanApiItem {
  id: number;
  name: string; // 'FREE' | 'STANDARD' | 'ENTERPRISE'
  maxFacilities: number | null;
  maxMonthlyAnalyses: number | null;
  maxSeats: number | null;
  hasPdfWatermark: boolean;
  hasCounselorAccess: boolean;
  hasAiAddon: boolean;
  priceMonthly: number;
}

export interface PublicPlanCatalogResponse {
  plans: PublicPlanApiItem[];
}

export const publicPlanApi = {
  getPlans: (signal?: AbortSignal) =>
    api.get<ApiResponse<PublicPlanCatalogResponse>>('/plans', { signal }),
};
