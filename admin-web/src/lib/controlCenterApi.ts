import {
  RELEASE_CONTROL_ENDPOINT_ENABLED,
  SUPABASE_PUBLISHABLE_KEY,
  SUPABASE_URL,
  getSupabaseClient,
} from "./supabaseClient";
import type {
  AdminRoleRecord,
  AdminSecurityStatus,
  AnnouncementInput,
  AnnouncementRecord,
  AnnouncementState,
  AuditLogRecord,
  ClientPolicyState,
  DashboardBundle,
  FeatureFlagKey,
  FeatureFlagRecord,
  FeedbackRecord,
  FeedbackStatus,
  ReleaseDraftInput,
  ReleasePriority,
  ReleaseRecord,
} from "../types/controlCenter";

export interface ControlCenterApi {
  getSecurityStatus(): Promise<AdminSecurityStatus>;
  signInWithPassword(email: string, password: string): Promise<void>;
  signOut(): Promise<void>;
  enrollTotp(): Promise<{ factorId: string; uri: string; secret: string }>;
  verifyTotp(factorId: string, code: string): Promise<void>;
  fetchDashboardBundle(): Promise<DashboardBundle>;
  fetchPolicyStatus(): Promise<ClientPolicyState | null>;
  createReleaseDraft(input: ReleaseDraftInput): Promise<void>;
  updateReleaseDraft(id: string, input: ReleaseDraftInput): Promise<void>;
  deleteReleaseDraft(id: string): Promise<void>;
  transitionReleaseServerSide(params: {
    releaseId: string;
    action: "publish" | "pause" | "resume" | "withdraw" | "update_rollout";
    priority?: ReleasePriority;
    rolloutPercent?: number;
    availableFrom?: string;
    availableUntil?: string | null;
  }): Promise<ReleaseRecord>;
  createAnnouncement(input: AnnouncementInput): Promise<void>;
  updateAnnouncement(id: string, input: AnnouncementInput): Promise<void>;
  setAnnouncementState(id: string, state: AnnouncementState): Promise<void>;
  deleteAnnouncement(id: string): Promise<void>;
  updateFeatureFlag(
    flagKey: FeatureFlagKey,
    patch: { boolean_value?: boolean | null; integer_value?: number | null; is_published?: boolean },
  ): Promise<void>;
  updateFeedbackStatus(id: string, status: FeedbackStatus): Promise<void>;
  deleteFeedback(id: string): Promise<void>;
  isReleaseControlEnabled(): boolean;
}

function toFriendlyError(rawMessage: string | undefined, fallback: string): string {
  const msg = (rawMessage ?? "").trim();
  if (!msg) return fallback;
  const lower = msg.toLowerCase();
  if (lower.includes("failed to fetch") || lower.includes("network") || lower.includes("load failed")) {
    return "Unable to connect. Please try again.";
  }
  if (lower.includes("row-level security") || lower.includes("permission denied") || lower.includes("42501")) {
    return "Administrator MFA verification (AAL2) and active role membership are required for this action.";
  }
  if (lower.includes("invalid login credentials")) {
    return "Incorrect email or password. Please try again.";
  }
  if (lower.includes("pgrst") || lower.includes("postgres")) {
    return fallback;
  }
  return msg;
}

export class SupabaseControlCenterApi implements ControlCenterApi {
  isReleaseControlEnabled(): boolean {
    return RELEASE_CONTROL_ENDPOINT_ENABLED;
  }

