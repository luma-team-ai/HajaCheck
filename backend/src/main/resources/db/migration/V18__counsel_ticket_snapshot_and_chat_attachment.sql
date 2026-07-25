-- Flyway V18 — 상담 티켓 스냅샷 필드 + 채팅 이미지 첨부 컬럼(FR-7, #20/HAJA-33).
-- dev에 V13(media.detail_url, #788/#789)~V16(defects.area_ratio, #803)이 이미 선점해
-- V18로 재번호했다(V6/V10과 동일한 재번호 컨벤션). counsel_tickets.counsel_type 컬럼은
-- V14(add_counsel_type, #743)가 먼저 추가하며, 이 마이그레이션은 그 위에 스냅샷 필드만 더한다.
--
-- (1) counsel_tickets: 시나리오에서 진입한 카테고리/제목을 스냅샷으로 저장(시나리오 트리가 나중에 바뀌어도
--     과거 티켓의 이력 표시가 안 바뀌도록 — Media.storageKey 스냅샷과 동일 원칙) + 사람이 읽는 티켓번호.
--     ticket_number 포맷 CS-{yyyyMMdd}-{id zero-pad}은 생성 시점 날짜 + PK 기반이라 별도 시퀀스 불필요.
-- (2) chat_messages: 이미지 첨부(FileStorageService storageKey + MIME). Media 테이블에 행을 만들지 않고
--     chat_messages에 직접 컬럼을 둔다(Media는 inspectionId NOT NULL FK로 강결합돼 재사용 불가).
--
-- 캐노니컬 DDL(HajaCheck_script.sql)에 이미 존재할 수 있어(baseline-on-existing 경로) IF NOT EXISTS로
-- 멱등 처리한다(V12 패턴).
--
-- PR머신 리뷰(P1, PR #820): ticket_number/category/title을 DEFAULT·백필 없이 NOT NULL로 한 번에 추가하면
-- counsel_tickets에 기존 행이 있는 환경에서 Flyway forward-apply가 'column contains null values'로
-- 실패해 앱 기동을 중단시킬 수 있다(#531과 같은 클래스의 가용성 리스크). 이 기능의 REST 쓰기 경로가
-- 아직 어떤 환경에도 배포된 적 없어 실제로는 0건이어야 하지만, prod 재검증에 의존하지 않고도 안전하도록
-- nullable 추가 → 방어적 백필(멱등) → NOT NULL 전환 3단계로 처리한다.
alter table counsel_tickets
    add column if not exists ticket_number varchar(20),
    add column if not exists category      varchar(100),
    add column if not exists title         varchar(200);

update counsel_tickets
   set ticket_number = 'CS-LEGACY-' || id,
       category = 'UNKNOWN',
       title = 'UNKNOWN'
 where ticket_number is null;

alter table counsel_tickets
    alter column ticket_number set not null,
    alter column category set not null,
    alter column title set not null;

create unique index if not exists uq_counsel_tickets_ticket_number
    on counsel_tickets (ticket_number);

comment on column counsel_tickets.ticket_number is '사람이 읽는 상담 티켓 번호(CS-yyyyMMdd-{id}), 생성 시 PK 기반 스냅샷';
comment on column counsel_tickets.category is '진입 시나리오 최상위 카테고리 스냅샷(예: INSPECTION_REPORT)';
comment on column counsel_tickets.title is '진입 시나리오 바로 위 부모 노드 라벨 스냅샷';

alter table chat_messages
    add column if not exists attachment_key       varchar(500),
    add column if not exists attachment_mime_type varchar(100);

comment on column chat_messages.attachment_key is '이미지 첨부 저장키(FileStorageService storageKey, 실제 URL 아님), nullable';
comment on column chat_messages.attachment_mime_type is '이미지 첨부 MIME(image/jpeg·image/png), nullable';
