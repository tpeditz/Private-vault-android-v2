import React, { useState } from "react";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { SAFE_FLAG_DEFAULTS, type FeatureFlagKey, type FeatureFlagRecord } from "../types/controlCenter";

interface FeatureFlagsViewProps {
  flags: FeatureFlagRecord[];
  onUpdateFlag: (
    flagKey: FeatureFlagKey,
    patch: { boolean_value?: boolean | null; integer_value?: number | null; is_published?: boolean },
  ) => Promise<void>;
}

const FLAG_DESCRIPTIONS: Record<FeatureFlagKey, string> = {
  beta_enrollment_enabled:
    "Allows users who explicitly opt in inside App Updates to evaluate published beta channel releases.",
  feedback_enabled:
    "Shows optional voluntary feedback entry inside Settings when the reviewed server endpoint is also enabled.",
  online_maintenance:
    "Displays a non-blocking notice on online-only screens (Updates / Cloud sync). Never affects local vault access.",
  update_check_interval_hours:
    "Periodic WorkManager check cadence in hours (strictly bounded 6..72h; default 12h).",
};

export const FeatureFlagsView: React.FC<FeatureFlagsViewProps> = ({ flags, onUpdateFlag }) => {
  const [intervalDraft, setIntervalDraft] = useState<string>(() => {
    const f = flags.find((x) => x.flag_key === "update_check_interval_hours");
    return String(f?.integer_value ?? 12);
  });
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pendingConfirm, setPendingConfirm] = useState<{
    title: string;
    description: string;
    run: () => Promise<void>;
  } | null>(null);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="panel">
        <div className="panel-header">
          <div>
            <h2>Allowlisted Feature Flags</h2>
            <p className="muted" style={{ margin: "4px 0 0" }}>
              Only pre-approved flags implemented in the APK are permitted. Local encryption, biometrics, master passwords, backups, and decoy separation cannot be altered remotely.
            </p>
          </div>
        </div>

        {errorMsg && <div className="notice error" style={{ marginBottom: 14 }}>{errorMsg}</div>}
        {successMsg && <div className="notice success" style={{ marginBottom: 14 }}>{successMsg}</div>}

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Flag Key &amp; Purpose</th>
                <th>Safe Default</th>
                <th>Current Value</th>
                <th>Published</th>
                <th>Controls</th>
              </tr>
            </thead>
            <tbody>
              {flags.map((flag) => {
                const isInt = flag.flag_key === "update_check_interval_hours";
                return (
                  <tr key={flag.flag_key}>
                    <td>
                      <code>{flag.flag_key}</code>
                      <div className="muted">{FLAG_DESCRIPTIONS[flag.flag_key]}</div>
                    </td>
                    <td>{SAFE_FLAG_DEFAULTS[flag.flag_key]}</td>
                    <td>
                      <strong>{isInt ? `${flag.integer_value ?? 12}h` : String(Boolean(flag.boolean_value))}</strong>
                    </td>
                    <td>
                      <span className={`badge ${flag.is_published ? "success" : "warning"}`}>
                        {flag.is_published ? "published" : "unpublished"}
                      </span>
                    </td>
                    <td>
                      <div className="row-actions">
                        {isInt ? (
                          <>
                            <input
                              aria-label="Update check interval hours"
                              type="number"
                              min={6}
                              max={72}
                              value={intervalDraft}
                              onChange={(e) => setIntervalDraft(e.target.value)}
                              style={{ width: 80, padding: "6px 8px", borderRadius: 8 }}
                            />
                            <button
                              type="button"
                              className="btn primary"
                              disabled={busy}
                              onClick={() => {
                                const parsed = Number(intervalDraft);
                                if (!Number.isInteger(parsed) || parsed < 6 || parsed > 72) {
                                  setErrorMsg("Interval must be a whole number between 6 and 72 hours.");
                                  return;
                                }
                                setPendingConfirm({
                                  title: `Set update check interval to ${parsed}h?`,
                                  description: "Clients will adopt this cadence on their next periodic policy sync.",
                                  run: () => onUpdateFlag(flag.flag_key, { integer_value: parsed }),
                                });
                              }}
                            >
                              Save Hours
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            className="btn primary"
                            disabled={busy}
                            onClick={() => {
                              const nextVal = !flag.boolean_value;
                              setPendingConfirm({
                                title: `Set ${flag.flag_key} = ${nextVal}?`,
                                description: `This changes the operational flag state from ${Boolean(flag.boolean_value)} to ${nextVal}.`,
                                run: () => onUpdateFlag(flag.flag_key, { boolean_value: nextVal }),
                              });
                            }}
                          >
                            Toggle to {String(!flag.boolean_value)}
                          </button>
                        )}
                        <button
                          type="button"
                          className="btn"
                          disabled={busy}
                          onClick={() =>
                            setPendingConfirm({
                              title: `${flag.is_published ? "Unpublish" : "Publish"} ${flag.flag_key}?`,
                              description: flag.is_published
                                ? "Public clients will revert to safe compiled defaults for this key."
                                : "Makes this flag visible to public clients.",
                              run: () => onUpdateFlag(flag.flag_key, { is_published: !flag.is_published }),
                            })
                          }
                        >
                          {flag.is_published ? "Unpublish" : "Publish"}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <ConfirmDialog
        open={Boolean(pendingConfirm)}
        title={pendingConfirm?.title ?? ""}
        description={pendingConfirm?.description ?? ""}
        confirmLabel="Apply Flag Update"
        busy={busy}
        onConfirm={async () => {
          if (!pendingConfirm) return;
          setBusy(true);
          setErrorMsg(null);
          setSuccessMsg(null);
          try {
            await pendingConfirm.run();
            setPendingConfirm(null);
            setSuccessMsg("Feature flag updated.");
          } catch (e) {
            setErrorMsg(e instanceof Error ? e.message : "Failed updating feature flag.");
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
