// @vitest-environment jsdom
// File 생성자 사용(jsdom 환경에서 안전하게 동작 보장)
import { describe, expect, it } from 'vitest';
import { formatFileSize, validateBusinessLicenseFile } from './validateBusinessLicenseFile';

function makeFile(name: string, type: string, sizeBytes: number): File {
  return new File([new Uint8Array(sizeBytes)], name, { type });
}

describe('validateBusinessLicenseFile', () => {
  it('파일이 없으면 FILE_REQUIRED를 반환한다', () => {
    expect(validateBusinessLicenseFile(null)).toBe('FILE_REQUIRED');
  });

  it('허용되지 않은 MIME 타입이면 FILE_INVALID_TYPE을 반환한다', () => {
    const file = makeFile('license.txt', 'text/plain', 1024);
    expect(validateBusinessLicenseFile(file)).toBe('FILE_INVALID_TYPE');
  });

  it('10MB를 초과하면 FILE_TOO_LARGE를 반환한다', () => {
    const file = makeFile('license.png', 'image/png', 11 * 1024 * 1024);
    expect(validateBusinessLicenseFile(file)).toBe('FILE_TOO_LARGE');
  });

  it('허용 타입·용량 이내면 null(통과)을 반환한다', () => {
    const file = makeFile('license.pdf', 'application/pdf', 1024 * 1024);
    expect(validateBusinessLicenseFile(file)).toBeNull();
  });
});

describe('formatFileSize', () => {
  it('1024바이트 미만은 B 단위로 표시한다', () => {
    expect(formatFileSize(500)).toBe('500B');
  });

  it('1024바이트 이상 1MB 미만은 KB 단위로 표시한다', () => {
    expect(formatFileSize(1536)).toBe('1.5KB');
  });

  it('1MB 이상은 MB 단위로 표시한다', () => {
    expect(formatFileSize(1024 * 1024 * 2.5)).toBe('2.5MB');
  });
});
