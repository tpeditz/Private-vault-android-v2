import React, { useMemo, useState } from "react";
import { ConfirmDialog } from "../components/ConfirmDialog";
import type { FeedbackCategory, FeedbackRecord, FeedbackStatus } from "../types/controlCenter";

interface FeedbackViewProps {
  feedback: FeedbackRecord[];
  onUpdateStatus: (id: string, status: FeedbackStatus) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}

export const FeedbackView: React.FC<FeedbackViewProps> = ({ feedback, onUpdateStatus, onDelete }) => {
  const [statusFilter, setStatusFilter] = useState<"all" | FeedbackStatus>("all");
  const [categoryFilter, setCategoryFilter] = useState<"all" | FeedbackCategory>("all");
  const [versionFilter, setVersionFilter] = useState("");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const vQuery = versionFilter.trim().toLowerCase();
    return feedback.filter((item) => {
      if (statusFilter !== "all" && item.status !== statusFilter) return false;
      if (categoryFilter !== "all" && item.category !== categoryFilter) return false;
      if (vQuery && !(item.app_version ?? "").toLowerCase().includes(vQuery)) return false;
      return true;
    });
  }, [feedback, statusFilter, categoryFilter, versionFilter]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="panel">
        <div className="panel-header">
          <div>
            <h2>Private User Feedback</h2>
            <p className="muted" style={{ margin: "4px 0 0" }}>
              Readable only by active allowlisted AAL2 admins. Public feedback submission remains disabled until server review is complete.
            </p>
          </div>
          <span className="badge">{filtered.length} shown</span>
        </div>

        {errorMsg && <div className="notice error" style={{ marginBottom: 14 }}>{errorMsg}</div>}

        <div className="form-grid" style={{ marginBottom: 16 }}>
          <div className="field">
            <label htmlFor="fb-status-filter">Filter Status</label>
            <select
              id="fb-status-filter"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as "all" | FeedbackStatus)}
            >
              <option value="all">All statuses</option>
              <option value="open">open</option>
              <option value="reviewing">reviewing</option>
              <option value="resolved">resolved</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="fb-cat-filter">Filter Category</label>
            <select
              id="fb-cat-filter"
              value={categoryFilter}
              onChange={(e) => setCategoryFilter(e.target.value as "all" | FeedbackCategory)}
            >
              <option value="all">All categories</option>
              <option value="bug">bug</option>
              <option value="suggestion">suggestion</option>
              <option value="other">other</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="fb-ver-filter">App Version</label>
            <input
              id="fb-ver-filter"
              value={versionFilter}
              onChange={(e) => setVersionFilter(e.target.value)}
              placeholder="e.g. 2.1.0"
            />
          </div>
        </div>

        {filtered.length === 0 ? (
          <p className="muted" style={{ marginBottom: 0 }}>
            No feedback entries match the current filters.
          </p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Date &amp; Category</th>
                  <th>Message (Escaped Plain Text)</th>
                  <th>App Version / Contact</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <div>{new Date(item.created_at).toLocaleDateString()}</div>
                      <span className="badge">{item.category}</span>
                    </td>
                    <td style={{ whiteSpace: "pre-wrap", maxWidth: 420 }}>{item.message}</td>
                    <td>
                      <div>{item.app_version ?? "—"}</div>
                      <div className="muted">{item.contact ?? "No contact"}</div>
                    </td>
                    <td>
                      <select
                        aria-label={`Status for feedback ${item.id}`}
                        value={item.status}
                        disabled={busy}
                        onChange={async (e) => {
                          setBusy(true);
                          setErrorMsg(null);
                          try {
                            await onUpdateStatus(item.id, e.target.value as FeedbackStatus);
                          } catch (err) {
                            setErrorMsg(err instanceof Error ? err.message : "Status update failed.");
                          } finally {
                            setBusy(false);
                          }
                        }}
                      >
                        <option value="open">open</option>
                        <option value="reviewing">reviewing</option>
                        <option value="resolved">resolved</option>
                      </select>
                    </td>
                    <td>
                      <button
                        type="button"
                        className="btn danger"
                        disabled={busy}
                        onClick={() => setConfirmDeleteId(item.id)}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ConfirmDialog
        open={Boolean(confirmDeleteId)}
        title="Delete Feedback Entry?"
        description="Removes this feedback item permanently for data-retention hygiene. This action is logged in admin_audit_log."
        confirmLabel="Delete Feedback"
        destructive
        busy={busy}
        onConfirm={async () => {
          if (!confirmDeleteId) return;
          setBusy(true);
          setErrorMsg(null);
          try {
            await onDelete(confirmDeleteId);
            setConfirmDeleteId(null);
          } catch (e) {
            setErrorMsg(e instanceof Error ? e.message : "Failed deleting feedback.");
            setConfirmDeleteId(null);
          } finally {
            setBusy(false);
          }
        }}
        onCancel={() => setConfirmDeleteId(null)}
      />
    </div>
  );
};
