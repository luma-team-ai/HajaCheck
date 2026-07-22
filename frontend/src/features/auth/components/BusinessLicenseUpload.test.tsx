// @vitest-environment jsdom
// #298 — OCR 예상화면(잘못된 랜딩 히어로 이미지) 제거 + 드래그앤드롭 업로드 버튼 회귀 방지.
// 특히 드롭 경로 검증(accept 속성이 드래그앤드롭엔 적용되지 않는 문제)을 고정하는 테스트 포함.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BusinessLicenseUpload } from './BusinessLicenseUpload';

afterEach(cleanup);

function getDropzone() {
  // 드롭존 컨테이너 = "파일을 끌어다 놓거나" 문구의 부모 요소
  return screen.getByText('파일을 끌어다 놓거나').parentElement as HTMLElement;
}

describe('BusinessLicenseUpload', () => {
  it('"파일 선택" 버튼 클릭 시 숨겨진 input의 클릭을 트리거한다', () => {
    const onFileSelect = vi.fn();
    render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const input = screen.getByLabelText('사업자등록증') as HTMLInputElement;
    const clickSpy = vi.spyOn(input, 'click');

    fireEvent.click(screen.getByRole('button', { name: '파일 선택' }));

    expect(clickSpy).toHaveBeenCalledTimes(1);
  });

  it('파일 선택(클릭 경로) 시 파일명·용량 칩을 노출하고 ✕ 클릭 시 제거한다', () => {
    const onFileSelect = vi.fn();
    const { rerender } = render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const file = new File(['dummy'], 'license.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });

    expect(onFileSelect).toHaveBeenCalledWith(file);

    rerender(<BusinessLicenseUpload file={file} onFileSelect={onFileSelect} />);

    expect(screen.getByText('license.png')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '첨부 파일 삭제' }));
    expect(onFileSelect).toHaveBeenCalledWith(null);
  });

  it('드롭으로 허용되지 않는 타입을 투입하면 onFileSelect를 호출하지 않고 에러를 노출한다', () => {
    const onFileSelect = vi.fn();
    render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const invalidFile = new File(['not a license'], 'malware.exe', { type: 'text/plain' });
    fireEvent.drop(getDropzone(), { dataTransfer: { files: [invalidFile] } });

    expect(onFileSelect).not.toHaveBeenCalled();
    expect(screen.getByText('지원하지 않는 파일 형식입니다. (JPG, PNG, PDF만 가능)')).not.toBeNull();
  });

  it('드롭으로 유효한 PDF를 투입하면 onFileSelect가 호출된다', () => {
    const onFileSelect = vi.fn();
    render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const validFile = new File(['%PDF-1.4'], 'license.pdf', { type: 'application/pdf' });
    fireEvent.drop(getDropzone(), { dataTransfer: { files: [validFile] } });

    expect(onFileSelect).toHaveBeenCalledWith(validFile);
  });

  it('OCR 예상화면 이미지를 더 이상 렌더링하지 않는다', () => {
    render(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} />);

    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.queryByText(/자동인식 완료 시 예상 화면/)).toBeNull();
  });

  it('OCR 자동채움 구현(#587, 개업일자 확장 #600) 후 실제 동작에 맞는 안내 문구를 노출한다', () => {
    render(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} />);

    expect(
      screen.getByText(
        /JPG, PNG 파일은 업로드 시 사업자등록번호·상호명·대표자명·개업일자가 자동으로 채워집니다/,
      ),
    ).not.toBeNull();
    expect(screen.queryByText(/준비 중입니다/)).toBeNull();
  });

  it('isOcrLoading이 true면 자동인식 중 로딩 문구를 노출한다', () => {
    const { rerender } = render(
      <BusinessLicenseUpload file={null} onFileSelect={vi.fn()} isOcrLoading={false} />,
    );
    expect(screen.queryByRole('status')).toBeNull();

    rerender(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} isOcrLoading={true} />);
    expect(screen.getByRole('status').textContent).toContain('자동인식하는 중입니다');
  });
});
