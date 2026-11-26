import { createClient, type SupabaseClient } from "@supabase/supabase-js";

// Public client configuration only — never embed service_role secrets or admin credentials.
export const SUPABASE_URL =
  import.meta.env.VITE_SUPABASE_URL?.trim() || "https://lszjacrrqthhdapzvjok.supabase.co";

export const SUPABASE_PUBLISHABLE_KEY =
  import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim() ||
  "sb_publishable_-9CyCkZpssMM_PJt2q3PYw_ybXtYnA_";

// Release activation remains intentionally disabled until the protected server endpoint is deployed.
export const RELEASE_CONTROL_ENDPOINT_ENABLED =
  import.meta.env.VITE_RELEASE_CONTROL_ENABLED === "true";

let cachedClient: SupabaseClient | null = null;

export function getSupabaseClient(): SupabaseClient {
  if (!cachedClient) {
    cachedClient = createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
      auth: {
        autoRefreshToken: true,
        persistSession: true,
        detectSessionInUrl: false,
      },
    });
  }
  return cachedClient;
}
