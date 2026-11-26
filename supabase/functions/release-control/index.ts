// Protected Release Control Edge Function (Reviewable Only — Do Not Deploy Without Owner/ChatGPT Approval)
// Enforces active admin_roles membership, AAL2 MFA, and independent GitHub release manifest verification.

import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  ALLOWED_GITHUB_HOSTS,
  decodeJwtAalClaim,
  expectedGitHubManifestUrl,
  validateGitHubUpdateManifest,
  validateTrustedGitHubHttpsUrl,
  verifyApkBinaryStream,
} from "../_shared/releaseValidation.ts";

const DEFAULT_ORIGINS = ["https://tpeditz.github.io", "http://localhost:5173"];

function buildCorsHeaders(origin: string | null): HeadersInit {
  const configured = (Deno.env.get("ADMIN_ALLOWED_ORIGINS") ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  const allowlist = configured.length > 0 ? configured : DEFAULT_ORIGINS;
  const matchedOrigin = origin && allowlist.includes(origin) ? origin : allowlist[0];
  return {
    "Access-Control-Allow-Origin": matchedOrigin,
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Vary": "Origin",
  };
}

function jsonResponse(status: number, body: unknown, cors: HeadersInit): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json; charset=utf-8" },
  });
}

async function fetchWithTrustedRedirects(url: string, method: "GET" | "HEAD", maxHops = 4): Promise<Response> {
  let current = url;
  for (let hop = 0; hop <= maxHops; hop++) {
    const parsed = validateTrustedGitHubHttpsUrl(current);
    if (!ALLOWED_GITHUB_HOSTS.has(parsed.hostname.toLowerCase())) {
      throw new Error("Untrusted redirect target.");
    }
    const resp = await fetch(current, { method, redirect: "manual" });
    if (resp.status >= 300 && resp.status < 400) {
      const location = resp.headers.get("location");
      if (!location) throw new Error("Redirect missing Location header.");
      current = new URL(location, current).toString();
      continue;
    }
    if (!resp.ok) {
      throw new Error(`Upstream GitHub asset check failed with HTTP ${resp.status}.`);
    }
    return resp;
  }
  throw new Error("Too many redirects inspecting GitHub release asset.");
}

Deno.serve(async (req: Request) => {
  const cors = buildCorsHeaders(req.headers.get("origin"));
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (req.method !== "POST") return jsonResponse(405, { error: "Method not allowed" }, cors);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (!supabaseUrl || !serviceKey || !anonKey) {
    return jsonResponse(500, { error: "Server configuration incomplete." }, cors);
  }

  const authHeader = req.headers.get("authorization") ?? "";
  const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
  if (!token) return jsonResponse(401, { error: "Missing bearer token." }, cors);

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userErr } = await userClient.auth.getUser(token);
  if (userErr || !userData.user) {
    return jsonResponse(401, { error: "Invalid or expired session." }, cors);
  }

  // Verify AAL2 after Supabase Auth validates signature and expiration cleanly.
  const { data: aalData } = await userClient.auth.mfa.getAuthenticatorAssuranceLevel();
  const aal = aalData?.currentLevel ?? decodeJwtAalClaim(token);
  if (aal !== "aal2") {
    return jsonResponse(403, { error: "Multi-factor authentication (AAL2) is required." }, cors);
  }

  const adminClient = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: roleRow, error: roleErr } = await adminClient
    .from("admin_roles")
    .select("user_id, role, active")
    .eq("user_id", userData.user.id)
    .eq("active", true)
    .maybeSingle();

  if (roleErr || !roleRow || (roleRow.role !== "owner" && roleRow.role !== "admin")) {
    return jsonResponse(403, { error: "Active admin role required." }, cors);
  }

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return jsonResponse(400, { error: "Invalid JSON body." }, cors);
  }

  const releaseId = typeof body.release_id === "string" ? body.release_id.trim() : "";
  const action = typeof body.action === "string" ? body.action.trim() : "";
  if (!releaseId || !["publish", "pause", "resume", "withdraw", "update_rollout"].includes(action)) {
    return jsonResponse(400, { error: "Invalid release_id or action." }, cors);
  }

  const { data: targetRow, error: fetchErr } = await adminClient
    .from("releases")
    .select("id, version_name, version_code, channel, priority, rollout_percent, available_from, available_until")
    .eq("id", releaseId)
    .maybeSingle();
  if (fetchErr || !targetRow) {
    return jsonResponse(404, { error: "Target release not found." }, cors);
  }

  let verifiedSha: string | null = null;
  let verifiedSize: number | null = null;
  let verifiedCert: string | null = null;

  if (action === "publish" || action === "resume") {
    try {
      const manifestUrl = expectedGitHubManifestUrl(targetRow.version_name);
      const manifestResp = await fetchWithTrustedRedirects(manifestUrl, "GET");
      const rawText = await manifestResp.text();
      if (rawText.length > 262_144) {
        throw new Error("GitHub update.json exceeds 256 KiB limit.");
      }
      const verified = validateGitHubUpdateManifest(
        JSON.parse(rawText),
        targetRow.version_name,
        targetRow.version_code,
      );

      // Stream the actual signed APK asset from GitHub to compute exact byte length, full SHA-256,
      // ZIP header magic, and APK Signature Scheme v2/v3 signing block presence (constant RAM).
      const apkResp = await fetchWithTrustedRedirects(verified.apkUrl, "GET");
      if (!apkResp.body) {
        throw new Error("GitHub release APK response stream is empty.");
      }
      await verifyApkBinaryStream(apkResp.body, verified.sizeBytes, verified.sha256);

      verifiedSha = verified.sha256;
      verifiedSize = verified.sizeBytes;
      verifiedCert = verified.signingCertSha256;
    } catch (err) {
      return jsonResponse(422, {
        error: err instanceof Error ? err.message : "Failed to verify GitHub release artifacts.",
      }, cors);
    }
  }

  const priority = typeof body.priority === "string" && ["optional", "recommended", "critical"].includes(body.priority)
    ? body.priority
    : null;
  const rolloutRaw = body.rollout_percent;
  const rolloutPercent = rolloutRaw === undefined || rolloutRaw === null ? null : Number(rolloutRaw);
  if (rolloutPercent !== null && (!Number.isInteger(rolloutPercent) || rolloutPercent < 0 || rolloutPercent > 100)) {
    return jsonResponse(400, { error: "rollout_percent must be integer 0..100." }, cors);
  }

  const availableFrom = typeof body.available_from === "string" ? body.available_from : null;
  const availableUntil = typeof body.available_until === "string" ? body.available_until : null;
  const clearAvailableUntil = body.clear_available_until === true;

  const { data: transitioned, error: rpcErr } = await adminClient.rpc("admin_transition_release", {
    p_release_id: releaseId,
    p_action: action,
    p_actor_user_id: userData.user.id,
    p_apk_sha256: verifiedSha,
    p_apk_size_bytes: verifiedSize,
    p_signing_cert_sha256: verifiedCert,
    p_priority: priority,
    p_rollout_percent: rolloutPercent,
    p_available_from: availableFrom,
    p_available_until: availableUntil,
    p_clear_available_until: clearAvailableUntil,
  });

  if (rpcErr) {
    return jsonResponse(400, { error: rpcErr.message }, cors);
  }

  return jsonResponse(200, { release: transitioned }, cors);
});
