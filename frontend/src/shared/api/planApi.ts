import { api } from './axios';

export interface PlanCatalogItem {
  id: number;
  name: string;
  maxFacilities: number | null;
  maxMonthlyAnalyses: number | null;
  maxSeats: number | null;
  hasPdfWatermark: boolean;
  hasCounselorAccess: boolean;
  hasAiAddon: boolean;
  priceMonthly: number;
}

export interface PlanCatalogResponse {
  plans: PlanCatalogItem[];
}

export interface CurrentPlanResponse {
  plan: {
    name: string;
  };
}

export const planQueryKeys = {
  catalog: ['plans', 'catalog'] as const,
  current: ['plans', 'current'] as const,
};

export const planApi = {
  getPlans: (signal?: AbortSignal) =>
    api.get<PlanCatalogResponse>('/plans', { signal }),
  getCurrentPlan: <T extends CurrentPlanResponse = CurrentPlanResponse>(signal?: AbortSignal) =>
    api.get<T>('/me/plan', { signal }),
};
