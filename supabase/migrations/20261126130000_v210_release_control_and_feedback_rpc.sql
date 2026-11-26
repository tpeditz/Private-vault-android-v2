-- Private Vault V2.1.0 Follow-Up Migration (Reviewable Only — Do Not Apply Without Owner/ChatGPT Approval)
-- Adds atomic service-role-only RPCs for verified release state transitions and rate-limited feedback submission.
begin;

create or replace function pv_private.admin_transition_release(
    p_release_id uuid,
    p_action text,
    p_actor_user_id uuid,
    p_apk_sha256 text default null,
    p_apk_size_bytes bigint default null,
    p_signing_cert_sha256 text default null,
    p_priority text default null,
    p_rollout_percent smallint default null,
    p_available_from timestamptz default null,
    p_available_until timestamptz default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_target public.releases%rowtype;
    v_updated public.releases%rowtype;
    v_max_prior_code integer;
    v_expected_cert constant text := '3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770';
begin
    if not exists (
        select 1 from public.admin_roles
        where user_id = p_actor_user_id and active and role in ('owner', 'admin')
    ) then
        raise exception 'Actor is not an active allowlisted admin.';
    end if;

    -- Attribute audit triggers accurately to the verified AAL2 administrator.
    perform set_config('request.jwt.claim.sub', p_actor_user_id::text, true);

    select * into v_target
    from public.releases
    where id = p_release_id
    for update;

    if not found then
        raise exception 'Release draft or record not found.';
    end if;

    if p_action = 'publish' then
        if v_target.state not in ('draft', 'paused') then
            raise exception 'Only draft or paused releases can be published.';
        end if;
        if coalesce(p_signing_cert_sha256, '') <> v_expected_cert then
            raise exception 'Signing certificate fingerprint does not match official Private Vault key.';
        end if;
        if p_apk_sha256 is null or p_apk_sha256 !~ '^[0-9a-f]{64}$' then
            raise exception 'Verified 64-character lowercase hex APK SHA-256 is required.';
        end if;
        if p_apk_size_bytes is null or p_apk_size_bytes not between 1 and 262144000 then
            raise exception 'Verified APK byte size is out of bounds.';
        end if;

        select coalesce(max(version_code), 0) into v_max_prior_code
        from public.releases
        where channel = v_target.channel
          and id <> v_target.id
          and state in ('published', 'paused');

        if v_target.version_code < v_max_prior_code then
            raise exception 'Cannot publish a release with lower version_code than an active/paused channel release.';
        end if;

        update public.releases
        set state = 'withdrawn'
        where channel = v_target.channel
          and state = 'published'
          and id <> v_target.id;

        update public.releases
        set state = 'published',
            apk_sha256 = p_apk_sha256,
            apk_size_bytes = p_apk_size_bytes,
            signing_cert_sha256 = v_expected_cert,
            verified_at = now(),
            priority = coalesce(p_priority, v_target.priority),
            rollout_percent = coalesce(p_rollout_percent, v_target.rollout_percent),
            available_from = coalesce(p_available_from, v_target.available_from),
            available_until = p_available_until
        where id = v_target.id
        returning * into v_updated;

    elsif p_action = 'pause' then
        if v_target.state <> 'published' then
            raise exception 'Only a published release can be paused.';
        end if;
        update public.releases
        set state = 'paused'
        where id = v_target.id
        returning * into v_updated;

    elsif p_action = 'resume' then
        if v_target.state <> 'paused' then
            raise exception 'Only a paused release can be resumed.';
        end if;
        if v_target.apk_sha256 is null or v_target.apk_size_bytes is null or v_target.verified_at is null
           or v_target.signing_cert_sha256 <> v_expected_cert then
            raise exception 'Cannot resume a release lacking verified artifact metadata.';
        end if;

        update public.releases
        set state = 'withdrawn'
        where channel = v_target.channel
          and state = 'published'
          and id <> v_target.id;

        update public.releases
        set state = 'published'
        where id = v_target.id
        returning * into v_updated;

    elsif p_action = 'withdraw' then
        if v_target.state not in ('published', 'paused') then
            raise exception 'Only published or paused releases can be withdrawn.';
        end if;
        update public.releases
        set state = 'withdrawn',
            rollout_percent = 0
        where id = v_target.id
        returning * into v_updated;

    elsif p_action = 'update_rollout' then
        if v_target.state not in ('published', 'paused') then
            raise exception 'Use direct draft update for drafts; update_rollout applies to published/paused releases.';
        end if;
        update public.releases
        set rollout_percent = coalesce(p_rollout_percent, v_target.rollout_percent),
            priority = coalesce(p_priority, v_target.priority),
            available_from = coalesce(p_available_from, v_target.available_from),
            available_until = coalesce(p_available_until, v_target.available_until)
        where id = v_target.id
        returning * into v_updated;
    else
        raise exception 'Unsupported release control action: %', p_action;
    end if;

    return to_jsonb(v_updated);
end;
$$;

revoke all on function pv_private.admin_transition_release(uuid, text, uuid, text, bigint, text, text, smallint, timestamptz, timestamptz)
    from public, anon, authenticated;
grant execute on function pv_private.admin_transition_release(uuid, text, uuid, text, bigint, text, text, smallint, timestamptz, timestamptz)
    to service_role;

-- Internal hashed-IP rate limit bucket for optional consent-based feedback submission.
create table if not exists pv_private.feedback_rate_limits (
    bucket_key text primary key,
    window_started_at timestamptz not null default now(),
    submission_count integer not null default 0 check (submission_count >= 0)
);
revoke all on table pv_private.feedback_rate_limits from public, anon, authenticated;

create or replace function pv_private.submit_verified_feedback(
    p_category text,
    p_message text,
    p_contact text,
    p_app_version text,
    p_consent boolean,
    p_rate_bucket text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_enabled boolean;
    v_now timestamptz := now();
    v_window_start timestamptz;
    v_count integer;
    v_id uuid;
    v_trimmed text;
    v_clean_contact text;
    v_clean_version text;
begin
    select coalesce(boolean_value, false) into v_enabled
    from public.feature_flags
    where flag_key = 'feedback_enabled' and is_published = true;

    if coalesce(v_enabled, false) = false then
        raise exception 'Feedback submissions are currently disabled.';
    end if;

    if p_consent is distinct from true then
        raise exception 'Explicit user consent is required.';
    end if;
    if p_category not in ('bug', 'suggestion', 'other') then
        raise exception 'Invalid feedback category.';
    end if;

    v_trimmed := btrim(coalesce(p_message, ''));
    if char_length(v_trimmed) < 1 or char_length(v_trimmed) > 3000 then
        raise exception 'Feedback message must be 1..3000 characters.';
    end if;

    v_clean_contact := nullif(btrim(coalesce(p_contact, '')), '');
    if v_clean_contact is not null and char_length(v_clean_contact) > 254 then
        raise exception 'Contact length exceeds 254 characters.';
    end if;

    v_clean_version := nullif(btrim(coalesce(p_app_version, '')), '');
    if v_clean_version is not null and char_length(v_clean_version) > 30 then
        raise exception 'App version length exceeds 30 characters.';
    end if;

    insert into pv_private.feedback_rate_limits(bucket_key, window_started_at, submission_count)
    values (p_rate_bucket, v_now, 1)
    on conflict (bucket_key) do update
    set window_started_at = case
            when pv_private.feedback_rate_limits.window_started_at < v_now - interval '1 hour' then v_now
            else pv_private.feedback_rate_limits.window_started_at
        end,
        submission_count = case
            when pv_private.feedback_rate_limits.window_started_at < v_now - interval '1 hour' then 1
            else pv_private.feedback_rate_limits.submission_count + 1
        end
    returning window_started_at, submission_count into v_window_start, v_count;

    if v_count > 5 then
        raise exception 'Rate limit exceeded. Please try again later.';
    end if;

    -- Probabilistic stale rate bucket cleanup
    delete from pv_private.feedback_rate_limits where window_started_at < v_now - interval '24 hours';

    insert into public.feedback(category, message, contact, app_version, consent, status)
    values (p_category, v_trimmed, v_clean_contact, v_clean_version, true, 'open')
    returning id into v_id;

    return v_id;
end;
$$;

revoke all on function pv_private.submit_verified_feedback(text, text, text, text, boolean, text)
    from public, anon, authenticated;
grant execute on function pv_private.submit_verified_feedback(text, text, text, text, boolean, text)
    to service_role;

commit;
