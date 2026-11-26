import React, { useCallback, useEffect, useState } from "react";
import {
  Activity,
  Bell,
  Flag,
  LayoutDashboard,
  Menu,
  MessageSquare,
  Moon,
  Package,
  Shield,
  Sun,
} from "lucide-react";
import { defaultControlCenterApi, type ControlCenterApi } from "./lib/controlCenterApi";
import type { AdminSecurityStatus, DashboardBundle } from "./types/controlCenter";
import { OverviewView } from "./views/OverviewView";
import { ReleasesView } from "./views/ReleasesView";
import { AnnouncementsView } from "./views/AnnouncementsView";
import { FeatureFlagsView } from "./views/FeatureFlagsView";
import { FeedbackView } from "./views/FeedbackView";
import { AuditLogView } from "./views/AuditLogView";
import { SecurityView } from "./views/SecurityView";

type TabId = "overview" | "releases" | "announcements" | "flags" | "feedback" | "audit" | "security";

interface AppProps {
  api?: ControlCenterApi;
}

const EMPTY_SECURITY: AdminSecurityStatus = {
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

export const App: React.FC<AppProps> = ({ api = defaultControlCenterApi }) => {
  const [tab, setTab] = useState<TabId>("overview");
  const [theme, setTheme] = useState<"light" | "dark">("dark");
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [security, setSecurity] = useState<AdminSecurityStatus>(EMPTY_SECURITY);
  const [bundle, setBundle] = useState<DashboardBundle>({
    policyState: null,
    releases: [],
    announcements: [],
    featureFlags: [],
    feedback: [],
    auditLog: [],
  });
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
  }, [theme]);

  const refreshAll = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const sec = await api.getSecurityStatus();
      setSecurity(sec);
      if (sec.isAuthorizedAdminAal2) {
        const data = await api.fetchDashboardBundle();
        setBundle(data);
      } else {
        const policyState = await api.fetchPolicyStatus().catch(() => null);
        setBundle((prev) => ({ ...prev, policyState }));
      }
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Failed connecting to Supabase Control Center.");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void refreshAll();
  }, [refreshAll]);

  const navItems: Array<{ id: TabId; label: string; icon: React.ReactNode }> = [
    { id: "overview", label: "Overview", icon: <LayoutDashboard size={17} /> },
    { id: "releases", label: "Releases", icon: <Package size={17} /> },
    { id: "announcements", label: "Announcements", icon: <Bell size={17} /> },
    { id: "flags", label: "Feature Flags", icon: <Flag size={17} /> },
    { id: "feedback", label: "Feedback", icon: <MessageSquare size={17} /> },
    { id: "audit", label: "Audit Log", icon: <Activity size={17} /> },
    { id: "security", label: "Admin Security", icon: <Shield size={17} /> },
  ];

  const showProtectedContent = security.isAuthorizedAdminAal2;

  return (
    <div className="shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>

      <aside className={`sidebar ${mobileMenuOpen ? "mobile-open" : ""}`} aria-label="Control Center navigation">
        <div className="brand-box">
          <div className="brand-mark">
            <Shield size={20} />
          </div>
          <div>
            <strong>Private Vault</strong>
            <div className="muted" style={{ fontSize: "0.75rem" }}>
              V2.1.0 Control Center
            </div>
          </div>
        </div>

        <nav className="nav-list">
          {navItems.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`nav-item ${tab === item.id ? "active" : ""}`}
              onClick={() => {
                setTab(item.id);
                setMobileMenuOpen(false);
              }}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </nav>
      </aside>

      <div className="main-col">
        <header className="topbar">
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <button
              type="button"
              className="btn mobile-menu-btn"
              aria-label="Toggle menu"
              onClick={() => setMobileMenuOpen((v) => !v)}
            >
              <Menu size={18} />
            </button>
            <div>
              <strong>Operational Metadata Console</strong>
              <div className="muted" style={{ fontSize: "0.78rem" }}>
                Public GitHub Pages shell · Server authorization via Supabase RLS &amp; AAL2 MFA
              </div>
            </div>
          </div>

          <div className="row-actions">
            {security.authenticated && (
              <span className={`badge ${security.isAuthorizedAdminAal2 ? "success" : "warning"}`}>
                {security.isAuthorizedAdminAal2 ? `${security.adminRole?.role.toUpperCase()} · AAL2` : "MFA Verification Needed"}
              </span>
            )}
            <button
              type="button"
              className="btn"
              aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
              onClick={() => setTheme((t) => (t === "dark" ? "light" : "dark"))}
            >
              {theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
            </button>
          </div>
        </header>

        <main id="main-content" className="content">
          {loadError && <div className="notice error">{loadError}</div>}

          {loading ? (
            <div className="panel">
              <p className="muted" style={{ margin: 0 }}>
                Verifying session assurance and loading operational metadata...
              </p>
            </div>
          ) : !showProtectedContent || tab === "security" ? (
            <SecurityView
              status={security}
              onSignIn={async (email, pwd) => {
                await api.signInWithPassword(email, pwd);
                await refreshAll();
              }}
              onSignOut={async () => {
                await api.signOut();
                setBundle({
                  policyState: bundle.policyState,
                  releases: [],
                  announcements: [],
                  featureFlags: [],
                  feedback: [],
                  auditLog: [],
                });
                await refreshAll();
              }}
              onEnrollTotp={() => api.enrollTotp()}
              onVerifyTotp={async (factorId, code) => {
                await api.verifyTotp(factorId, code);
                await refreshAll();
              }}
            />
          ) : (
            <>
              {tab === "overview" && (
                <OverviewView
                  bundle={bundle}
                  releaseControlEnabled={api.isReleaseControlEnabled()}
                  onRefresh={refreshAll}
                  refreshing={loading}
                />
              )}
              {tab === "releases" && (
                <ReleasesView
                  releases={bundle.releases}
                  releaseControlEnabled={api.isReleaseControlEnabled()}
                  onCreateDraft={async (input) => {
                    await api.createReleaseDraft(input);
                    await refreshAll();
                  }}
                  onUpdateDraft={async (id, input) => {
                    await api.updateReleaseDraft(id, input);
                    await refreshAll();
                  }}
                  onDeleteDraft={async (id) => {
                    await api.deleteReleaseDraft(id);
                    await refreshAll();
                  }}
                  onServerTransition={async (params) => {
                    await api.transitionReleaseServerSide(params);
                    await refreshAll();
                  }}
                />
              )}
              {tab === "announcements" && (
                <AnnouncementsView
                  announcements={bundle.announcements}
                  onCreate={async (input) => {
                    await api.createAnnouncement(input);
                    await refreshAll();
                  }}
                  onUpdate={async (id, input) => {
                    await api.updateAnnouncement(id, input);
                    await refreshAll();
                  }}
                  onTransitionState={async (id, state) => {
                    await api.setAnnouncementState(id, state);
                    await refreshAll();
                  }}
                  onDelete={async (id) => {
                    await api.deleteAnnouncement(id);
                    await refreshAll();
                  }}
                />
              )}
              {tab === "flags" && (
                <FeatureFlagsView
                  flags={bundle.featureFlags}
                  onUpdateFlag={async (key, patch) => {
                    await api.updateFeatureFlag(key, patch);
                    await refreshAll();
                  }}
                />
              )}
              {tab === "feedback" && (
                <FeedbackView
                  feedback={bundle.feedback}
                  onUpdateStatus={async (id, status) => {
                    await api.updateFeedbackStatus(id, status);
                    await refreshAll();
                  }}
                  onDelete={async (id) => {
                    await api.deleteFeedback(id);
                    await refreshAll();
                  }}
                />
              )}
              {tab === "audit" && <AuditLogView logs={bundle.auditLog} />}
            </>
          )}
        </main>
      </div>
    </div>
  );
};
