import React from "react";
import type { DashboardBundle } from "../types/controlCenter";

interface OverviewViewProps {
  bundle: DashboardBundle;
  releaseControlEnabled: boolean;
  onRefresh: () => void;
  refreshing: boolean;
}

export const OverviewView: React.FC<OverviewViewProps> = ({
  bundle,
  releaseControlEnabled,
  onRefresh,
  refreshing,
}) => {
  const publishedStable = bundle.releases.find((r) => r.channel === "stable" && r.state === "published");
  const publishedBeta = bundle.releases.find((r) => r.channel === "beta" && r.state === "published");
  const activeAnnouncements = bundle.announcements.filter((a) => a.state === "published").length;
  const openFeedback = bundle.feedback.filter((f) => f.status === "open").length;
  const reviewingFeedback = bundle.feedback.filter((f) => f.status === "reviewing").length;
  const resolvedFeedback = bundle.feedback.filter((f) => f.status === "resolved").length;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="panel-header" style={{ marginBottom: 0 }}>
        <div>
          <h2>Control Center Overview</h2>
          <p className="muted" style={{ margin: "4px 0 0" }}>
            Operational policy metadata only. Private Vault records, keys, and backups never leave user devices.
          </p>
        </div>
        <button type="button" className="btn" onClick={onRefresh} disabled={refreshing}>
          {refreshing ? "Refreshing..." : "Refresh Data"}
        </button>
      </div>

      <div className="notice">
        <strong>Zero-Telemetry Policy:</strong> Private Vault collects no user counts, installation telemetry, device identifiers, or crash analytics. All metrics below reflect only operational control records stored in Supabase.
      </div>

      <div className="card-grid">
        <div className="panel">
          <div className="muted">Backend Reachability</div>
          <h3 style={{ marginTop: 6 }}>
            <span className="badge success">Connected (AAL2 Verified)</span>
          </h3>
          <p className="muted" style={{ marginTop: 8 }}>
            Policy revision: <strong>#{bundle.policyState?.revision ?? "—"}</strong>
          </p>
          <p className="muted" style={{ marginBottom: 0 }}>
            Updated: {bundle.policyState?.updated_at ? new Date(bundle.policyState.updated_at).toLocaleString() : "—"}
          </p>
        </div>

        <div className="panel">
          <div className="muted">Stable Channel Release</div>
          <h3 style={{ marginTop: 6 }}>
            {publishedStable ? `v${publishedStable.version_name} (${publishedStable.rollout_percent}%)` : "None published"}
          </h3>
          <p className="muted" style={{ marginTop: 8, marginBottom: 0 }}>
            {publishedStable
              ? `Code ${publishedStable.version_code} · Priority: ${publishedStable.priority}`
              : "No active stable release in Supabase policy."}
          </p>
        </div>

        <div className="panel">
          <div className="muted">Beta Channel Release</div>
          <h3 style={{ marginTop: 6 }}>
            {publishedBeta ? `v${publishedBeta.version_name} (${publishedBeta.rollout_percent}%)` : "None published"}
          </h3>
          <p className="muted" style={{ marginTop: 8, marginBottom: 0 }}>
            {publishedBeta
              ? `Code ${publishedBeta.version_code} · Priority: ${publishedBeta.priority}`
              : "Beta opt-in channel has no active release."}
          </p>
        </div>

        <div className="panel">
          <div className="muted">Notices &amp; Private Feedback</div>
          <h3 style={{ marginTop: 6 }}>{activeAnnouncements} published notice(s)</h3>
          <p className="muted" style={{ marginTop: 8, marginBottom: 0 }}>
            Feedback: {openFeedback} open · {reviewingFeedback} reviewing · {resolvedFeedback} resolved
          </p>
        </div>
      </div>

      {!releaseControlEnabled && (
        <div className="notice warning">
          <strong>Server Release Activation Pending:</strong> Browser RLS permits creating and editing release <em>drafts</em> only. Publishing/pausing releases requires deploying and enabling the reviewed <code>release-control</code> Edge Function.
        </div>
      )}

      <div className="panel">
        <div className="panel-header">
          <h3>Recent Admin Audit Activity</h3>
          <span className="badge">{bundle.auditLog.length} recent events</span>
        </div>
        {bundle.auditLog.length === 0 ? (
          <p className="muted" style={{ marginBottom: 0 }}>
            No audit events recorded yet.
          </p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Event</th>
                  <th>Entity</th>
                  <th>Actor</th>
                  <th>Summary</th>
                </tr>
              </thead>
              <tbody>
                {bundle.auditLog.slice(0, 6).map((entry) => (
                  <tr key={entry.id}>
                    <td>{new Date(entry.occurred_at).toLocaleString()}</td>
                    <td>
                      <span className="badge">{entry.event}</span>
                    </td>
                    <td>{entry.entity}</td>
                    <td>{entry.actor_user_id ? `${entry.actor_user_id.slice(0, 8)}...` : "system"}</td>
                    <td>
                      <code>{JSON.stringify(entry.after_state ?? entry.before_state ?? {})}</code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
