-- Flyway V32 — 조치 등록 이력 append-only 테이블 신설(#1193/HAJA-569).
--
-- 번호 조율: 착수 시점엔 V29였으나 그 사이 V29(reports.deleted_at, #1172)·V30(scheduled_plan_changes,
-- #1105/HAJA-526)·V31(notification_type 예약 하향 라벨, #1105/HAJA-526)이 먼저 dev에 들어와 다음 빈
-- 번호인 V32로 재조정한다(V1~V31 전부 무수정, project convention — 먼저 머지되는 쪽이 번호를 가져간다).
--
-- defects.action_media_id/action_content/action_date/action_assignee_id는 "최신 스냅샷"만 저장하는
-- 단일 값 컬럼이라(V12), 조치중(IN_PROGRESS) 단계에서 시간차를 두고 여러 번 등록하는 사진/조치내용
-- 이력이 쌓이지 않고 매번 덮어써진다. defect_revisions(V2)는 필드 변경 감사용이라 사진 URL을 직접
-- 담기엔 스키마가 맞지 않아(varchar(255) old/new_value), 조치 등록 제출 자체를 통째로 append하는
-- 별도 테이블을 신설한다.
--
-- 멱등 가드(IF NOT EXISTS / DO 블록): 캐노니컬 DDL(HajaCheck_script.sql)에 이 스키마를 함께 반영하므로,
-- baseline-on-existing 경로(이미 캐노니컬 전체 스키마가 적재된 DB)에서 이 파일이 처음 적용될 때
-- 'already exists'로 기동을 깨뜨리지 않아야 한다(V12·V18·V20과 동일 패턴).
--
-- phase는 기존 defect_status_type(defects.status)을 재사용하지 않고 별도 2라벨 enum을 새로 만든다 —
-- defect_status_type을 재사용했다면 V22(#1193 이전에 이미 dev에 존재하는 마이그레이션, 절대 수정 금지)가
-- "구 타입 drop → 새 타입으로 rename"하는 과정에서 이 테이블의 컬럼이 구 타입을 계속 참조해 V22가
-- "cannot drop type ... other objects depend on it"로 실패한다(baseline-on-existing 경로 회귀).
do
$$
    begin
        if not exists (select 1 from pg_type where typname = 'defect_action_log_phase_type') then
            create type defect_action_log_phase_type as enum ('IN_PROGRESS', 'RESOLVED');
        end if;
    end
$$;

comment on type defect_action_log_phase_type is '조치 등록 이력의 제출 시점 전이 목표(IN_PROGRESS/RESOLVED만 가능) — defect_status_type과 독립된 별도 타입';

create table if not exists defect_action_logs
(
    id                 bigint generated always as identity
        primary key,
    defect_id          bigint                                 not null
        references defects,
    media_id           bigint                                 not null
        references media,
    phase              defect_action_log_phase_type           not null,
    action_content     text                                   not null,
    action_date        date                                   not null,
    action_assignee_id bigint                                 not null
        references users,
    created_at         timestamp with time zone default now() not null
);

comment on table defect_action_logs is '조치 등록 제출 이력(append-only, #1193/HAJA-569) — 조치중 단계 다중 등록 지원';
comment on column defect_action_logs.defect_id is '대상 하자(defects.id)';
comment on column defect_action_logs.media_id is '해당 제출의 조치 사진 media id';
comment on column defect_action_logs.phase is '제출 시점의 targetStatus(IN_PROGRESS 또는 RESOLVED)';
comment on column defect_action_logs.action_content is '해당 제출의 조치 내용';
comment on column defect_action_logs.action_date is '해당 제출의 조치일';
comment on column defect_action_logs.action_assignee_id is '해당 제출의 조치 담당자(users.id)';

create index if not exists idx_defect_action_logs_defect_phase
    on defect_action_logs (defect_id, phase);
