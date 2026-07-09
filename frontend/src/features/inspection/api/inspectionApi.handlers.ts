import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockInspectionResult } from '../mocks/inspectionResult.mock';
import type { InspectionResult } from '../types';

export const inspectionHandlers = [
  http.get('/api/inspections/:id/result', () => {
    const body: ApiResponse<InspectionResult> = { success: true, data: mockInspectionResult };
    return HttpResponse.json(body);
  }),
];
