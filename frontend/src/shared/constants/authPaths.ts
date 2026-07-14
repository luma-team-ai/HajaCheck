// 인증 관련 공통 경로 — shared/api 인터셉터와 features/auth 양쪽에서 참조(하드코딩 중복 방지)
// basename/서브패스 배포(예: '/app/login', '/haja/login')에서도 로그인 화면 경로로 인식되도록
// 정확 일치가 아닌 접미사(endsWith) 비교에 사용한다.
export const LOGIN_PATH = '/login';
