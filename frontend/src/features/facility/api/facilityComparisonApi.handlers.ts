import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockInspectionComparison } from '../mocks/facilityComparison.mock';
import type { InspectionComparisonResult } from '../types';

export const facilityComparisonHandlers = [
  http.get('/api/facilities/:id/compare', () => {
    const body: ApiResponse<InspectionComparisonResult> = {
      success: true,
      data: mockInspectionComparison,
    };
    return HttpResponse.json(body);
  }),
];