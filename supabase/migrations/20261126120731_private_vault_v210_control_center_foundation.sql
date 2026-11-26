-- Private Vault V2.1.0 development foundation.
-- No user vault data, no client-side secrets, no published releases.
begin;

create schema pv_private;
revoke all on schema pv_private from public, anon, authenticated;
grant usage on schema pv_private to authenticated;

create table public.admin_roles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    role text not null check (role in ('owner', 'admin')),
    active boolean not null default true,
    created_at timestamptz not null default now()
);
alter table public.admin_roles enable row level security;

create function pv_private.is_vault_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce((select auth.jwt()->>'aal') = 'aal2', false)
        and exists (
            select 1 from public.admin_roles
            where user_id = (select auth.uid()) and active
        );
$$;
revoke all on function pv_private.is_vault_admin() from public, anon, authenticated;
grant execute on function pv_private.is_vault_admin() to authenticated;

create policy admin_role_self_read on public.admin_roles
for select to authenticated using (user_id = (select auth.uid()) and active);

create table public.releases (
    id uuid primary key default gen_random_uuid(),
    version_name text not null check (version_name ~ '^[0-9]{1,6}[.][0-9]{1,6}[.][0-9]{1,6}$'),
    version_code integer not null check (version_code > 0),
    channel text not null default 'stable' check (channel in ('stable', 'beta')),
    priority text not null default 'optional' check (priority in ('optional', 'recommended', 'critical')),
    state text not null default 'draft' check (state in ('draft', 'published', 'paused', 'withdrawn')),
    rollout_percent smallint not null default 0 check (rollout_percent between 0 and 100),
    release_notes text not null default '' check (char_length(release_notes) <= 4000),
    manifest_url text generated always as (
        'https://github.com/tpeditz/Private-vault-android-v2/releases/download/v' || version_name || '/update.json'
    ) stored,
    apk_sha256 text check (apk_sha256 is null or apk_sha256 ~ '^[0-9a-f]{64}$'),
    apk_size_bytes bigint check (apk_size_bytes is null or apk_size_bytes between 1 and 262144000),
    signing_cert_sha256 text not null default '3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770'
        check (signing_cert_sha256 = '3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770'),
    verified_at timestamptz,
    available_from timestamptz not null default now(),
    available_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (available_until is null or available_until > available_from),
    check (state <> 'published' or (apk_sha256 is not null and apk_size_bytes is not null and verified_at is not null)),
    unique (channel, version_code),
    unique (channel, version_name)
);
alter table public.releases enable row level security;
create unique index releases_one_published_per_channel on public.releases(channel) where state = 'published';
create index releases_channel_history on public.releases(channel, version_code desc);
create policy release_public_read on public.releases
for select to anon, authenticated using (
    state = 'published' and available_from <= now() and (available_until is null or available_until > now())
);
create policy release_admin_read on public.releases
for select to authenticated using ((select pv_private.is_vault_admin()));
-- The admin frontend can prepare drafts only. Activation must use a future verified server operation.
create policy release_admin_draft_insert on public.releases
for insert to authenticated with check ((select pv_private.is_vault_admin()) and state = 'draft');
create policy release_admin_draft_update on public.releases
for update to authenticated using ((select pv_private.is_vault_admin()) and state = 'draft')
with check ((select pv_private.is_vault_admin()) and state = 'draft');
create policy release_admin_draft_delete on public.releases
for delete to authenticated using ((select pv_private.is_vault_admin()) and state = 'draft');

create table public.announcements (
    id uuid primary key default gen_random_uuid(),
    title text not null check (char_length(btrim(title)) between 1 and 120),
    message text not null check (char_length(btrim(message)) between 1 and 2000),
    presentation text not null default 'banner' check (presentation in ('banner', 'dialog')),
    target_channel text not null default 'all' check (target_channel in ('all', 'stable', 'beta')),
    min_version_code integer check (min_version_code is null or min_version_code > 0),
    max_version_code integer check (max_version_code is null or max_version_code > 0),
    state text not null default 'draft' check (state in ('draft', 'published', 'archived')),
    repeat_mode text not null default 'once' check (repeat_mode in ('once', 'repeat')),
    starts_at timestamptz not null default now(),
    ends_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (ends_at is null or ends_at > starts_at),
    check (min_version_code is null or max_version_code is null or max_version_code >= min_version_code)
);
alter table public.announcements enable row level security;
create index announcements_published_start on public.announcements(starts_at desc) where state = 'published';
create policy announcement_public_read on public.announcements
for select to anon, authenticated using (
    state = 'published' and starts_at <= now() and (ends_at is null or ends_at > now())
);
create policy announcement_admin_read on public.announcements
for select to authenticated using ((select pv_private.is_vault_admin()));
create policy announcement_admin_insert on public.announcements
for insert to authenticated with check ((select pv_private.is_vault_admin()));
create policy announcement_admin_update on public.announcements
for update to authenticated using ((select pv_private.is_vault_admin()))
with check ((select pv_private.is_vault_admin()));
create policy announcement_admin_delete on public.announcements
for delete to authenticated using ((select pv_private.is_vault_admin()) and state <> 'published');

