import { api } from '../../../shared/api/axios';
import type { InspectionResult } from '../types';

export const inspectionApi = {
  getResult: (inspectionId: number) =>
    api.get<InspectionResult>(`/inspections/${inspectionId}/result`),
};
