import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import type { ControlCenterApi } from "./lib/controlCenterApi";
import type { AdminSecurityStatus, DashboardBundle } from "./types/controlCenter";

function makeMockApi(
  security: AdminSecurityStatus,
  bundle: DashboardBundle,
  releaseControlEnabled = false,
): ControlCenterApi {
  return {
    getSecurityStatus: vi.fn().mockResolvedValue(security),
    signInWithPassword: vi.fn().mockResolvedValue(undefined),
    signOut: vi.fn().mockResolvedValue(undefined),
    enrollTotp: vi.fn().mockResolvedValue({ factorId: "f1", uri: "otpauth://totp/PV:owner", secret: "JBSWY3DPEHPK3PXP" }),
    verifyTotp: vi.fn().mockResolvedValue(undefined),
    fetchDashboardBundle: vi.fn().mockResolvedValue(bundle),
    fetchPolicyStatus: vi.fn().mockResolvedValue(bundle.policyState),
    createReleaseDraft: vi.fn().mockResolvedValue(undefined),
    updateReleaseDraft: vi.fn().mockResolvedValue(undefined),
    deleteReleaseDraft: vi.fn().mockResolvedValue(undefined),
    transitionReleaseServerSide: vi.fn().mockResolvedValue(bundle.releases[0]),
    createAnnouncement: vi.fn().mockResolvedValue(undefined),
    updateAnnouncement: vi.fn().mockResolvedValue(undefined),
    setAnnouncementState: vi.fn().mockResolvedValue(undefined),
    deleteAnnouncement: vi.fn().mockResolvedValue(undefined),
    updateFeatureFlag: vi.fn().mockResolvedValue(undefined),
    updateFeedbackStatus: vi.fn().mockResolvedValue(undefined),
    deleteFeedback: vi.fn().mockResolvedValue(undefined),
    isReleaseControlEnabled: () => releaseControlEnabled,
  };
}

const SAMPLE_BUNDLE: DashboardBundle = {
  policyState: { singleton: true, revision: 4, updated_at: "2026-11-26T12:00:00Z" },
  releases: [
    {
      id: "rel-draft-1",
      version_name: "2.1.0",
      version_code: 6,
      channel: "stable",
      priority: "recommended",
      state: "draft",
      rollout_percent: 20,
      release_notes: "Staged rollout support",
      manifest_url: "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.0/update.json",
      apk_sha256: null,
      apk_size_bytes: null,
      signing_cert_sha256: "3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770",
      verified_at: null,
      available_from: "2026-11-26T12:00:00Z",
      available_until: null,
      created_at: "2026-11-26T12:00:00Z",
      updated_at: "2026-11-26T12:00:00Z",
    },
  ],
  announcements: [
    {
      id: "ann-1",
      title: "Scheduled Check <script>alert(1)</script>",
      message: "Plain text body only",
      presentation: "banner",
      target_channel: "all",
      min_version_code: 6,
      max_version_code: null,
      state: "draft",
      repeat_mode: "once",
      starts_at: "2026-11-26T12:00:00Z",
      ends_at: null,
      created_at: "2026-11-26T12:00:00Z",
      updated_at: "2026-11-26T12:00:00Z",
    },
  ],
  featureFlags: [
    { flag_key: "beta_enrollment_enabled", boolean_value: false, integer_value: null, is_published: true, updated_at: "2026-11-26T12:00:00Z" },
    { flag_key: "feedback_enabled", boolean_value: false, integer_value: null, is_published: true, updated_at: "2026-11-26T12:00:00Z" },
    { flag_key: "online_maintenance", boolean_value: false, integer_value: null, is_published: true, updated_at: "2026-11-26T12:00:00Z" },
    { flag_key: "update_check_interval_hours", boolean_value: null, integer_value: 12, is_published: true, updated_at: "2026-11-26T12:00:00Z" },
  ],
  feedback: [
    {
      id: "fb-1",
      category: "suggestion",
      message: "Private suggestion item",
      contact: null,
      app_version: "2.1.0",
      consent: true,
      status: "open",
      created_at: "2026-11-26T12:00:00Z",
      updated_at: "2026-11-26T12:00:00Z",
    },
  ],
  auditLog: [],
};