  async getSecurityStatus(): Promise<AdminSecurityStatus> {
    const client = getSupabaseClient();
    const { data: sessionData, error: sessionErr } = await client.auth.getSession();
    if (sessionErr) throw new Error(sessionErr.message);

    const session = sessionData.session;
    if (!session?.user) {
      return {
        authenticated: false,
        userId: null,
        email: null,
        currentLevel: null,
        nextLevel: null,
        hasVerifiedTotpFactor: false,
        verifiedTotpFactorId: null,
        adminRole: null,
        isAuthorizedAdminAal2: false,
        sessionExpiresAtEpochSec: null,
      };
    }

    const { data: aalData, error: aalErr } = await client.auth.mfa.getAuthenticatorAssuranceLevel();
    if (aalErr) throw new Error(aalErr.message);

    const { data: factorsData } = await client.auth.mfa.listFactors();
    const verifiedTotp = factorsData?.totp?.find((f) => f.status === "verified") ?? null;

    const { data: roleRow } = await client
      .from("admin_roles")
      .select("user_id, role, active, created_at")
      .eq("user_id", session.user.id)
      .eq("active", true)
      .maybeSingle();

    const adminRole = (roleRow as AdminRoleRecord | null) ?? null;
    const currentLevel = (aalData.currentLevel as "aal1" | "aal2" | null) ?? null;
    const nextLevel = (aalData.nextLevel as "aal1" | "aal2" | null) ?? null;

    return {
      authenticated: true,
      userId: session.user.id,
      email: session.user.email ?? null,
      currentLevel,
      nextLevel,
      hasVerifiedTotpFactor: Boolean(verifiedTotp),
      verifiedTotpFactorId: verifiedTotp?.id ?? null,
      adminRole,
      isAuthorizedAdminAal2: Boolean(adminRole && adminRole.active && currentLevel === "aal2"),
      sessionExpiresAtEpochSec: session.expires_at ?? null,
    };
  }