create table public.feature_flags (
    flag_key text primary key check (flag_key in (
        'beta_enrollment_enabled', 'feedback_enabled', 'online_maintenance', 'update_check_interval_hours'
    )),
    boolean_value boolean,
    integer_value integer,
    is_published boolean not null default false,
    updated_at timestamptz not null default now(),
    check (
        (flag_key = 'update_check_interval_hours' and boolean_value is null and integer_value is not null and integer_value between 6 and 72)
        or (flag_key <> 'update_check_interval_hours' and boolean_value is not null and integer_value is null)
    )
);
alter table public.feature_flags enable row level security;
create policy feature_flag_public_read on public.feature_flags
for select to anon, authenticated using (is_published);
create policy feature_flag_admin_read on public.feature_flags
for select to authenticated using ((select pv_private.is_vault_admin()));
create policy feature_flag_admin_update on public.feature_flags
for update to authenticated using ((select pv_private.is_vault_admin()))
with check ((select pv_private.is_vault_admin()));

create table public.feedback (
    id uuid primary key default gen_random_uuid(),
    category text not null check (category in ('bug', 'suggestion', 'other')),
    message text not null check (char_length(btrim(message)) between 1 and 3000),
    contact text check (contact is null or char_length(contact) <= 254),
    app_version text check (app_version is null or char_length(app_version) <= 30),
    consent boolean not null check (consent),
    status text not null default 'open' check (status in ('open', 'reviewing', 'resolved')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
alter table public.feedback enable row level security;
create index feedback_status_created on public.feedback(status, created_at desc);
create policy feedback_admin_read on public.feedback
for select to authenticated using ((select pv_private.is_vault_admin()));
create policy feedback_admin_update on public.feedback
for update to authenticated using ((select pv_private.is_vault_admin()))
with check ((select pv_private.is_vault_admin()));
create policy feedback_admin_delete on public.feedback
for delete to authenticated using ((select pv_private.is_vault_admin()));
-- No anonymous or authenticated insert permission until an abuse-protected,
-- consent-based submission endpoint is implemented and reviewed.

create table public.admin_audit_log (
    id uuid primary key default gen_random_uuid(),
    occurred_at timestamptz not null default now(),
    actor_user_id uuid,
    event text not null check (event in ('INSERT', 'UPDATE', 'DELETE')),
    entity text not null check (entity in ('admin_roles', 'releases', 'announcements', 'feature_flags', 'feedback')),
    entity_key text not null,
    before_state jsonb,
    after_state jsonb
);
alter table public.admin_audit_log enable row level security;
create index admin_audit_log_occurred on public.admin_audit_log(occurred_at desc);
create policy audit_admin_read on public.admin_audit_log
for select to authenticated using ((select pv_private.is_vault_admin()));

create table public.client_policy_state (
    singleton boolean primary key default true check (singleton),
    revision bigint not null default 1 check (revision >= 1),
    updated_at timestamptz not null default now()
);
alter table public.client_policy_state enable row level security;
create policy client_policy_state_read on public.client_policy_state
for select to anon, authenticated using (true);

insert into public.client_policy_state(singleton, revision) values (true, 1);
insert into public.feature_flags(flag_key, boolean_value, integer_value, is_published) values
    ('beta_enrollment_enabled', false, null, true),
    ('feedback_enabled', false, null, true),
    ('online_maintenance', false, null, true),
    ('update_check_interval_hours', null, 12, true);

create function pv_private.set_updated_at()
returns trigger language plpgsql
set search_path = ''
as $$ begin new.updated_at := now(); return new; end; $$;
revoke all on function pv_private.set_updated_at() from public, anon, authenticated;

create function pv_private.safe_audit_state(entity_name text, row_data jsonb)
returns jsonb language sql immutable
set search_path = ''
as $$
select case
    when row_data is null then null
    when entity_name = 'releases' then jsonb_build_object(
        'version_code', row_data->'version_code', 'channel', row_data->'channel',
        'state', row_data->'state', 'priority', row_data->'priority',
        'rollout_percent', row_data->'rollout_percent')
    when entity_name = 'announcements' then jsonb_build_object(
        'state', row_data->'state', 'presentation', row_data->'presentation',
        'target_channel', row_data->'target_channel')
    when entity_name = 'feature_flags' then jsonb_build_object(
        'boolean_value', row_data->'boolean_value', 'integer_value', row_data->'integer_value',
        'is_published', row_data->'is_published')
    when entity_name = 'feedback' then jsonb_build_object('category', row_data->'category', 'status', row_data->'status')
    when entity_name = 'admin_roles' then jsonb_build_object('role', row_data->'role', 'active', row_data->'active')
    else null end
$$;
revoke all on function pv_private.safe_audit_state(text, jsonb) from public, anon, authenticated;

create function pv_private.log_admin_change()
returns trigger language plpgsql
security definer set search_path = ''
as $$
declare before_row jsonb; after_row jsonb; key_value text;
begin
    if tg_op <> 'INSERT' then before_row := to_jsonb(old); end if;
    if tg_op <> 'DELETE' then after_row := to_jsonb(new); end if;
    key_value := coalesce(after_row->>'id', before_row->>'id', after_row->>'flag_key',
                           before_row->>'flag_key', after_row->>'user_id', before_row->>'user_id');
    insert into public.admin_audit_log(actor_user_id, event, entity, entity_key, before_state, after_state)
    values (auth.uid(), tg_op, tg_table_name, key_value,
        pv_private.safe_audit_state(tg_table_name, before_row),
        pv_private.safe_audit_state(tg_table_name, after_row));
    return null;
end;
$$;
revoke all on function pv_private.log_admin_change() from public, anon, authenticated;

create function pv_private.bump_policy_revision()
returns trigger language plpgsql
security definer set search_path = ''
as $$
declare before_row jsonb; after_row jsonb;
begin
    if tg_op <> 'INSERT' then before_row := to_jsonb(old); end if;
    if tg_op <> 'DELETE' then after_row := to_jsonb(new); end if;
    if before_row->>'state' = 'published' or after_row->>'state' = 'published'
       or before_row->>'is_published' = 'true' or after_row->>'is_published' = 'true' then
        update public.client_policy_state set revision = revision + 1, updated_at = now()
        where singleton;
    end if;
    return null;
end;
$$;
revoke all on function pv_private.bump_policy_revision() from public, anon, authenticated;

create trigger releases_updated_at before update on public.releases for each row execute function pv_private.set_updated_at();
create trigger announcements_updated_at before update on public.announcements for each row execute function pv_private.set_updated_at();
create trigger flags_updated_at before update on public.feature_flags for each row execute function pv_private.set_updated_at();
create trigger feedback_updated_at before update on public.feedback for each row execute function pv_private.set_updated_at();

create trigger roles_audit after insert or update or delete on public.admin_roles for each row execute function pv_private.log_admin_change();
create trigger releases_audit after insert or update or delete on public.releases for each row execute function pv_private.log_admin_change();
create trigger announcements_audit after insert or update or delete on public.announcements for each row execute function pv_private.log_admin_change();
create trigger flags_audit after insert or update or delete on public.feature_flags for each row execute function pv_private.log_admin_change();
create trigger feedback_audit after update or delete on public.feedback for each row execute function pv_private.log_admin_change();

create trigger releases_revision after insert or update or delete on public.releases for each row execute function pv_private.bump_policy_revision();
create trigger announcement_revision after insert or update or delete on public.announcements for each row execute function pv_private.bump_policy_revision();
create trigger flags_revision after insert or update or delete on public.feature_flags for each row execute function pv_private.bump_policy_revision();

-- Explicit grants; new projects do not automatically expose every public table.
revoke all on table public.admin_roles, public.releases, public.announcements,
    public.feature_flags, public.feedback, public.admin_audit_log, public.client_policy_state
    from public, anon, authenticated;
grant select on public.releases, public.announcements, public.feature_flags, public.client_policy_state
    to anon, authenticated;
grant select on public.admin_roles, public.feedback, public.admin_audit_log to authenticated;
grant insert (version_name, version_code, channel, priority, rollout_percent, release_notes, available_from, available_until)
    on public.releases to authenticated;
grant update (version_name, version_code, channel, priority, rollout_percent, release_notes, available_from, available_until)
    on public.releases to authenticated;
grant delete on public.releases to authenticated;
grant insert (title, message, presentation, target_channel, min_version_code, max_version_code, state, repeat_mode, starts_at, ends_at)
    on public.announcements to authenticated;
grant update (title, message, presentation, target_channel, min_version_code, max_version_code, state, repeat_mode, starts_at, ends_at)
    on public.announcements to authenticated;
grant delete on public.announcements to authenticated;
grant update (boolean_value, integer_value, is_published) on public.feature_flags to authenticated;
grant update (status) on public.feedback to authenticated;
grant delete on public.feedback to authenticated;

-- Provider-created event-trigger helper does not need client execution.
revoke execute on function public.rls_auto_enable() from public, anon, authenticated;

commit;