describe("Private Vault Admin Control Center", () => {
  it("blocks protected admin views when unauthenticated and offers no public sign-up", async () => {
    const api = makeMockApi(
      {
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
      },
      SAMPLE_BUNDLE,
    );

    render(<App api={api} />);
    await waitFor(() => expect(screen.getByText(/Admin Authentication & MFA Security/i)).toBeInTheDocument());
    expect(screen.queryByText(/Zero-Telemetry Policy/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /sign up/i })).not.toBeInTheDocument();
  });

  it("forces AAL2 MFA challenge even if authenticated user is AAL1", async () => {
    const api = makeMockApi(
      {
        authenticated: true,
        userId: "user-1",
        email: "owner@example.test",
        currentLevel: "aal1",
        nextLevel: "aal2",
        hasVerifiedTotpFactor: true,
        verifiedTotpFactorId: "factor-totp-1",
        adminRole: { user_id: "user-1", role: "owner", active: true, created_at: "2026-11-26T12:00:00Z" },
        isAuthorizedAdminAal2: false,
        sessionExpiresAtEpochSec: 1900000000,
      },
      SAMPLE_BUNDLE,
    );

    render(<App api={api} />);
    await waitFor(() =>
      expect(screen.getByText(/Complete Two-Factor Verification \(AAL2 Required\)/i)).toBeInTheDocument(),
    );
    expect(api.fetchDashboardBundle).not.toHaveBeenCalled();
  });

  it("renders dashboard views for AAL2 admin and keeps Publish disabled when endpoint is pending", async () => {
    const user = userEvent.setup();
    const api = makeMockApi(
      {
        authenticated: true,
        userId: "user-1",
        email: "owner@example.test",
        currentLevel: "aal2",
        nextLevel: "aal2",
        hasVerifiedTotpFactor: true,
        verifiedTotpFactorId: "factor-totp-1",
        adminRole: { user_id: "user-1", role: "owner", active: true, created_at: "2026-11-26T12:00:00Z" },
        isAuthorizedAdminAal2: true,
        sessionExpiresAtEpochSec: 1900000000,
      },
      SAMPLE_BUNDLE,
      false,
    );

    render(<App api={api} />);
    await waitFor(() => expect(screen.getByText(/Control Center Overview/i)).toBeInTheDocument());
    expect(screen.getByText(/OWNER · AAL2/i)).toBeInTheDocument();

    // Navigate to Releases and confirm Publish button is disabled while server endpoint is pending
    await user.click(screen.getByRole("button", { name: /^Releases$/i }));
    const publishBtn = screen.getByRole("button", { name: /^Publish$/i });
    expect(publishBtn).toBeDisabled();

    // Navigate to Announcements and confirm escaped plain-text rendering
    await user.click(screen.getByRole("button", { name: /^Announcements$/i }));
    expect(screen.getByText(/Scheduled Check <script>alert\(1\)<\/script>/)).toBeInTheDocument();

    // Navigate to Feature Flags and confirm safe defaults are shown
    await user.click(screen.getByRole("button", { name: /^Feature Flags$/i }));
    expect(screen.getByText("update_check_interval_hours")).toBeInTheDocument();

    // Navigate to Feedback and confirm read/triage view renders cleanly
    await user.click(screen.getByRole("button", { name: /^Feedback$/i }));
    expect(screen.getByText("Private suggestion item")).toBeInTheDocument();
  });

  it("supports creating drafts, editing announcements, and handling errors gracefully", async () => {
    const user = userEvent.setup();
    const api = makeMockApi(
      {
        authenticated: true,
        userId: "user-1",
        email: "owner@example.test",
        currentLevel: "aal2",
        nextLevel: "aal2",
        hasVerifiedTotpFactor: true,
        verifiedTotpFactorId: "factor-totp-1",
        adminRole: { user_id: "user-1", role: "owner", active: true, created_at: "2026-11-26T12:00:00Z" },
        isAuthorizedAdminAal2: true,
        sessionExpiresAtEpochSec: 1900000000,
      },
      SAMPLE_BUNDLE,
      false,
    );
    (api.createReleaseDraft as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error("Unable to connect. Please try again."),
    );

    render(<App api={api} />);
    await waitFor(() => expect(screen.getByText(/Control Center Overview/i)).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /^Releases$/i }));
    await user.click(screen.getByRole("button", { name: /^Create Draft$/i }));
    await waitFor(() => expect(screen.getByText("Unable to connect. Please try again.")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /^Announcements$/i }));
    await user.click(screen.getByRole("button", { name: /^Edit$/i }));
    expect(screen.getByDisplayValue("Plain text body only")).toBeInTheDocument();
  });
});
