// Pure validation and streaming APK verification helpers shared across Supabase Edge Functions and Deno tests.

export const OFFICIAL_REPO = "tpeditz/Private-vault-android-v2";
export const OFFICIAL_APP_ID = "com.tpeditz.privatevault.v2";
export const OFFICIAL_SIGNING_CERT_SHA256 =
  "3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770";

export const ALLOWED_GITHUB_HOSTS = new Set([
  "github.com",
  "raw.githubusercontent.com",
  "objects.githubusercontent.com",
  "release-assets.githubusercontent.com",
]);

export const MAX_APK_BYTES = 262_144_000;

export interface VerifiedGitHubManifest {
  versionName: string;
  versionCode: number;
  sha256: string;
  sizeBytes: number;
  signingCertSha256: string;
  apkUrl: string;
}

export interface StreamedApkVerification {
  sha256Hex: string;
  byteCount: number;
  hasZipHeader: boolean;
  hasApkSigningBlockMagic: boolean;
}

/**
 * Pure TypeScript incremental SHA-256 implementation so large APK payloads can be digested
 * in constant memory inside edge runtimes without buffering the entire binary in RAM.
 */
export class StreamingSha256 {
  private readonly h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  private readonly w = new Uint32Array(64);
  private readonly block = new Uint8Array(64);
  private blockLen = 0;
  private totalBytes = 0;

