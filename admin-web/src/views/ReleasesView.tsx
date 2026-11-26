import React, { useState } from "react";
import { ConfirmDialog } from "../components/ConfirmDialog";
import {
  validateReleaseDraftInput,
  type ReleaseChannel,
  type ReleaseDraftInput,
  type ReleasePriority,
  type ReleaseRecord,
} from "../types/controlCenter";

interface ReleasesViewProps {
  releases: ReleaseRecord[];
  releaseControlEnabled: boolean;
  onCreateDraft: (input: ReleaseDraftInput) => Promise<void>;
  onUpdateDraft: (id: string, input: ReleaseDraftInput) => Promise<void>;
  onDeleteDraft: (id: string) => Promise<void>;
  onServerTransition: (params: {
    releaseId: string;
    action: "publish" | "pause" | "resume" | "withdraw" | "update_rollout";
    priority?: ReleasePriority;
    rolloutPercent?: number;
  }) => Promise<void>;
}

function toLocalInputValue(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const ReleasesView: React.FC<ReleasesViewProps> = ({
  releases,
  releaseControlEnabled,
  onCreateDraft,
  onUpdateDraft,
  onDeleteDraft,
  onServerTransition,
}) => {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [versionName, setVersionName] = useState("2.1.0");
  const [versionCode, setVersionCode] = useState("6");
  const [channel, setChannel] = useState<ReleaseChannel>("stable");
  const [priority, setPriority] = useState<ReleasePriority>("recommended");
  const [rolloutPercent, setRolloutPercent] = useState("10");
  const [releaseNotes, setReleaseNotes] = useState("");
  const [availableFrom, setAvailableFrom] = useState(() => toLocalInputValue(new Date().toISOString()));
  const [availableUntil, setAvailableUntil] = useState("");
  const [previewRelease, setPreviewRelease] = useState<ReleaseRecord | null>(null);
  const [rolloutEditTarget, setRolloutEditTarget] = useState<ReleaseRecord | null>(null);
  const [rolloutEditValue, setRolloutEditValue] = useState("100");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [pendingConfirm, setPendingConfirm] = useState<{
    title: string;
    description: string;
    confirmLabel: string;
    destructive?: boolean;
    run: () => Promise<void>;
  } | null>(null);

  const resetForm = () => {
    setEditingId(null);
    setVersionName("2.1.0");
    setVersionCode("6");
    setChannel("stable");
    setPriority("recommended");
    setRolloutPercent("10");
    setReleaseNotes("");
    setAvailableFrom(toLocalInputValue(new Date().toISOString()));
    setAvailableUntil("");
  };

  const loadDraftIntoForm = (rel: ReleaseRecord) => {
    setEditingId(rel.id);
    setVersionName(rel.version_name);
    setVersionCode(String(rel.version_code));
    setChannel(rel.channel);
    setPriority(rel.priority);
    setRolloutPercent(String(rel.rollout_percent));
    setReleaseNotes(rel.release_notes);
    setAvailableFrom(toLocalInputValue(rel.available_from));
    setAvailableUntil(toLocalInputValue(rel.available_until));
    setErrorMsg(null);
    setSuccessMsg(null);
  };

  const handleSaveDraft = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);
    const input: ReleaseDraftInput = {
      version_name: versionName.trim(),
      version_code: Number(versionCode),
      channel,
      priority,
      rollout_percent: Number(rolloutPercent),
      release_notes: releaseNotes,
      available_from: availableFrom ? new Date(availableFrom).toISOString() : new Date().toISOString(),
      available_until: availableUntil ? new Date(availableUntil).toISOString() : null,
    };
    const validationErr = validateReleaseDraftInput(input);
    if (validationErr) {
      setErrorMsg(validationErr);
      return;
    }

    setBusy(true);
    try {
      if (editingId) {
        await onUpdateDraft(editingId, input);
        setSuccessMsg(`Updated draft v${input.version_name} (${input.channel}).`);
      } else {
        await onCreateDraft(input);
        setSuccessMsg(`Created release draft v${input.version_name} (${input.channel}).`);
      }
      resetForm();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Failed saving release draft.");
    } finally {
      setBusy(false);
    }
  };

  const runConfirmedAction = async () => {
    if (!pendingConfirm) return;
    setBusy(true);
    setErrorMsg(null);
    setSuccessMsg(null);
    try {
      await pendingConfirm.run();
      setPendingConfirm(null);
      setSuccessMsg("Operation completed.");
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Release operation failed.");
      setPendingConfirm(null);
    } finally {
      setBusy(false);
    }
  };

  const statusBadgeClass = (state: ReleaseRecord["state"]) => {
    if (state === "published") return "badge success";
    if (state === "paused") return "badge warning";
    if (state === "withdrawn") return "badge danger";
    return "badge";
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="panel">
        <div className="panel-header">
          <div>
            <h2>{editingId ? "Edit Release Draft" : "Prepare Release Draft"}</h2>
            <p className="muted" style={{ margin: "4px 0 0" }}>
              Browser sessions can only prepare drafts. Publication requires the protected server endpoint to verify signed GitHub assets.
            </p>
          </div>
          {editingId && (
            <button type="button" className="btn" onClick={resetForm}>
              Cancel Edit
            </button>
          )}
        </div>

        {!releaseControlEnabled && (
          <div className="notice warning" style={{ marginBottom: 14 }}>
            <strong>Activation Endpoint Disabled:</strong> Activate, Pause, Resume, and Withdraw controls stay disabled until the reviewed <code>release-control</code> Edge Function is deployed and enabled via <code>VITE_RELEASE_CONTROL_ENABLED=true</code>.
          </div>
        )}
        {errorMsg && <div className="notice error" style={{ marginBottom: 14 }}>{errorMsg}</div>}
        {successMsg && <div className="notice success" style={{ marginBottom: 14 }}>{successMsg}</div>}

        <form onSubmit={handleSaveDraft} style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div className="form-grid">
            <div className="field">
              <label htmlFor="rel-version-name">Version Name</label>
              <input
                id="rel-version-name"
                value={versionName}
                onChange={(e) => setVersionName(e.target.value)}
                placeholder="2.1.0"
                required
              />
            </div>
            <div className="field">
              <label htmlFor="rel-version-code">Version Code (Monotonic)</label>
              <input
                id="rel-version-code"
                type="number"
                min={1}
                value={versionCode}
                onChange={(e) => setVersionCode(e.target.value)}
                required
              />
            </div>
            <div className="field">
              <label htmlFor="rel-channel">Channel</label>
              <select id="rel-channel" value={channel} onChange={(e) => setChannel(e.target.value as ReleaseChannel)}>
                <option value="stable">stable</option>
                <option value="beta">beta</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="rel-priority">Priority</label>
              <select id="rel-priority" value={priority} onChange={(e) => setPriority(e.target.value as ReleasePriority)}>
                <option value="optional">optional</option>
                <option value="recommended">recommended</option>
                <option value="critical">critical</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="rel-rollout">Staged Rollout (%)</label>
              <input
                id="rel-rollout"
                type="number"
                min={0}
                max={100}
                value={rolloutPercent}
                onChange={(e) => setRolloutPercent(e.target.value)}
                required
              />
            </div>
            <div className="field">
              <label htmlFor="rel-from">Available From</label>
              <input
                id="rel-from"
                type="datetime-local"
                value={availableFrom}
                onChange={(e) => setAvailableFrom(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="rel-until">Available Until (Optional)</label>
              <input
                id="rel-until"
                type="datetime-local"
                value={availableUntil}
                onChange={(e) => setAvailableUntil(e.target.value)}
              />
            </div>
          </div>

          <div className="field">
            <label htmlFor="rel-notes">Plain-Text Release Notes ({releaseNotes.length}/4000)</label>
            <textarea
              id="rel-notes"
              rows={3}
              maxLength={4000}
              value={releaseNotes}
              onChange={(e) => setReleaseNotes(e.target.value)}
              placeholder="Summarize security, OTA, and usability improvements in plain text..."
            />
          </div>

          <div className="row-actions">
            <button type="submit" className="btn primary" disabled={busy}>
              {busy ? "Saving..." : editingId ? "Update Draft" : "Create Draft"}
            </button>
          </div>
        </form>
      </div>

      <div className="panel">
        <div className="panel-header">
          <h3>Configured Releases &amp; Drafts</h3>
          <span className="badge">{releases.length} total</span>
        </div>

        {releases.length === 0 ? (
          <p className="muted" style={{ marginBottom: 0 }}>
            No release records found yet. Create a draft above when preparing a new GitHub release.
          </p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Channel / Priority</th>
                  <th>State</th>
                  <th>Rollout &amp; Schedule</th>
                  <th>Verified Manifest</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {releases.map((rel) => (
                  <tr key={rel.id}>
                    <td>
                      <strong>v{rel.version_name}</strong>
                      <div className="muted">Code {rel.version_code}</div>
                    </td>
                    <td>
                      <div>{rel.channel}</div>
                      <div className="muted">{rel.priority}</div>
                    </td>
                    <td>
                      <span className={statusBadgeClass(rel.state)}>{rel.state}</span>
                    </td>
                    <td>
                      <div>{rel.rollout_percent}% rollout</div>
                      <div className="muted">{new Date(rel.available_from).toLocaleDateString()}</div>
                    </td>
                    <td>
                      {rel.apk_sha256 ? (
                        <div>
                          <div className="badge success">Verified APK</div>
                          <div className="muted">{rel.apk_sha256.slice(0, 12)}...</div>
                        </div>
                      ) : (
                        <span className="muted">Unverified draft</span>
                      )}
                    </td>
                    <td>
                      <div className="row-actions">
                        <button type="button" className="btn" onClick={() => setPreviewRelease(rel)}>
                          Preview
                        </button>
                        {rel.state === "draft" && (
                          <>
                            <button type="button" className="btn" onClick={() => loadDraftIntoForm(rel)}>
                              Edit Draft
                            </button>
                            <button
                              type="button"
                              className="btn danger"
                              onClick={() =>
                                setPendingConfirm({
                                  title: `Delete Draft v${rel.version_name}?`,
                                  description: "This removes the unpublished draft record cleanly.",
                                  confirmLabel: "Delete Draft",
                                  destructive: true,
                                  run: () => onDeleteDraft(rel.id),
                                })
                              }
                            >
                              Delete
                            </button>
                          </>
                        )}
                        {(rel.state === "draft" || rel.state === "paused") && (
                          <button
                            type="button"
                            className="btn primary"
                            disabled={!releaseControlEnabled || busy}
                            title={
                              releaseControlEnabled
                                ? "Verify GitHub release and publish"
                                : "Deploy release-control server endpoint to enable"
                            }
                            onClick={() =>
                              setPendingConfirm({
                                title:
                                  rel.state === "paused"
                                    ? `Resume v${rel.version_name} (${rel.channel})?`
                                    : `Publish v${rel.version_name} (${rel.channel})?`,
                                description:
                                  "The release-control Edge Function will fetch the official GitHub update.json, confirm SHA-256 and signing key fingerprint, and replace any previously published release on this channel.",
                                confirmLabel: rel.state === "paused" ? "Resume Release" : "Verify & Publish",
                                run: () =>
                                  onServerTransition({
                                    releaseId: rel.id,
                                    action: rel.state === "paused" ? "resume" : "publish",
                                  }),
                              })
                            }
                          >
                            {rel.state === "paused" ? "Resume" : "Publish"}
                          </button>
                        )}
                        {(rel.state === "published" || rel.state === "paused") && (
                          <button
                            type="button"
                            className="btn"
                            disabled={!releaseControlEnabled || busy}
                            onClick={() => {
                              setRolloutEditTarget(rel);
                              setRolloutEditValue(String(rel.rollout_percent));
                            }}
                          >
                            Rollout %
                          </button>
                        )}
                        {rel.state === "published" && (
                          <>
                            <button
                              type="button"
                              className="btn"
                              disabled={!releaseControlEnabled || busy}
                              onClick={() =>
                                setPendingConfirm({
                                  title: `Pause v${rel.version_name}?`,
                                  description: "Stops new update offers once clients refresh policy.",
                                  confirmLabel: "Pause Release",
                                  run: () => onServerTransition({ releaseId: rel.id, action: "pause" }),
                                })
                              }
                            >
                              Pause
                            </button>
                            <button
                              type="button"
                              className="btn danger"
                              disabled={!releaseControlEnabled || busy}
                              onClick={() =>
                                setPendingConfirm({
                                  title: `Withdraw v${rel.version_name}?`,
                                  description: "Withdraws this release from OTA distribution.",
                                  confirmLabel: "Withdraw",
                                  destructive: true,
                                  run: () => onServerTransition({ releaseId: rel.id, action: "withdraw" }),
                                })
                              }
                            >
                              Withdraw
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {previewRelease && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="preview-rel-title">
          <div className="modal-box">
            <h3 id="preview-rel-title">Release Notes Preview — v{previewRelease.version_name}</h3>
            <p className="muted">
              Manifest URL: <code>{previewRelease.manifest_url}</code>
            </p>
            <div className="notice" style={{ whiteSpace: "pre-wrap", marginBottom: 14 }}>
              {previewRelease.release_notes || "(No release notes entered)"}
            </div>
            <div className="row-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn" onClick={() => setPreviewRelease(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={Boolean(pendingConfirm)}
        title={pendingConfirm?.title ?? ""}
        description={pendingConfirm?.description ?? ""}
        confirmLabel={pendingConfirm?.confirmLabel}
        destructive={pendingConfirm?.destructive}
        busy={busy}
        onConfirm={runConfirmedAction}
        onCancel={() => setPendingConfirm(null)}
      />

      {rolloutEditTarget && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="rollout-modal-title">
          <div className="modal-box">
            <h3 id="rollout-modal-title">Adjust Rollout — v{rolloutEditTarget.version_name}</h3>
            <p className="muted">Enter staged rollout percentage (0–100):</p>
            <div className="field" style={{ marginBottom: 16 }}>
              <label htmlFor="rollout-edit-input">Rollout Percentage</label>
              <input
                id="rollout-edit-input"
                type="number"
                min={0}
                max={100}
                value={rolloutEditValue}
                onChange={(e) => setRolloutEditValue(e.target.value)}
              />
            </div>
            <div className="row-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn" disabled={busy} onClick={() => setRolloutEditTarget(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn primary"
                disabled={busy}
                onClick={async () => {
                  const pct = Number(rolloutEditValue);
                  if (!Number.isInteger(pct) || pct < 0 || pct > 100) {
                    setErrorMsg("Rollout percentage must be an integer between 0 and 100.");
                    return;
                  }
                  setBusy(true);
                  setErrorMsg(null);
                  try {
                    await onServerTransition({
                      releaseId: rolloutEditTarget.id,
                      action: "update_rollout",
                      rolloutPercent: pct,
                    });
                    setRolloutEditTarget(null);
                    setSuccessMsg("Saved successfully.");
                  } catch (err) {
                    setErrorMsg(err instanceof Error ? err.message : "Failed updating rollout.");
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Save Rollout
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
