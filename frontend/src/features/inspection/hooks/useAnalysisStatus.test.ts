import { describe, expect, it } from 'vitest';
import { isAnalysisPollingTerminal } from './useAnalysisStatus';

// 코드 리뷰 P2 — 워커가 이미지 전체 실패로 롤백하면 stage가 'failed'로 저장되는데(백엔드
// InspectionAnalysisWorker), 예전엔 useAnalysisStatus가 'done'만 종료로 봐서 실패 잡을 2초마다
// 영원히 폴링하며 "AI 탐지 진행 중 0%"로 계속 보여줬다. 폴링 종료 판단(isAnalysisPollingTerminal)을
// 직접 테스트해 'done'이 아니어도 'failed'면 멈춘다는 걸 고정한다.
describe('isAnalysisPollingTerminal', () => {
  it('stage가 done이면 폴링을 멈춘다', () => {
    expect(isAnalysisPollingTerminal('done')).toBe(true);
  });

  it('stage가 failed여도 폴링을 멈춘다(done이 아니어도 종료 상태)', () => {
    expect(isAnalysisPollingTerminal('failed')).toBe(true);
  });

  it('stage가 진행 중(aiDetection 등)이면 폴링을 계속한다', () => {
    expect(isAnalysisPollingTerminal('aiDetection')).toBe(false);
    expect(isAnalysisPollingTerminal('upload')).toBe(false);
    expect(isAnalysisPollingTerminal('frameExtraction')).toBe(false);
    expect(isAnalysisPollingTerminal('postProcessing')).toBe(false);
  });

  it('데이터가 아직 없으면(최초 요청 전) 폴링을 계속한다', () => {
    expect(isAnalysisPollingTerminal(undefined)).toBe(false);
  });
});
