import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { BusinessLicenseUpload } from '../components/BusinessLicenseUpload';
import { CompanyAddressField } from '../components/CompanyAddressField';
import { CompanySignupHeroPanel } from '../components/CompanySignupHeroPanel';
import { LOGIN_ROUTE } from '../constants';
import { useCompanySignup } from '../hooks/useCompanySignup';
import { useEmailAvailability } from '../hooks/useEmailAvailability';
import { isValidBusinessNumber, isValidEmail, isValidPassword, doPasswordsMatch } from '../utils/authFormValidators';
import { validateBusinessLicenseFile } from '../utils/validateBusinessLicenseFile';
import { isCompanySignupFormValid } from '../utils/validateCompanySignupForm';

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

const LABEL_CLASSES = 'text-sm font-medium text-text-default';
const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3.5 py-3 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';
const ERROR_CLASSES = 'text-xs text-danger';
// auth.css의 기존 .auth-form-success(#1a9a52)와 동일 값을 그대로 이식 — 신규 색이 아니라 기존
// 성공색을 재사용하는 것. tokens.css는 타 오너 자산이라 미터치 규칙상 토큰 승격 대신 로컬 상수로 유지.
const SUCCESS_CLASSES = 'text-xs text-[#1a9a52]';
const INLINE_BTN_CLASSES =
  'shrink-0 cursor-pointer whitespace-nowrap rounded-lg border border-border bg-surface px-4 text-sm font-semibold text-text-default enabled:hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted';
