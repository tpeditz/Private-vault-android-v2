import React, { useState } from "react";
import { QrCodeSvg } from "../components/QrCodeSvg";
import type { AdminSecurityStatus } from "../types/controlCenter";

interface SecurityViewProps {
  status: AdminSecurityStatus;
  onSignIn: (email: string, password: string) => Promise<void>;
  onSignOut: () => Promise<void>;
  onEnrollTotp: () => Promise<{ factorId: string; uri: string; secret: string }>;
  onVerifyTotp: (factorId: string, code: string) => Promise<void>;
}

export const SecurityView: React.FC<SecurityViewProps> = ({
  status,
  onSignIn,
  onSignOut,
  onEnrollTotp,
  onVerifyTotp,
}) => {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [enrollment, setEnrollment] = useState<{ factorId: string; uri: string; secret: string } | null>(null);
  const [totpCode, setTotpCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setSuccessMsg(null);
    setBusy(true);
    try {
      await onSignIn(email, password);
      setPassword("");
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Authentication failed.");
    } finally {
      setBusy(false);
    }
  };

  const handleStartEnroll = async () => {
    setErrorMsg(null);
    setBusy(true);
    try {
      const res = await onEnrollTotp();
      setEnrollment(res);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Could not enroll TOTP factor.");
    } finally {
      setBusy(false);
    }
  };

  const handleVerifyMfa = async (e: React.FormEvent) => {
    e.preventDefault();
    const factorId = enrollment?.factorId ?? status.verifiedTotpFactorId;
    if (!factorId) {
      setErrorMsg("No TOTP factor selected for verification.");
      return;
    }
    setErrorMsg(null);
    setBusy(true);
    try {
      await onVerifyTotp(factorId, totpCode);
      setEnrollment(null);
      setTotpCode("");
      setSuccessMsg("MFA challenge verified cleanly (AAL2 active).");
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Invalid authenticator code.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="panel">
        <h2>Admin Authentication &amp; MFA Security</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Public GitHub Pages hosts only static frontend files. Access to operational data requires a Supabase Auth account listed in <code>public.admin_roles</code> with verified TOTP MFA (<strong>AAL2</strong>).
        </p>

        {errorMsg && <div className="notice error" style={{ marginBottom: 14 }}>{errorMsg}</div>}
        {successMsg && <div className="notice success" style={{ marginBottom: 14 }}>{successMsg}</div>}

        {!status.authenticated ? (
          <form onSubmit={handleLogin} style={{ display: "flex", flexDirection: "column", gap: 14, maxWidth: 420 }}>
            <div className="field">
              <label htmlFor="admin-email">Administrator Email</label>
              <input
                id="admin-email"
                type="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </div>
            <div className="field">
              <label htmlFor="admin-password">Password</label>
              <input
                id="admin-password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>
            <button type="submit" className="btn primary" disabled={busy}>
              {busy ? "Signing In..." : "Sign In"}
            </button>
          </form>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            <div className="card-grid">
              <div className="notice">
                <div className="muted">Signed In Account</div>
                <strong>{status.email ?? status.userId}</strong>
                <div className="muted">
                  Session expires: {status.sessionExpiresAtEpochSec ? new Date(status.sessionExpiresAtEpochSec * 1000).toLocaleString() : "—"}
                </div>
              </div>
              <div className="notice">
                <div className="muted">Assurance Level &amp; Role</div>
                <div style={{ marginTop: 4, display: "flex", gap: 8, flexWrap: "wrap" }}>
                  <span className={`badge ${status.currentLevel === "aal2" ? "success" : "warning"}`}>
                    {status.currentLevel?.toUpperCase() ?? "AAL1"}
                  </span>
                  <span className={`badge ${status.adminRole?.active ? "success" : "danger"}`}>
                    {status.adminRole ? `Role: ${status.adminRole.role}` : "Not in admin_roles"}
                  </span>
                </div>
              </div>
            </div>

            {status.currentLevel !== "aal2" && (
              <div className="panel" style={{ background: "var(--surface-alt)" }}>
                <h3 style={{ marginBottom: 8 }}>Complete Two-Factor Verification (AAL2 Required)</h3>
                {!status.hasVerifiedTotpFactor && !enrollment && (
                  <div>
                    <p className="muted">
                      Your account does not yet have a verified TOTP authenticator factor enrolled. Enroll one below before accessing admin tables.
                    </p>
                    <button type="button" className="btn primary" onClick={handleStartEnroll} disabled={busy}>
                      Enroll Authenticator App (TOTP)
                    </button>
                  </div>
                )}

                {enrollment && (
                  <div style={{ display: "flex", flexDirection: "column", gap: 12, marginBottom: 14 }}>
                    <p className="muted" style={{ marginBottom: 0 }}>
                      Scan this QR code inside Private Vault Authenticator (or another TOTP app), then enter the 6-digit code below.
                    </p>
                    <QrCodeSvg value={enrollment.uri} />
                    <div>
                      <span className="muted">Manual Secret: </span>
                      <code>{enrollment.secret}</code>
                    </div>
                  </div>
                )}

                {(status.hasVerifiedTotpFactor || enrollment) && (
                  <form onSubmit={handleVerifyMfa} style={{ display: "flex", gap: 10, alignItems: "flex-end", flexWrap: "wrap" }}>
                    <div className="field">
                      <label htmlFor="mfa-code">6-Digit Authenticator Code</label>
                      <input
                        id="mfa-code"
                        inputMode="numeric"
                        maxLength={6}
                        value={totpCode}
                        onChange={(e) => setTotpCode(e.target.value)}
                        placeholder="123456"
                        required
                      />
                    </div>
                    <button type="submit" className="btn primary" disabled={busy}>
                      Verify MFA &amp; Unlock Dashboard
                    </button>
                  </form>
                )}
              </div>
            )}

            <div>
              <button type="button" className="btn danger" onClick={onSignOut} disabled={busy}>
                Sign Out
              </button>
            </div>
          </div>
        )}
      </div>

      <div className="panel">
        <h3 style={{ marginBottom: 8 }}>Provisioning &amp; Revocation Model</h3>
        <ul className="muted" style={{ marginBottom: 0, paddingLeft: 20 }}>
          <li>Public sign-up is disabled and does not grant admin permissions.</li>
          <li>New administrators must be created by the owner in Supabase Authentication and manually allowlisted in <code>public.admin_roles</code> with <code>active = true</code>.</li>
          <li>To revoke access immediately, set <code>active = false</code> or delete the user&apos;s row in <code>public.admin_roles</code>. All RLS policies re-check live membership and AAL2 status on every request.</li>
        </ul>
      </div>
    </div>
  );
};