  async signInWithPassword(email: string, password: string): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    if (error) throw new Error(toFriendlyError(error.message, "Unable to sign in. Please try again."));
  }

  async signOut(): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.auth.signOut();
    if (error) throw new Error(error.message);
  }

  async enrollTotp(): Promise<{ factorId: string; uri: string; secret: string }> {
    const client = getSupabaseClient();
    const { data, error } = await client.auth.mfa.enroll({
      factorType: "totp",
      friendlyName: `PrivateVault-Admin-${new Date().toISOString().slice(0, 10)}`,
    });
    if (error || !data) throw new Error(error?.message ?? "Could not start MFA enrollment.");
    return {
      factorId: data.id,
      uri: data.totp.uri,
      secret: data.totp.secret,
    };
  }

  async verifyTotp(factorId: string, code: string): Promise<void> {
    const client = getSupabaseClient();
    const cleaned = code.replace(/\s+/g, "").trim();
    if (!/^[0-9]{6}$/.test(cleaned)) {
      throw new Error("Enter the 6-digit code from your authenticator app.");
    }
    const { error } = await client.auth.mfa.challengeAndVerify({
      factorId,
      code: cleaned,
    });
    if (error) throw new Error(error.message);
  }

  async fetchPolicyStatus(): Promise<ClientPolicyState | null> {
    const client = getSupabaseClient();
    const { data, error } = await client
      .from("client_policy_state")
      .select("singleton, revision, updated_at")
      .maybeSingle();
    if (error) throw new Error(error.message);
    return (data as ClientPolicyState | null) ?? null;
  }

  async fetchDashboardBundle(): Promise<DashboardBundle> {
    const client = getSupabaseClient();
    const [policyRes, relRes, annRes, flagRes, fbRes, auditRes] = await Promise.all([
      client.from("client_policy_state").select("singleton, revision, updated_at").maybeSingle(),
      client
        .from("releases")
        .select("*")
        .order("version_code", { ascending: false }),
      client
        .from("announcements")
        .select("*")
        .order("created_at", { ascending: false }),
      client.from("feature_flags").select("*").order("flag_key", { ascending: true }),
      client.from("feedback").select("*").order("created_at", { ascending: false }),
      client
        .from("admin_audit_log")
        .select("*")
        .order("occurred_at", { ascending: false })
        .limit(100),
    ]);

    const firstError = [policyRes, relRes, annRes, flagRes, fbRes, auditRes].find((r) => r.error)?.error;
    if (firstError) throw new Error(toFriendlyError(firstError.message, "Unable to load operational data."));

    return {
      policyState: (policyRes.data as ClientPolicyState | null) ?? null,
      releases: (relRes.data as ReleaseRecord[] | null) ?? [],
      announcements: (annRes.data as AnnouncementRecord[] | null) ?? [],
      featureFlags: (flagRes.data as FeatureFlagRecord[] | null) ?? [],
      feedback: (fbRes.data as FeedbackRecord[] | null) ?? [],
      auditLog: (auditRes.data as AuditLogRecord[] | null) ?? [],
    };
  }

  async createReleaseDraft(input: ReleaseDraftInput): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("releases").insert({
      version_name: input.version_name.trim(),
      version_code: input.version_code,
      channel: input.channel,
      priority: input.priority,
      rollout_percent: input.rollout_percent,
      release_notes: input.release_notes.trim(),
      available_from: input.available_from,
      available_until: input.available_until || null,
    });
    if (error) throw new Error(error.message);
  }

  async updateReleaseDraft(id: string, input: ReleaseDraftInput): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client
      .from("releases")
      .update({
        version_name: input.version_name.trim(),
        version_code: input.version_code,
        channel: input.channel,
        priority: input.priority,
        rollout_percent: input.rollout_percent,
        release_notes: input.release_notes.trim(),
        available_from: input.available_from,
        available_until: input.available_until || null,
      })
      .eq("id", id)
      .eq("state", "draft");
    if (error) throw new Error(error.message);
  }

  async deleteReleaseDraft(id: string): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("releases").delete().eq("id", id).eq("state", "draft");
    if (error) throw new Error(error.message);
  }

  async transitionReleaseServerSide(params: {
    releaseId: string;
    action: "publish" | "pause" | "resume" | "withdraw" | "update_rollout";
    priority?: ReleasePriority;
    rolloutPercent?: number;
    availableFrom?: string;
    availableUntil?: string | null;
  }): Promise<ReleaseRecord> {
    if (!RELEASE_CONTROL_ENDPOINT_ENABLED) {
      throw new Error(
        "Protected release-control Edge Function is intentionally disabled until server review and deployment.",
      );
    }
    const client = getSupabaseClient();
    const { data: sessionData } = await client.auth.getSession();
    const token = sessionData.session?.access_token;
    if (!token) throw new Error("Sign in with AAL2 MFA first.");

    const resp = await fetch(`${SUPABASE_URL}/functions/v1/release-control`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        apikey: SUPABASE_PUBLISHABLE_KEY,
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        release_id: params.releaseId,
        action: params.action,
        priority: params.priority,
        rollout_percent: params.rolloutPercent,
        available_from: params.availableFrom,
        available_until: params.availableUntil,
      }),
    });
    const json = (await resp.json().catch(() => ({}))) as { release?: ReleaseRecord; error?: string };
    if (!resp.ok || !json.release) {
      throw new Error(json.error ?? `Release control failed (${resp.status}).`);
    }
    return json.release;
  }

  async createAnnouncement(input: AnnouncementInput): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("announcements").insert({
      title: input.title.trim(),
      message: input.message.trim(),
      presentation: input.presentation,
      target_channel: input.target_channel,
      min_version_code: input.min_version_code,
      max_version_code: input.max_version_code,
      state: input.state,
      repeat_mode: input.repeat_mode,
      starts_at: input.starts_at,
      ends_at: input.ends_at || null,
    });
    if (error) throw new Error(error.message);
  }

  async updateAnnouncement(id: string, input: AnnouncementInput): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client
      .from("announcements")
      .update({
        title: input.title.trim(),
        message: input.message.trim(),
        presentation: input.presentation,
        target_channel: input.target_channel,
        min_version_code: input.min_version_code,
        max_version_code: input.max_version_code,
        state: input.state,
        repeat_mode: input.repeat_mode,
        starts_at: input.starts_at,
        ends_at: input.ends_at || null,
      })
      .eq("id", id);
    if (error) throw new Error(error.message);
  }

  async setAnnouncementState(id: string, state: AnnouncementState): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("announcements").update({ state }).eq("id", id);
    if (error) throw new Error(error.message);
  }

  async deleteAnnouncement(id: string): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("announcements").delete().eq("id", id).neq("state", "published");
    if (error) throw new Error(error.message);
  }

  async updateFeatureFlag(
    flagKey: FeatureFlagKey,
    patch: { boolean_value?: boolean | null; integer_value?: number | null; is_published?: boolean },
  ): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("feature_flags").update(patch).eq("flag_key", flagKey);
    if (error) throw new Error(error.message);
  }

  async updateFeedbackStatus(id: string, status: FeedbackStatus): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("feedback").update({ status }).eq("id", id);
    if (error) throw new Error(error.message);
  }

  async deleteFeedback(id: string): Promise<void> {
    const client = getSupabaseClient();
    const { error } = await client.from("feedback").delete().eq("id", id);
    if (error) throw new Error(error.message);
  }
}

export const defaultControlCenterApi: ControlCenterApi = new SupabaseControlCenterApi();
