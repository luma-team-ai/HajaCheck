import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';

export function useInspectionResult(inspectionId: number) {
  const isValidId = Number.isInteger(inspectionId) && inspectionId > 0;
  return useQuery({
    queryKey: ['inspection', inspectionId, 'result'],
    queryFn: () => inspectionApi.getResult(inspectionId).then((res) => res.data),
    enabled: isValidId,
  });
}
