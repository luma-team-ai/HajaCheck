import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BusinessLicenseUpload } from '../components/BusinessLicenseUpload';
import { CompanyAddressField } from '../components/CompanyAddressField';
import { useCompanySignup } from '../hooks/useCompanySignup';
import { useEmailAvailability } from '../hooks/useEmailAvailability';
import { isValidBusinessNumber, isValidEmail, isValidPassword, doPasswordsMatch } from '../utils/authFormValidators';
import { validateBusinessLicenseFile } from '../utils/validateBusinessLicenseFile';
import { isCompanySignupFormValid } from '../utils/validateCompanySignupForm';
import '../auth.css';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_EMAIL_DUPLICATED: '이미 가입된 이메일입니다.',
  AUTH_BUSINESS_NUMBER_DUPLICATED: '이미 등록된 사업자등록번호입니다.',
  FILE_REQUIRED: '사업자등록증 파일을 첨부해 주세요.',
  FILE_INVALID_TYPE: '지원하지 않는 파일 형식입니다. (JPG, PNG, PDF만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
  INVALID_INPUT: '입력값을 다시 확인해 주세요.',
  FILE_UPLOAD_FAILED: '파일 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.',
};
const DEFAULT_ERROR_MESSAGE = '가입 신청에 실패했습니다. 잠시 후 다시 시도해 주세요.';

