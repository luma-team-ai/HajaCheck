import type { SystemMonitoringResponse } from '../monitoring.types';

// 캡처 이미지(Figma node-id 1-404) 수치를 그대로 이식한 목데이터.
export const mockSystemMonitoring: SystemMonitoringResponse = {
  serverHealth: [
    { id: 'api-server', name: 'API 서버', status: 'HEALTHY', metric: '99.98%' },
    { id: 'ai-analysis-server', name: 'AI 분석 서버', status: 'HEALTHY' },
    { id: 'db', name: 'DB', status: 'HEALTHY' },
  ],
  jobQueue: {
    summary: { inProgress: 2, completed: 148, failed: 1 },
    jobs: [
      {
        id: 'J-8892',
        facilityName: '힐스테이트 광교 102동',
        imageCount: 42,
        status: 'IN_PROGRESS',
        durationLabel: '00:12',
        recordedAt: '2023-10-24 14:05:00',
      },
      {
        id: 'J-8891',
        facilityName: '래미안 블레스티지 지하주차장',
        imageCount: 128,
        status: 'IN_PROGRESS',
        durationLabel: '02:45',
        recordedAt: '2023-10-24 13:40:00',
      },
      {
        id: 'J-8890',
        facilityName: '아이파크 스위트 공용부',
        imageCount: 15,
        status: 'FAILED',
        durationLabel: '00:04',
        recordedAt: '2023-10-24 12:10:00',
      },
      {
        id: 'J-8889',
        facilityName: '자이 더 샵 외벽',
        imageCount: 56,
        status: 'WAITING',
        durationLabel: null,
        recordedAt: '2023-10-24 11:55:00',
      },
    ],
  },
  resourceUsage: {
    cpuUsagePercent: 42.5,
    memoryUsagePercent: 61.3,
    diskUsagePercent: 74.8,
  },
  errorLogs: [
    {
      id: 'log-1',
      timestamp: '2023-10-24 14:02:11',
      level: 'ERROR',
      service: 'worker-queue',
      message: 'Job J-8890 failed: Connection reset by peer during image download (retry 3/3)',
    },
    {
      id: 'log-2',
      timestamp: '2023-10-24 13:58:45',
      level: 'WARN',
      service: 'llm-api-gw',
      message: 'High latency detected (2.8s) for endpoint /v1/analyze. Throttling applied.',
    },
    {
      id: 'log-3',
      timestamp: '2023-10-24 11:20:05',
      level: 'ERROR',
      service: 'auth-service',
      message: 'Invalid token signature from IP 192.168.1.45 (user_id: null)',
    },
    {
      id: 'log-4',
      timestamp: '2023-10-24 09:15:22',
      level: 'WARN',
      service: 'db-pool',
      message: 'Connection pool reaching capacity (85/100 active connections)',
    },
    {
      id: 'log-5',
      timestamp: '2023-10-23 23:59:01',
      level: 'WARN',
      service: 'daily-cron',
      message: 'S3 backup taking longer than expected (> 5m). Still processing...',
    },
  ],
};
