-- Flyway V3 — api_system_logs 테이블 추가(#359 forward migration, 원본 #497/HAJA-299 #528).
--
-- ⚠️ V1(baseline)에는 넣지 않는다. V1은 arm1 현재 스키마의 baseline이고(api_system_logs 없음),
-- baseline-on-migrate가 기존 DB(arm1 프로덕션·팀원 로컬)에서 V1 실행을 스킵하므로, 이 테이블을 V1에
-- 넣으면 baseline된 기존 DB는 영영 이 테이블을 받지 못해 향후 validate가 실패한다. forward
-- migration(V3)으로 올려야 빈 새 DB(V1→V2→V3)와 기존 DB(baseline 스탬프 후 V3 실행) 둘 다
-- 이 테이블을 갖게 된다.
--
-- 원본: docs/design/db/HajaCheck_script.sql의 api_system_logs 정의(캐노니컬 DDL, #528로 추가됨) —
-- owner 문·CONCURRENTLY 없이 이미 Flyway-safe라 그대로 옮겼다. 상세 재실행 검증 로직(advisory lock,
-- 카탈로그 대조 등)은 docs/design/db/migrations/20260720_01_create_api_system_logs.sql(수동 마이그레이션
-- 절차, Flyway 이전 보관 문서)에만 있고 이 파일에는 필요 없다 — Flyway가 각 버전을 정확히 한 번만
-- 적용하는 것을 자체 보장한다.
create table api_system_logs
(
    id             bigint generated always as identity
        primary key,
    level          varchar(10)                            not null,
    request_id     varchar(100)                           not null,
    http_method    varchar(10)                            not null,
    endpoint       varchar(500)                           not null,
    http_status    smallint                               not null,
    error_code     varchar(100),
    message        varchar(500),
    exception_type varchar(255),
    user_id        bigint,
    duration_ms    bigint                                 not null,
    client_ip      inet,
    created_at     timestamp with time zone default now() not null,
    constraint ck_api_system_logs_level
        check (level in ('WARN', 'ERROR')),
    constraint ck_api_system_logs_level_http_status
        check (
            (level = 'WARN' and http_status between 400 and 499)
            or (level = 'ERROR' and http_status between 500 and 599)
        ),
    constraint ck_api_system_logs_duration
        check (duration_ms >= 0),
    constraint ck_api_system_logs_request_id_format
        check (
            request_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            or request_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'
        ),
    constraint ck_api_system_logs_endpoint_pattern
        check (endpoint ~ '^/' and endpoint !~ '[?#[:cntrl:]]'),
    constraint ck_api_system_logs_message_no_control
        check (message is null or message !~ '[[:cntrl:]]'),
    constraint ck_api_system_logs_client_ip_masked
        check (
            client_ip is null
            or (
                family(client_ip) = 4
                and masklen(client_ip) = 24
                and client_ip = network(client_ip)::inet
            )
            or (
                family(client_ip) = 6
                and masklen(client_ip) = 48
                and client_ip = network(client_ip)::inet
            )
        )
);

comment on table api_system_logs is 'API 호출 결과가 4xx 또는 5xx인 요청의 시스템 로그를 요청당 최대 한 행으로 기록한다.';

comment on column api_system_logs.id is 'API 시스템 로그 식별자';

comment on column api_system_logs.level is 'HTTP 응답 상태에 따른 로그 레벨(WARN=4xx, ERROR=5xx)';

comment on column api_system_logs.request_id is '서버가 생성하거나 allowlist 검증한 UUID/ULID 요청 추적 식별자. 요청당 최대 한 행은 애플리케이션 정책이며 DB UNIQUE로 강제하지 않는다';

comment on column api_system_logs.http_method is 'API 요청 HTTP 메서드';

comment on column api_system_logs.endpoint is 'raw URI가 아닌 서버 route pattern. query·fragment·control 문자는 허용하지 않는다';

comment on column api_system_logs.http_status is '최종 HTTP 응답 상태 코드';

comment on column api_system_logs.error_code is '애플리케이션 공통 오류 코드';

comment on column api_system_logs.message is 'ErrorCode 기반 고정·정제 오류 요약 메시지. control 문자는 허용하지 않는다';

comment on column api_system_logs.exception_type is '오류를 발생시킨 예외 클래스명';

comment on column api_system_logs.user_id is '로그인 사용자 식별자. 탈퇴·익명화 시 user_id=NULL로 갱신하고 로그 행은 created_at 기준 최대 30일 보존한다. users 외래키는 두지 않는다';

comment on column api_system_logs.duration_ms is 'API 요청 처리 시간(밀리초)';

comment on column api_system_logs.client_ip is '직접 peer 또는 신뢰 프록시 체인에서 판정해 IPv4 /24·IPv6 /48 네트워크 주소로 축약한 클라이언트 IP. 원본 IP와 신뢰되지 않은 forwarded 값은 저장하지 않는다';

comment on column api_system_logs.created_at is 'API 시스템 로그 생성 시각';

create index idx_api_system_logs_created_at
    on api_system_logs (created_at desc);

create index idx_api_system_logs_level_created_at
    on api_system_logs (level, created_at desc);

create index idx_api_system_logs_request_id
    on api_system_logs (request_id);
