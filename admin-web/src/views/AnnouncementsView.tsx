import React, { useState } from "react";
import { ConfirmDialog } from "../components/ConfirmDialog";
import {
  validateAnnouncementInput,
  type AnnouncementInput,
  type AnnouncementPresentation,
  type AnnouncementRecord,
  type AnnouncementRepeatMode,
  type AnnouncementState,
  type AnnouncementTargetChannel,
} from "../types/controlCenter";

interface AnnouncementsViewProps {
  announcements: AnnouncementRecord[];
  onCreate: (input: AnnouncementInput) => Promise<void>;
  onUpdate: (id: string, input: AnnouncementInput) => Promise<void>;
  onTransitionState: (id: string, state: AnnouncementState) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}

export const AnnouncementsView: React.FC<AnnouncementsViewProps> = ({
  announcements,
  onCreate,
  onUpdate,
  onTransitionState,
  onDelete,
}) => {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState("");
  const [presentation, setPresentation] = useState<AnnouncementPresentation>("banner");
  const [targetChannel, setTargetChannel] = useState<AnnouncementTargetChannel>("all");
  const [minVer, setMinVer] = useState("");
  const [maxVer, setMaxVer] = useState("");
  const [repeatMode, setRepeatMode] = useState<AnnouncementRepeatMode>("once");
  const [startsAt, setStartsAt] = useState(() => new Date().toISOString().slice(0, 16));
  const [endsAt, setEndsAt] = useState("");
  const [previewTarget, setPreviewTarget] = useState<{
    title: string;
    message: string;
    presentation: AnnouncementPresentation;
  } | null>(null);
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
    setTitle("");
    setMessage("");
    setPresentation("banner");
    setTargetChannel("all");
    setMinVer("");
    setMaxVer("");
    setRepeatMode("once");
    setStartsAt(new Date().toISOString().slice(0, 16));
    setEndsAt("");
  };

  const loadAnnouncementForEdit = (a: AnnouncementRecord) => {
    setEditingId(a.id);
    setTitle(a.title);
    setMessage(a.message);
    setPresentation(a.presentation);
    setTargetChannel(a.target_channel);
    setMinVer(a.min_version_code !== null ? String(a.min_version_code) : "");
    setMaxVer(a.max_version_code !== null ? String(a.max_version_code) : "");
    setRepeatMode(a.repeat_mode);
    setStartsAt(a.starts_at ? a.starts_at.slice(0, 16) : new Date().toISOString().slice(0, 16));
    setEndsAt(a.ends_at ? a.ends_at.slice(0, 16) : "");
    setErrorMsg(null);
    setSuccessMsg(null);
  };

  const handleSave = async (desiredState: AnnouncementState) => {
    setErrorMsg(null);
    setSuccessMsg(null);
    const input: AnnouncementInput = {
      title,
      message,
      presentation,
      target_channel: targetChannel,
      min_version_code: minVer.trim() ? Number(minVer) : null,
      max_version_code: maxVer.trim() ? Number(maxVer) : null,
      state: desiredState,
      repeat_mode: repeatMode,
      starts_at: startsAt ? new Date(startsAt).toISOString() : new Date().toISOString(),
      ends_at: endsAt ? new Date(endsAt).toISOString() : null,
    };
    const err = validateAnnouncementInput(input);
    if (err) {
      setErrorMsg(err);
      return;
    }
    setBusy(true);
    try {
      if (editingId) {
        await onUpdate(editingId, input);
        setSuccessMsg("Announcement updated.");
      } else {
        await onCreate(input);
        setSuccessMsg("Announcement draft created.");
      }
      resetForm();
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : "Failed to save announcement.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="panel">
        <div className="panel-header">
          <div>
            <h2>{editingId ? "Edit Announcement" : "Create Announcement"}</h2>
            <p className="muted" style={{ margin: "4px 0 0" }}>
              Announcements render strictly as escaped plain text inside Android. Never include sensitive user data.
            </p>
          </div>
          {editingId && (
            <button type="button" className="btn" onClick={resetForm}>
              Cancel Edit
            </button>
          )}
        </div>

        {errorMsg && <div className="notice error" style={{ marginBottom: 14 }}>{errorMsg}</div>}
        {successMsg && <div className="notice success" style={{ marginBottom: 14 }}>{successMsg}</div>}

        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div className="form-grid">
            <div className="field">
              <label htmlFor="ann-title">Title ({title.length}/120)</label>
              <input
                id="ann-title"
                maxLength={120}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="Planned maintenance window"
              />
            </div>
            <div className="field">
              <label htmlFor="ann-pres">Presentation</label>
              <select
                id="ann-pres"
                value={presentation}
                onChange={(e) => setPresentation(e.target.value as AnnouncementPresentation)}
              >
                <option value="banner">banner</option>
                <option value="dialog">dialog</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="ann-channel">Target Channel</label>
              <select
                id="ann-channel"
                value={targetChannel}
                onChange={(e) => setTargetChannel(e.target.value as AnnouncementTargetChannel)}
              >
                <option value="all">all</option>
                <option value="stable">stable</option>
                <option value="beta">beta</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="ann-repeat">Repeat Mode</label>
              <select
                id="ann-repeat"
                value={repeatMode}
                onChange={(e) => setRepeatMode(e.target.value as AnnouncementRepeatMode)}
              >
                <option value="once">once</option>
                <option value="repeat">repeat</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="ann-min-ver">Min Version Code (Optional)</label>
              <input
                id="ann-min-ver"
                type="number"
                min={1}
                value={minVer}
                onChange={(e) => setMinVer(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="ann-max-ver">Max Version Code (Optional)</label>
              <input
                id="ann-max-ver"
                type="number"
                min={1}
                value={maxVer}
                onChange={(e) => setMaxVer(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="ann-starts">Starts At</label>
              <input
                id="ann-starts"
                type="datetime-local"
                value={startsAt}
                onChange={(e) => setStartsAt(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="ann-ends">Ends At (Optional)</label>
              <input
                id="ann-ends"
                type="datetime-local"
                value={endsAt}
                onChange={(e) => setEndsAt(e.target.value)}
              />
            </div>
          </div>

          <div className="field">
            <label htmlFor="ann-msg">Plain-Text Message ({message.length}/2000)</label>
            <textarea
              id="ann-msg"
              rows={3}
              maxLength={2000}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="Write notice text in plain text (HTML/Markdown scripts are never executed)..."
            />
          </div>

          <div className="row-actions">
            <button
              type="button"
              className="btn"
              onClick={() => setPreviewTarget({ title: title || "(Untitled)", message, presentation })}
            >
              Preview Plain Text
            </button>
            <button type="button" className="btn primary" disabled={busy} onClick={() => handleSave("draft")}>
              {editingId ? "Save Changes (Draft)" : "Save as Draft"}
            </button>
          </div>
        </div>
      </div>

      <div className="panel">
        <div className="panel-header">
          <h3>Announcements</h3>
          <span className="badge">{announcements.length} total</span>
        </div>

        {announcements.length === 0 ? (
          <p className="muted" style={{ marginBottom: 0 }}>
            No announcements configured.
          </p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Title</th>
                  <th>Targeting</th>
                  <th>State</th>
                  <th>Schedule</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {announcements.map((a) => (
                  <tr key={a.id}>
                    <td>
                      <strong>{a.title}</strong>
                      <div className="muted">{a.message.slice(0, 80)}{a.message.length > 80 ? "..." : ""}</div>
                    </td>
                    <td>
                      <div>{a.presentation} · {a.target_channel}</div>
                      <div className="muted">
                        v{a.min_version_code ?? "1"}–{a.max_version_code ?? "∞"} · {a.repeat_mode}
                      </div>
                    </td>
                    <td>
                      <span
                        className={`badge ${
                          a.state === "published" ? "success" : a.state === "archived" ? "warning" : ""
                        }`}
                      >
                        {a.state}
                      </span>
                    </td>
                    <td>
                      <div>{new Date(a.starts_at).toLocaleDateString()}</div>
                      <div className="muted">{a.ends_at ? `Until ${new Date(a.ends_at).toLocaleDateString()}` : "No end"}</div>
                    </td>
                    <td>
                      <div className="row-actions">
                        <button
                          type="button"
                          className="btn"
                          onClick={() =>
                            setPreviewTarget({ title: a.title, message: a.message, presentation: a.presentation })
                          }
                        >
                          Preview
                        </button>
                        <button type="button" className="btn" onClick={() => loadAnnouncementForEdit(a)}>
                          Edit
                        </button>
                        {a.state !== "published" && (
                          <button
                            type="button"
                            className="btn primary"
                            onClick={() =>
                              setPendingConfirm({
                                title: `Publish "${a.title}"?`,
                                description: "Eligible Android clients will display this plain-text announcement after policy refresh.",
                                confirmLabel: "Publish",
                                run: () => onTransitionState(a.id, "published"),
                              })
                            }
                          >
                            Publish
                          </button>
                        )}
                        {a.state === "published" && (
                          <button
                            type="button"
                            className="btn"
                            onClick={() =>
                              setPendingConfirm({
                                title: `Archive "${a.title}"?`,
                                description: "Stops showing this announcement to public clients.",
                                confirmLabel: "Archive",
                                run: () => onTransitionState(a.id, "archived"),
                              })
                            }
                          >
                            Archive
                          </button>
                        )}
                        {a.state !== "published" && (
                          <button
                            type="button"
                            className="btn danger"
                            onClick={() =>
                              setPendingConfirm({
                                title: `Delete "${a.title}"?`,
                                description: "Permanently deletes this draft/archived announcement.",
                                confirmLabel: "Delete",
                                destructive: true,
                                run: () => onDelete(a.id),
                              })
                            }
                          >
                            Delete
                          </button>
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

      {previewTarget && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="ann-preview-title">
          <div className="modal-box">
            <span className="badge" style={{ marginBottom: 8 }}>{previewTarget.presentation.toUpperCase()} PREVIEW</span>
            <h3 id="ann-preview-title" style={{ marginBottom: 8 }}>{previewTarget.title}</h3>
            <div className="notice" style={{ whiteSpace: "pre-wrap", marginBottom: 16 }}>
              {previewTarget.message || "(Empty message)"}
            </div>
            <div className="row-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn" onClick={() => setPreviewTarget(null)}>
                Close Preview
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
        onConfirm={async () => {
          if (!pendingConfirm) return;
          setBusy(true);
          try {
            await pendingConfirm.run();
            setPendingConfirm(null);
            setSuccessMsg("Announcement updated.");
          } catch (e) {
            setErrorMsg(e instanceof Error ? e.message : "Action failed.");
            setPendingConfirm(null);
          } finally {
            setBusy(false);
          }
        }}
        onCancel={() => setPendingConfirm(null)}
      />
    </div>
  );
};
