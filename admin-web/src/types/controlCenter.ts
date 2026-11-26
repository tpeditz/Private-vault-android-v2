export type AdminRoleName = "owner" | "admin";
export type ReleaseChannel = "stable" | "beta";
export type ReleasePriority = "optional" | "recommended" | "critical";
export type ReleaseState = "draft" | "published" | "paused" | "withdrawn";
export type AnnouncementPresentation = "banner" | "dialog";
export type AnnouncementTargetChannel = "all" | "stable" | "beta";
export type AnnouncementState = "draft" | "published" | "archived";
export type AnnouncementRepeatMode = "once" | "repeat";
export type FeatureFlagKey =
  | "beta_enrollment_enabled"
  | "feedback_enabled"
  | "online_maintenance"
  | "update_check_interval_hours";
export type FeedbackCategory = "bug" | "suggestion" | "other";
export type FeedbackStatus = "open" | "reviewing" | "resolved";

export interface AdminRoleRecord {
  user_id: string;
  role: AdminRoleName;
  active: boolean;
  created_at: string;
}

export interface ReleaseRecord {
  id: string;
  version_name: string;
  version_code: number;
  channel: ReleaseChannel;
  priority: ReleasePriority;
  state: ReleaseState;
  rollout_percent: number;
  release_notes: string;
  manifest_url: string;
  apk_sha256: string | null;
  apk_size_bytes: number | null;
  signing_cert_sha256: string;
  verified_at: string | null;
  available_from: string;
  available_until: string | null;
  created_at: string;
  updated_at: string;
}

export interface ReleaseDraftInput {
  version_name: string;
  version_code: number;
  channel: ReleaseChannel;
  priority: ReleasePriority;
  rollout_percent: number;
  release_notes: string;
  available_from: string;
  available_until: string | null;
}

export interface AnnouncementRecord {
  id: string;
  title: string;
  message: string;
  presentation: AnnouncementPresentation;
  target_channel: AnnouncementTargetChannel;
  min_version_code: number | null;
  max_version_code: number | null;
  state: AnnouncementState;
  repeat_mode: AnnouncementRepeatMode;
  starts_at: string;
  ends_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface AnnouncementInput {
  title: string;
  message: string;
  presentation: AnnouncementPresentation;
  target_channel: AnnouncementTargetChannel;
  min_version_code: number | null;
  max_version_code: number | null;
  state: AnnouncementState;
  repeat_mode: AnnouncementRepeatMode;
  starts_at: string;
  ends_at: string | null;
}

export interface FeatureFlagRecord {
  flag_key: FeatureFlagKey;
  boolean_value: boolean | null;
  integer_value: number | null;
  is_published: boolean;
  updated_at: string;
}

export interface FeedbackRecord {
  id: string;
  category: FeedbackCategory;
  message: string;
  contact: string | null;
  app_version: string | null;
  consent: boolean;
  status: FeedbackStatus;
  created_at: string;
  updated_at: string;
}

export interface AuditLogRecord {
  id: string;
  occurred_at: string;
  actor_user_id: string | null;
  event: "INSERT" | "UPDATE" | "DELETE";
  entity: "admin_roles" | "releases" | "announcements" | "feature_flags" | "feedback";
  entity_key: string;
  before_state: Record<string, unknown> | null;
  after_state: Record<string, unknown> | null;
}

export interface ClientPolicyState {
  singleton: boolean;
  revision: number;
  updated_at: string;
}

export interface AdminSecurityStatus {
  authenticated: boolean;
  userId: string | null;
  email: string | null;
  currentLevel: "aal1" | "aal2" | null;
  nextLevel: "aal1" | "aal2" | null;
  hasVerifiedTotpFactor: boolean;
  verifiedTotpFactorId: string | null;
  adminRole: AdminRoleRecord | null;
  isAuthorizedAdminAal2: boolean;
  sessionExpiresAtEpochSec: number | null;
}

export interface DashboardBundle {
  policyState: ClientPolicyState | null;
  releases: ReleaseRecord[];
  announcements: AnnouncementRecord[];
  featureFlags: FeatureFlagRecord[];
  feedback: FeedbackRecord[];
  auditLog: AuditLogRecord[];
}

export const SAFE_FLAG_DEFAULTS: Record<FeatureFlagKey, string> = {
  beta_enrollment_enabled: "false",
  feedback_enabled: "false",
  online_maintenance: "false",
  update_check_interval_hours: "12 hours (bounds 6–72h)",
};

export function validateReleaseDraftInput(input: ReleaseDraftInput): string | null {
  if (!/^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$/.test(input.version_name.trim())) {
    return "Version name must be X.Y.Z numeric segments (e.g. 2.1.0).";
  }
  if (!Number.isInteger(input.version_code) || input.version_code < 1) {
    return "Version code must be a positive integer.";
  }
  if (!Number.isInteger(input.rollout_percent) || input.rollout_percent < 0 || input.rollout_percent > 100) {
    return "Rollout percentage must be between 0 and 100.";
  }
  if (input.release_notes.length > 4000) {
    return "Release notes must not exceed 4000 characters.";
  }
  if (input.available_until && new Date(input.available_until) <= new Date(input.available_from)) {
    return "End schedule must be after start schedule.";
  }
  return null;
}

export function validateAnnouncementInput(input: AnnouncementInput): string | null {
  const titleLen = input.title.trim().length;
  const msgLen = input.message.trim().length;
  if (titleLen < 1 || titleLen > 120) {
    return "Announcement title must be 1 to 120 characters.";
  }
  if (msgLen < 1 || msgLen > 2000) {
    return "Announcement message must be 1 to 2000 characters.";
  }
  if (input.min_version_code !== null && input.min_version_code < 1) {
    return "Minimum version code must be positive.";
  }
  if (input.max_version_code !== null && input.max_version_code < 1) {
    return "Maximum version code must be positive.";
  }
  if (
    input.min_version_code !== null &&
    input.max_version_code !== null &&
    input.max_version_code < input.min_version_code
  ) {
    return "Maximum version code must be >= minimum version code.";
  }
  if (input.ends_at && new Date(input.ends_at) <= new Date(input.starts_at)) {
    return "End time must be after start time.";
  }
  return null;
}