  private static readonly K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]);

  update(chunk: Uint8Array): void {
    this.totalBytes += chunk.length;
    let offset = 0;

    if (this.blockLen > 0) {
      const need = 64 - this.blockLen;
      const copyLen = Math.min(need, chunk.length);
      this.block.set(chunk.subarray(0, copyLen), this.blockLen);
      this.blockLen += copyLen;
      offset += copyLen;
      if (this.blockLen === 64) {
        this.compress(this.block, 0);
        this.blockLen = 0;
      }
    }

    while (offset + 64 <= chunk.length) {
      this.compress(chunk, offset);
      offset += 64;
    }

    if (offset < chunk.length) {
      const tail = chunk.subarray(offset);
      this.block.set(tail, 0);
      this.blockLen = tail.length;
    }
  }

  digestHex(): string {
    const bitLenHi = Math.floor((this.totalBytes * 8) / 0x100000000);
    const bitLenLo = (this.totalBytes * 8) >>> 0;

    const pad = new Uint8Array(this.blockLen < 56 ? 64 : 128);
    pad.set(this.block.subarray(0, this.blockLen), 0);
    pad[this.blockLen] = 0x80;
    const view = new DataView(pad.buffer);
    view.setUint32(pad.length - 8, bitLenHi, false);
    view.setUint32(pad.length - 4, bitLenLo, false);

    this.compress(pad, 0);
    if (pad.length === 128) {
      this.compress(pad, 64);
    }

    return Array.from(this.h)
      .map((word) => word.toString(16).padStart(8, "0"))
      .join("");
  }

  private compress(buf: Uint8Array, offset: number): void {
    const w = this.w;
    for (let i = 0; i < 16; i++) {
      const idx = offset + i * 4;
      w[i] =
        ((buf[idx] << 24) | (buf[idx + 1] << 16) | (buf[idx + 2] << 8) | buf[idx + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i++) {
      const s0 = (this.rotr(w[i - 15], 7) ^ this.rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
      const s1 = (this.rotr(w[i - 2], 17) ^ this.rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }

    let [a, b, c, d, e, f, g, h] = this.h;
    for (let i = 0; i < 64; i++) {
      const s1 = (this.rotr(e, 6) ^ this.rotr(e, 11) ^ this.rotr(e, 25)) >>> 0;
      const ch = ((e & f) ^ (~e & g)) >>> 0;
      const temp1 = (h + s1 + ch + StreamingSha256.K[i] + w[i]) >>> 0;
      const s0 = (this.rotr(a, 2) ^ this.rotr(a, 13) ^ this.rotr(a, 22)) >>> 0;
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const temp2 = (s0 + maj) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    this.h[0] = (this.h[0] + a) >>> 0;
    this.h[1] = (this.h[1] + b) >>> 0;
    this.h[2] = (this.h[2] + c) >>> 0;
    this.h[3] = (this.h[3] + d) >>> 0;
    this.h[4] = (this.h[4] + e) >>> 0;
    this.h[5] = (this.h[5] + f) >>> 0;
    this.h[6] = (this.h[6] + g) >>> 0;
    this.h[7] = (this.h[7] + h) >>> 0;
  }

  private rotr(value: number, bits: number): number {
    return (value >>> bits) | (value << (32 - bits));
  }
}

const APK_SIG_BLOCK_MAGIC = new TextEncoder().encode("APK Sig Block 42");

/**
 * Streams an APK binary body, computing the full SHA-256 digest and exact byte length,
 * while inspecting ZIP header magic (`PK\x03\x04`) and APK Signing Block v2/v3 magic (`APK Sig Block 42`).
 */
export async function verifyApkBinaryStream(
  stream: ReadableStream<Uint8Array>,
  expectedSizeBytes: number,
  expectedSha256Hex: string,
): Promise<StreamedApkVerification> {
  const hasher = new StreamingSha256();
  const reader = stream.getReader();
  let byteCount = 0;
  let hasZipHeader = false;
  let firstFourCollected = 0;
  const headerBytes = new Uint8Array(4);
  let magicMatchPos = 0;
  let hasApkSigningBlockMagic = false;

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!value || value.length === 0) continue;

      byteCount += value.length;
      if (byteCount > MAX_APK_BYTES) {
        throw new Error("Downloaded APK exceeds maximum supported 250 MiB bound.");
      }

      if (firstFourCollected < 4) {
        const take = Math.min(4 - firstFourCollected, value.length);
        headerBytes.set(value.subarray(0, take), firstFourCollected);
        firstFourCollected += take;
        if (firstFourCollected === 4) {
          hasZipHeader =
            headerBytes[0] === 0x50 &&
            headerBytes[1] === 0x4b &&
            headerBytes[2] === 0x03 &&
            headerBytes[3] === 0x04;
        }
      }

      if (!hasApkSigningBlockMagic) {
        for (let i = 0; i < value.length; i++) {
          if (value[i] === APK_SIG_BLOCK_MAGIC[magicMatchPos]) {
            magicMatchPos++;
            if (magicMatchPos === APK_SIG_BLOCK_MAGIC.length) {
              hasApkSigningBlockMagic = true;
              break;
            }
          } else {
            magicMatchPos = value[i] === APK_SIG_BLOCK_MAGIC[0] ? 1 : 0;
          }
        }
      }

      hasher.update(value);
    }
  } finally {
    reader.releaseLock();
  }

  if (byteCount !== expectedSizeBytes) {
    throw new Error(
      `APK byte length mismatch (actual ${byteCount}, manifest ${expectedSizeBytes}).`,
    );
  }
  if (!hasZipHeader) {
    throw new Error("Downloaded artifact is not a valid ZIP/APK archive.");
  }
  if (!hasApkSigningBlockMagic) {
    throw new Error("Downloaded APK does not contain an APK Signature Scheme v2/v3 signing block.");
  }

  const sha256Hex = hasher.digestHex();
  if (sha256Hex !== expectedSha256Hex.toLowerCase()) {
    throw new Error("Downloaded APK SHA-256 digest does not match update.json manifest.");
  }

  return { sha256Hex, byteCount, hasZipHeader, hasApkSigningBlockMagic };
}

export function normalizeCertSha256(raw: string): string {
  return raw.replaceAll(":", "").trim().toLowerCase();
}

export function expectedGitHubManifestUrl(versionName: string): string {
  const trimmed = versionName.trim();
  if (!/^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$/.test(trimmed)) {
    throw new Error("Invalid semantic version format.");
  }
  return `https://github.com/${OFFICIAL_REPO}/releases/download/v${trimmed}/update.json`;
}

export function validateTrustedGitHubHttpsUrl(rawUrl: string): URL {
  const parsed = new URL(rawUrl);
  if (parsed.protocol !== "https:") {
    throw new Error("Only HTTPS release URLs are permitted.");
  }
  if (parsed.username || parsed.password) {
    throw new Error("Credentials in release URL are forbidden.");
  }
  if (!ALLOWED_GITHUB_HOSTS.has(parsed.hostname.toLowerCase())) {
    throw new Error(`Untrusted release host rejected: ${parsed.hostname}`);
  }
  return parsed;
}

export function validateGitHubUpdateManifest(
  rawJson: unknown,
  expectedVersionName: string,
  expectedVersionCode: number,
): VerifiedGitHubManifest {
  if (!rawJson || typeof rawJson !== "object") {
    throw new Error("Malformed release manifest JSON.");
  }
  const m = rawJson as Record<string, unknown>;
  if (m.schemaVersion !== 1) {
    throw new Error("Unsupported manifest schemaVersion.");
  }
  if (m.applicationId !== OFFICIAL_APP_ID) {
    throw new Error("Manifest applicationId does not match Private Vault.");
  }
  if (m.versionName !== expectedVersionName) {
    throw new Error("Manifest versionName does not match draft record.");
  }
  if (m.versionCode !== expectedVersionCode) {
    throw new Error("Manifest versionCode does not match draft record.");
  }
  const sha = typeof m.sha256 === "string" ? m.sha256.trim().toLowerCase() : "";
  if (!/^[0-9a-f]{64}$/.test(sha)) {
    throw new Error("Manifest apk sha256 is invalid.");
  }
  const size = typeof m.sizeBytes === "number" ? m.sizeBytes : Number(m.sizeBytes ?? 0);
  if (!Number.isInteger(size) || size < 1 || size > MAX_APK_BYTES) {
    throw new Error("Manifest sizeBytes is out of allowed bounds.");
  }
  const certRaw = typeof m.signingCertSha256 === "string" ? m.signingCertSha256 : "";
  const cert = normalizeCertSha256(certRaw);
  if (cert !== OFFICIAL_SIGNING_CERT_SHA256) {
    throw new Error("Manifest signingCertSha256 does not match official release key.");
  }
  const apkUrl = typeof m.apkUrl === "string" ? m.apkUrl.trim() : "";
  validateTrustedGitHubHttpsUrl(apkUrl);
  const expectedPrefix = `https://github.com/${OFFICIAL_REPO}/releases/download/v${expectedVersionName}/`;
  if (!apkUrl.startsWith(expectedPrefix)) {
    throw new Error("Manifest apkUrl must point to the exact versioned GitHub release asset.");
  }

  return {
    versionName: expectedVersionName,
    versionCode: expectedVersionCode,
    sha256: sha,
    sizeBytes: size,
    signingCertSha256: cert,
    apkUrl,
  };
}

export function decodeJwtAalClaim(jwt: string): string | null {
  const parts = jwt.split(".");
  if (parts.length !== 3) return null;
  try {
    const b64 = parts[1].replaceAll("-", "+").replaceAll("_", "/");
    const pad = b64.length % 4 === 0 ? b64 : b64 + "=".repeat(4 - (b64.length % 4));
    const decoded = atob(pad);
    const payload = JSON.parse(decoded) as Record<string, unknown>;
    return typeof payload.aal === "string" ? payload.aal : null;
  } catch {
    return null;
  }
}

export function validateFeedbackInput(body: unknown): {
  category: "bug" | "suggestion" | "other";
  message: string;
  contact: string | null;
  app_version: string | null;
  consent: true;
} {
  if (!body || typeof body !== "object") {
    throw new Error("Invalid request payload.");
  }
  const obj = body as Record<string, unknown>;
  if (obj.consent !== true) {
    throw new Error("Explicit consent is required to submit feedback.");
  }
  const category = obj.category;
  if (category !== "bug" && category !== "suggestion" && category !== "other") {
    throw new Error("Unsupported feedback category.");
  }
  const message = typeof obj.message === "string" ? obj.message.trim() : "";
  if (message.length < 1 || message.length > 3000) {
    throw new Error("Message must be 1..3000 characters.");
  }
  const contactRaw = typeof obj.contact === "string" ? obj.contact.trim() : "";
  if (contactRaw.length > 254) {
    throw new Error("Contact field must be 254 characters or fewer.");
  }
  const versionRaw = typeof obj.app_version === "string" ? obj.app_version.trim() : "";
  if (versionRaw.length > 30) {
    throw new Error("App version must be 30 characters or fewer.");
  }
  return {
    category,
    message,
    contact: contactRaw.length > 0 ? contactRaw : null,
    app_version: versionRaw.length > 0 ? versionRaw : null,
    consent: true,
  };
}
