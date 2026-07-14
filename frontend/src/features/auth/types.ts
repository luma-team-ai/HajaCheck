// 로그인 화면 — HAJA-160(#157) — SpringBoot 사용자 도메인 Role enum과 값 일치
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — auth 전용 로컬 정의

export type Role = 'ADMIN' | 'INSPECTOR' | 'USER' | 'COUNSELOR';

export interface User {
  id: number;
  email: string;
  name: string;
  role: Role;
  companyId: number | null;
  profileImageUrl: string | null;
}

// 백엔드 응답 DTO 형태 — 현재는 User와 동일 필드
export type UserResponse = User;

export interface LoginRequest {
  loginId: string;
  password: string;
}