export function CompanySignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);
  const [isConfirmPasswordVisible, setIsConfirmPasswordVisible] = useState(false);
  const [companyName, setCompanyName] = useState('');
  const [businessRegistrationNumber, setBusinessRegistrationNumber] = useState('');
  const [representativeName, setRepresentativeName] = useState('');
  const [address, setAddress] = useState('');
  const [addressDetail, setAddressDetail] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [agreeToTerms, setAgreeToTerms] = useState(false);
  const [showValidation, setShowValidation] = useState(false);

  const { checkEmailAvailability, isPending: isCheckingEmail, result: emailCheckResult } =
    useEmailAvailability();
  const { signup, isPending, error } = useCompanySignup();

  const handleCheckEmail = () => {
    if (!isValidEmail(email)) return;
    checkEmailAvailability(email.trim());
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);

    const form = {
      email,
      password,
      confirmPassword,
      companyName,
      businessRegistrationNumber,
      representativeName,
      address,
      businessRegistrationFile: file,
      agreeToTerms,
    };
    if (!isCompanySignupFormValid(form)) return;
    if (emailCheckResult?.available === false) return;

    signup({
      email: email.trim(),
      password,
      companyName: companyName.trim(),
      businessRegistrationNumber,
      representativeName: representativeName.trim(),
      address,
      addressDetail,
      agreeTermsOfService: agreeToTerms,
      agreePrivacyPolicy: agreeToTerms,
      businessRegistrationFile: file as File,
    });
  };

  const submitErrorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;
  const fileError = showValidation ? validateBusinessLicenseFile(file) : null;

  return (
    <div className="auth-standalone-page">
      <section className="auth-standalone-panel auth-standalone-panel--wide">
        <h1 className="auth-standalone-title">기업 회원가입</h1>

        <form className="company-signup-form" onSubmit={handleSubmit}>
          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="signup-email">
              아이디(이메일)
            </label>
            <div className="auth-inline-check-row">
              <input
                id="signup-email"
                type="email"
                className="auth-form-input"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="username"
              />
              <button
                type="button"
                className="auth-inline-check-btn"
                onClick={handleCheckEmail}
                disabled={isCheckingEmail || !isValidEmail(email)}
              >
                중복확인
              </button>
            </div>
            {emailCheckResult && (
              <p className={emailCheckResult.available ? 'auth-form-success' : 'auth-form-error'}>
                {emailCheckResult.available ? '사용 가능한 이메일입니다.' : '이미 가입된 이메일입니다.'}
              </p>
            )}
            {showValidation && !isValidEmail(email) && (
              <p className="auth-form-error">올바른 이메일 형식을 입력해 주세요.</p>
            )}
          </div>

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="signup-password">
              비밀번호
            </label>
            <div className="auth-password-input-wrap">
              <input
                id="signup-password"
                type={isPasswordVisible ? 'text' : 'password'}
                className="auth-form-input"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="new-password"
              />
              <button
                type="button"
                className="auth-password-toggle-btn"
                aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                onClick={() => setIsPasswordVisible((prev) => !prev)}
              >
                {isPasswordVisible ? '🙈' : '👁'}
              </button>
            </div>
            {showValidation && !isValidPassword(password) && (
              <p className="auth-form-error">8자 이상, 영문+숫자를 포함해 주세요.</p>
            )}
          </div>

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="signup-confirm-password">
              비밀번호 재입력
            </label>
            <div className="auth-password-input-wrap">
              <input
                id="signup-confirm-password"
                type={isConfirmPasswordVisible ? 'text' : 'password'}
                className="auth-form-input"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                autoComplete="new-password"
              />
              <button
                type="button"
                className="auth-password-toggle-btn"
                aria-label={isConfirmPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                onClick={() => setIsConfirmPasswordVisible((prev) => !prev)}
              >
                {isConfirmPasswordVisible ? '🙈' : '👁'}
              </button>
            </div>
            {confirmPassword && (
              <p className={doPasswordsMatch(password, confirmPassword) ? 'auth-form-success' : 'auth-form-error'}>
                {doPasswordsMatch(password, confirmPassword) ? '비밀번호가 일치합니다.' : '비밀번호가 일치하지 않습니다.'}
              </p>
            )}
          </div>

          <CompanyAddressField
            address={address}
            addressDetail={addressDetail}
            onAddressChange={setAddress}
            onAddressDetailChange={setAddressDetail}
          />

          <BusinessLicenseUpload
            file={file}
            onFileSelect={setFile}
            errorMessage={fileError ? ERROR_MESSAGES[fileError] : null}
          />

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="signup-business-number">
              사업자등록번호
            </label>
            <input
              id="signup-business-number"
              type="text"
              className="auth-form-input"
              value={businessRegistrationNumber}
              onChange={(event) => setBusinessRegistrationNumber(event.target.value)}
              placeholder="000-00-00000"
            />
            {showValidation && !isValidBusinessNumber(businessRegistrationNumber) && (
              <p className="auth-form-error">사업자등록번호 10자리를 입력해 주세요.</p>
            )}
          </div>

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="signup-company-name">
              상호명
            </label>
            <input
              id="signup-company-name"
              type="text"
              className="auth-form-input"
              value={companyName}
              onChange={(event) => setCompanyName(event.target.value)}
            />
          </div>

          <div className="auth-form-field">
            <label className="auth-form-label" htmlFor="signup-representative-name">
              대표자명
            </label>
            <input
              id="signup-representative-name"
              type="text"
              className="auth-form-input"
              value={representativeName}
              onChange={(event) => setRepresentativeName(event.target.value)}
            />
          </div>

          <div className="auth-signup-notice">
            관리자 승인 후 가입이 완료됩니다. 승인까지 영업일 기준 2-3일이 소요될 수 있어요.
          </div>

          <label className="auth-terms-checkbox">
            <input
              type="checkbox"
              checked={agreeToTerms}
              onChange={(event) => setAgreeToTerms(event.target.checked)}
            />
            (필수) 이용약관 및 개인정보처리방침에 동의합니다.
          </label>
          {showValidation && !agreeToTerms && (
            <p className="auth-form-error">약관에 동의해야 가입할 수 있습니다.</p>
          )}

          {submitErrorMessage && <p className="auth-form-error">{submitErrorMessage}</p>}

          <button type="submit" className="company-login-submit-btn" disabled={isPending}>
            {isPending ? '신청 중...' : '가입 신청하기'}
          </button>

          <p className="auth-panel-guide">
            이미 계정이 있으신가요? <Link to="/login">로그인</Link>
          </p>
        </form>
      </section>
    </div>
  );
}
