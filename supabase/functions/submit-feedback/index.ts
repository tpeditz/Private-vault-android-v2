// Voluntary Consent-Based Feedback Submission Edge Function
// Intentionally un-deployed pending independent security review. Never logs IP or message contents.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { validateFeedbackInput } from "../_shared/releaseValidation.ts";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "content-type",
    },
  });
}

async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204 });
  if (req.method !== "POST") return jsonResponse(405, { error: "Method not allowed" });

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const pepper = Deno.env.get("FEEDBACK_RATE_PEPPER") ?? "private-vault-feedback-bucket-v1";
  if (!supabaseUrl || !serviceKey) {
    return jsonResponse(500, { error: "Feedback service unavailable." });
  }

  let parsedBody: unknown;
  try {
    parsedBody = await req.json();
  } catch {
    return jsonResponse(400, { error: "Invalid JSON payload." });
  }

  let cleanInput;
  try {
    cleanInput = validateFeedbackInput(parsedBody);
  } catch (err) {
    return jsonResponse(400, { error: err instanceof Error ? err.message : "Invalid feedback payload." });
  }

  const forwarded = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
  const rateBucket = await sha256Hex(`${pepper}:${forwarded}`);

  const adminClient = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data, error } = await adminClient.rpc("submit_verified_feedback", {
    p_category: cleanInput.category,
    p_message: cleanInput.message,
    p_contact: cleanInput.contact,
    p_app_version: cleanInput.app_version,
    p_consent: cleanInput.consent,
    p_rate_bucket: rateBucket,
  });

  if (error) {
    const status = error.message.includes("disabled") ? 403 : error.message.includes("Rate limit") ? 429 : 400;
    return jsonResponse(status, { error: error.message });
  }

  return jsonResponse(201, { accepted: true, feedback_id: data });
});
