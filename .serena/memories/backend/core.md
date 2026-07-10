## backend (Spring Boot) — 세부

패키지 구조·구현 상태는 `mem:conventions`, `mem:progress` 참고. 여기선 backend 고유 사항만:

- Java 17 고정(`build.gradle` toolchain) — 전역 CLAUDE.md의 Java 21 컨벤션과 다름, 이 프로젝트에선 17 유지.
- 인증: Spring Security + OAuth2 Client 의존성 추가됨(Kakao/Google), 실제 `auth` 패키지 구현은 아직 없음(`.gitkeep`만).
- 세션: Redis 기반 spring-session-data-redis 의존성 있음 — 세션 저장소로 Redis 사용 예정.
- API 문서: springdoc-openapi-starter-webmvc-ui 2.6.0 — Swagger UI로 실물 엔드포인트 확인 가능(서버 기동 후).
- 코드 컨벤션 SOT: `docs/conventions/SpringBoot_코드_컨벤션.md`.
