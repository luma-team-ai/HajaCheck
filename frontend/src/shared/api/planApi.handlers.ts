import { http, HttpResponse } from 'msw';
import type { ApiResponse } from './types';
import type { PlanCatalogResponse } from './planApi';

export const planCatalogHandlers = [
  http.get('/api/plans', () => {
    const body: ApiResponse<PlanCatalogResponse> = {
      success: true,
      data: {
        plans: [
          {
            id: 1,
            name: 'FREE',
            maxFacilities: 1,
            maxMonthlyAnalyses: 50,
            maxSeats: 1,
            hasPdfWatermark: true,
            hasCounselorAccess: false,
            hasAiAddon: false,
            priceMonthly: 0,
          },
          {
            id: 2,
            name: 'STANDARD',
            maxFacilities: 10,
            maxMonthlyAnalyses: 1000,
            maxSeats: 3,
            hasPdfWatermark: false,
            hasCounselorAccess: true,
            hasAiAddon: true,
            priceMonthly: 29000,
          },
          {
            id: 3,
            name: 'ENTERPRISE',
            maxFacilities: null,
            maxMonthlyAnalyses: null,
            maxSeats: null,
            hasPdfWatermark: false,
            hasCounselorAccess: true,
            hasAiAddon: true,
            priceMonthly: 59000,
          },
        ],
      },
    };
    return HttpResponse.json(body);
  }),
];