const PASSWORD_TOGGLE_CLASSES =
  'absolute right-2.5 cursor-pointer border-none bg-transparent p-1.5 text-base leading-none';

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
  const [agreeTermsOfService, setAgreeTermsOfService] = useState(false);
  const [agreePrivacyPolicy, setAgreePrivacyPolicy] = useState(false);
  const [showValidation, setShowValidation] = useState(false);

  const {
    checkEmailAvailability,
    isPending: isCheckingEmail,
    result: emailCheckResult,
    reset: resetEmailCheck,
  } = useEmailAvailability();
  const { signup, isPending, error } = useCompanySignup();

  const handleCheckEmail = () => {
    if (!isValidEmail(email)) return;
    checkEmailAvailability(email.trim());
  };

  // 이메일을 바꾸면 이전 중복확인 결과(stale)를 즉시 무효화 — A로 확인 후 B로 바꿔도
  // A의 "사용 가능" 결과가 남아 잘못된 메시지·제출 판정으로 이어지는 것 방지(PR머신 P2)
  const handleEmailChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setEmail(event.target.value);
    if (emailCheckResult) resetEmailCheck();
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
      agreeTermsOfService,
      agreePrivacyPolicy,
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
      agreeTermsOfService,
      agreePrivacyPolicy,
      businessRegistrationFile: file as File,
    });
  };

  const submitErrorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;
  const fileError = showValidation ? validateBusinessLicenseFile(file) : null;

  return (
    <div className="flex min-h-screen bg-white">
      <CompanySignupHeroPanel />

      <section className="flex flex-1 justify-center overflow-y-auto px-6 py-14 sm:px-10 lg:px-16">
        <div className="w-full max-w-[440px]">
          <p className="m-0 text-sm font-medium text-text-muted">기업 회원가입</p>
          <h1 className="m-0 mt-1.5 text-2xl font-bold text-heading">회사 계정을 만들어 주세요</h1>
          <p className="m-0 mt-2 text-sm text-text-muted">
            기업 승인 후 검수 워크스페이스가 활성화됩니다.
          </p>

          <form className="mt-8 flex flex-col gap-5" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-email">
                아이디(이메일)
              </label>
              <div className="flex gap-2">
                <input
                  id="signup-email"
                  type="email"
                  className={INPUT_CLASSES}
                  value={email}
                  onChange={handleEmailChange}
                  autoComplete="username"
                />
                <button
                  type="button"
                  className={INLINE_BTN_CLASSES}
                  onClick={handleCheckEmail}
                  disabled={isCheckingEmail || !isValidEmail(email)}
                >
                  중복확인
                </button>
              </div>
              {emailCheckResult && (
                <p className={emailCheckResult.available ? SUCCESS_CLASSES : ERROR_CLASSES}>
                  {emailCheckResult.available ? '사용 가능한 이메일입니다.' : '이미 가입된 이메일입니다.'}
                </p>
              )}
              {showValidation && !isValidEmail(email) && (
                <p className={ERROR_CLASSES}>올바른 이메일 형식을 입력해 주세요.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-password">
                비밀번호
              </label>
              <div className="relative flex items-center">
                <input
                  id="signup-password"
                  type={isPasswordVisible ? 'text' : 'password'}
                  className={`${INPUT_CLASSES} pr-11`}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  placeholder="비밀번호 입력 (8자 이상)"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsPasswordVisible((prev) => !prev)}
                >
                  {isPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              {showValidation && !isValidPassword(password) && (
                <p className={ERROR_CLASSES}>8자 이상, 영문+숫자를 포함해 주세요.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-confirm-password">
                비밀번호 재입력
              </label>
              <div className="relative flex items-center">
                <input
                  id="signup-confirm-password"
                  type={isConfirmPasswordVisible ? 'text' : 'password'}
                  className={`${INPUT_CLASSES} pr-11`}
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  placeholder="비밀번호를 다시 입력해 주세요"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isConfirmPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsConfirmPasswordVisible((prev) => !prev)}
                >
                  {isConfirmPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              {confirmPassword && (
                <p className={doPasswordsMatch(password, confirmPassword) ? SUCCESS_CLASSES : ERROR_CLASSES}>
                  {doPasswordsMatch(password, confirmPassword)
                    ? '✓ 비밀번호가 일치합니다'
                    : '비밀번호가 일치하지 않습니다.'}
                </p>
              )}
            </div>

            <CompanyAddressField
              address={address}
              addressDetail={addressDetail}
              onAddressChange={setAddress}
              onAddressDetailChange={setAddressDetail}
            />

            {/* 사업자등록증 블록 — 시안 기준 파일업로드 + 사업자정보 3필드를 카드 하나로 그룹핑(#292) */}
            <div className="flex flex-col gap-4 rounded-xl border border-border p-4">
              <BusinessLicenseUpload
                file={file}
                onFileSelect={setFile}
                errorMessage={fileError ? ERROR_MESSAGES[fileError] : null}
              />

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-business-number">
                  사업자등록번호
                </label>
                <input
                  id="signup-business-number"
                  type="text"
                  className={INPUT_CLASSES}
                  value={businessRegistrationNumber}
                  onChange={(event) => setBusinessRegistrationNumber(event.target.value)}
                  placeholder="000-00-00000"
                />
                {showValidation && !isValidBusinessNumber(businessRegistrationNumber) && (
                  <p className={ERROR_CLASSES}>사업자등록번호 10자리를 입력해 주세요.</p>
                )}
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-company-name">
                  상호명
                </label>
                <input
                  id="signup-company-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={companyName}
                  onChange={(event) => setCompanyName(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-representative-name">
                  대표자명
                </label>
                <input
                  id="signup-representative-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={representativeName}
                  onChange={(event) => setRepresentativeName(event.target.value)}
                />
              </div>
            </div>

            <div className="flex items-start gap-2 rounded-lg bg-surface-muted px-3.5 py-3 text-xs text-text-muted">
              <span aria-hidden="true">ⓘ</span>
              <span>기업 회원가입은 관리자 승인 후 완료되며, 영업일 기준 2-3일이 소요될 수 있습니다.</span>
            </div>

            {/* 이용약관 동의·개인정보 수집·이용 동의는 개인정보보호법상 별도 동의 대상이라 체크박스를
                분리한다(PR머신 P2) — 시안은 1개 통합 체크박스이나 법적 요건상 2개 분리를 유지한다(#292) */}
            <div className="flex flex-col gap-1.5">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text-default">
                <input
                  type="checkbox"
                  checked={agreeTermsOfService}
                  onChange={(event) => setAgreeTermsOfService(event.target.checked)}
                />
                (필수) 이용약관에 동의합니다.
              </label>
              {showValidation && !agreeTermsOfService && (
                <p className={ERROR_CLASSES}>이용약관에 동의해야 가입할 수 있습니다.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text-default">
                <input
                  type="checkbox"
                  checked={agreePrivacyPolicy}
                  onChange={(event) => setAgreePrivacyPolicy(event.target.checked)}
                />
                (필수) 개인정보 수집 및 이용에 동의합니다.
              </label>
              {showValidation && !agreePrivacyPolicy && (
                <p className={ERROR_CLASSES}>개인정보 수집·이용에 동의해야 가입할 수 있습니다.</p>
              )}
            </div>

            {submitErrorMessage && <p className={ERROR_CLASSES}>{submitErrorMessage}</p>}

            <Button type="submit" size="lg" className="w-full" disabled={isPending}>
              {isPending ? '신청 중...' : '가입 신청하기'}
            </Button>

            <p className="m-0 text-center text-sm text-text-muted">
              이미 계정이 있으신가요?{' '}
              <Link to={LOGIN_ROUTE} className="font-medium text-heading underline">
                로그인
              </Link>
            </p>
          </form>
        </div>
      </section>
    </div>
  );
}
