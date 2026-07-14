import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCsrfPrime } from '../hooks/useCsrfPrime';
import { useFindLoginId } from '../hooks/useFindLoginId';
import { isFindIdFormValid } from '../utils/validateFindIdForm';
import '../auth.css';

// 계정 열거 방지(계약 공통 규약) — 무매칭 실패 메시지 통일
const ERROR_MESSAGES: Record<string, string> = {
  AUTH_ACCOUNT_NOT_FOUND: '일치하는 계정을 찾을 수 없습니다.',
};
const DEFAULT_ERROR_MESSAGE = '아이디 찾기에 실패했습니다. 잠시 후 다시 시도해 주세요.';

export function FindIdPage() {
  useCsrfPrime();

  const [businessRegistrationNumber, setBusinessRegistrationNumber] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [representativeName, setRepresentativeName] = useState('');
  const [showValidation, setShowValidation] = useState(false);

  const { findLoginId, isPending, result, error } = useFindLoginId();

  const isFormValid = isFindIdFormValid(businessRegistrationNumber, companyName, representativeName);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);
    if (!isFormValid) return;

    findLoginId({
      businessRegistrationNumber,
      companyName: companyName.trim(),
      representativeName: representativeName.trim(),
    });
  };

  const errorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;

  return (
    <div className="auth-standalone-page">
      <section className="auth-standalone-panel">
        <h1 className="auth-standalone-title">기업 아이디 찾기</h1>

        {result ? (
          <div className="auth-find-result">
            <p className="auth-find-result-label">찾으시는 아이디는 다음과 같습니다.</p>
            <p className="auth-find-result-value">{result.maskedEmail}</p>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="auth-form-field">
              <label className="auth-form-label" htmlFor="find-id-business-number">
                사업자등록번호
              </label>
              <input
                id="find-id-business-number"
                type="text"
                className="auth-form-input"
                value={businessRegistrationNumber}
                onChange={(event) => setBusinessRegistrationNumber(event.target.value)}
                placeholder="'-' 제외 10자리"
              />
            </div>

            <div className="auth-form-field">
              <label className="auth-form-label" htmlFor="find-id-company-name">
                상호명
              </label>
              <input
                id="find-id-company-name"
                type="text"
                className="auth-form-input"
                value={companyName}
                onChange={(event) => setCompanyName(event.target.value)}
              />
            </div>

            <div className="auth-form-field">
              <label className="auth-form-label" htmlFor="find-id-representative-name">
                대표자명
              </label>
              <input
                id="find-id-representative-name"
                type="text"
                className="auth-form-input"
                value={representativeName}
                onChange={(event) => setRepresentativeName(event.target.value)}
              />
            </div>

            {showValidation && !isFormValid && (
              <p className="auth-form-error">사업자등록번호와 상호명(또는 대표자명)을 입력해 주세요.</p>
            )}
            {errorMessage && <p className="auth-form-error">{errorMessage}</p>}

            <button type="submit" className="company-login-submit-btn" disabled={isPending}>
              {isPending ? '확인 중...' : '아이디 확인'}
            </button>
          </form>
        )}

        <div className="auth-links-row">
          <Link to="/login" className="auth-link-btn">
            로그인으로
          </Link>
          <span className="auth-links-divider">|</span>
          <Link to="/find-password" className="auth-link-btn">
            비밀번호 찾기
          </Link>
        </div>
      </section>
    </div>
  );
}
