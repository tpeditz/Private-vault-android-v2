-- Private Vault V2.1.0 Corrective Migration (Reviewable Only — Do Not Apply Live Without Owner Review)
-- Fixes PostgREST schema exposure mismatch between Edge Functions (`public` RPC schema) and private helpers (`pv_private`),
-- ensures complete auth.uid() attribution in admin_audit_log, and preserves scheduled `available_until` unless explicitly cleared.
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
    p_available_until timestamptz default null,
    p_clear_available_until boolean default false
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

    -- Attribute audit triggers reliably across Postgres/PostgREST versions.
    perform set_config('request.jwt.claim.sub', p_actor_user_id::text, true);
    perform set_config(
        'request.jwt.claims',
        jsonb_build_object('sub', p_actor_user_id::text, 'role', 'authenticated', 'aal', 'aal2')::text,
        true
    );

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
            available_until = case
                when p_clear_available_until then null
                else coalesce(p_available_until, v_target.available_until)
            end
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
        if p_signing_cert_sha256 is distinct from v_expected_cert
           or p_apk_sha256 is distinct from v_target.apk_sha256
           or p_apk_size_bytes is distinct from v_target.apk_size_bytes
           or v_target.apk_sha256 is null
           or v_target.apk_size_bytes is null then
            raise exception 'Resuming requires fresh server-side artifact re-verification matching the release record.';
        end if;

        update public.releases
        set state = 'withdrawn'
        where channel = v_target.channel
          and state = 'published'
          and id <> v_target.id;

        update public.releases
        set state = 'published',
            verified_at = now()
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
            available_until = case
                when p_clear_available_until then null
                else coalesce(p_available_until, v_target.available_until)
            end
        where id = v_target.id
        returning * into v_updated;
    else
        raise exception 'Unsupported release control action: %', p_action;
    end if;

    return to_jsonb(v_updated);
end;
$$;

revoke all on function pv_private.admin_transition_release(uuid, text, uuid, text, bigint, text, text, smallint, timestamptz, timestamptz, boolean)
    from public, anon, authenticated;
grant execute on function pv_private.admin_transition_release(uuid, text, uuid, text, bigint, text, text, smallint, timestamptz, timestamptz, boolean)
    to service_role;

-- Service-role-only public RPC wrappers so PostgREST can invoke release-control and feedback RPCs
-- without exposing the private `pv_private` schema in Supabase API settings.
create or replace function public.admin_transition_release(
    p_release_id uuid,
    p_action text,
    p_actor_user_id uuid,
    p_apk_sha256 text default null,
    p_apk_size_bytes bigint default null,
    p_signing_cert_sha256 text default null,
    p_priority text default null,
    p_rollout_percent smallint default null,
    p_available_from timestamptz default null,
    p_available_until timestamptz default null,
    p_clear_available_until boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
    return pv_private.admin_transition_release(
        p_release_id := p_release_id,
        p_action := p_action,
        p_actor_user_id := p_actor_user_id,
        p_apk_sha256 := p_apk_sha256,
        p_apk_size_bytes := p_apk_size_bytes,
        p_signing_cert_sha256 := p_signing_cert_sha256,
        p_priority := p_priority,
        p_rollout_percent := p_rollout_percent,
        p_available_from := p_available_from,
        p_available_until := p_available_until,
        p_clear_available_until := p_clear_available_until
    );
end;
$$;

revoke all on function public.admin_transition_release(uuid, text, uuid, text, bigint, text, text, smallint, timestamptz, timestamptz, boolean)
    from public, anon, authenticated;
grant execute on function public.admin_transition_release(uuid, text, uuid, text, bigint, text, text, smallint, timestamptz, timestamptz, boolean)
    to service_role;

create or replace function public.submit_verified_feedback(
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
begin
    return pv_private.submit_verified_feedback(
        p_category := p_category,
        p_message := p_message,
        p_contact := p_contact,
        p_app_version := p_app_version,
        p_consent := p_consent,
        p_rate_bucket := p_rate_bucket
    );
end;
$$;

revoke all on function public.submit_verified_feedback(text, text, text, text, boolean, text)
    from public, anon, authenticated;
grant execute on function public.submit_verified_feedback(text, text, text, text, boolean, text)
    to service_role;

commit;
