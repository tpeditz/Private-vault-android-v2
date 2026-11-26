import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  OFFICIAL_SIGNING_CERT_SHA256,
  StreamingSha256,
  decodeJwtAalClaim,
  expectedGitHubManifestUrl,
  validateFeedbackInput,
  validateGitHubUpdateManifest,
  validateTrustedGitHubHttpsUrl,
  verifyApkBinaryStream,
} from "../_shared/releaseValidation.ts";

Deno.test("expectedGitHubManifestUrl enforces strict semver and official repo", () => {
  assertEquals(
    expectedGitHubManifestUrl("2.1.0"),
    "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.0/update.json",
  );
  assertThrows(() => expectedGitHubManifestUrl("2.1.0-beta"));
  assertThrows(() => expectedGitHubManifestUrl("../evil"));
});

Deno.test("validateGitHubUpdateManifest verifies official appId, cert, and hosts", () => {
  const validManifest = {
    schemaVersion: 1,
    applicationId: "com.tpeditz.privatevault.v2",
    versionCode: 6,
    versionName: "2.1.0",
    sha256: "a".repeat(64),
    sizeBytes: 12_345_678,
    signingCertSha256: OFFICIAL_SIGNING_CERT_SHA256,
    apkUrl: "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.0/private-vault.apk",
  };
  const verified = validateGitHubUpdateManifest(validManifest, "2.1.0", 6);
  assertEquals(verified.versionCode, 6);
  assertEquals(verified.signingCertSha256, OFFICIAL_SIGNING_CERT_SHA256);

  assertThrows(() =>
    validateGitHubUpdateManifest({ ...validManifest, applicationId: "com.fake.app" }, "2.1.0", 6)
  );
  assertThrows(() =>
    validateGitHubUpdateManifest({ ...validManifest, signingCertSha256: "0".repeat(64) }, "2.1.0", 6)
  );
  assertThrows(() =>
    validateGitHubUpdateManifest({
      ...validManifest,
      apkUrl: "https://evil.example.com/private-vault.apk",
    }, "2.1.0", 6)
  );
});

Deno.test("validateTrustedGitHubHttpsUrl rejects HTTP and credentials", () => {
  assertThrows(() => validateTrustedGitHubHttpsUrl("http://github.com/a/b"));
  assertThrows(() => validateTrustedGitHubHttpsUrl("https://user:pass@github.com/a/b"));
  assertThrows(() => validateTrustedGitHubHttpsUrl("https://attacker.example/private-vault.apk"));
});

Deno.test("decodeJwtAalClaim detects AAL1 vs AAL2 accurately", () => {
  const encodePart = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
  const fakeAal2 = `${encodePart({ alg: "HS256" })}.${encodePart({ aal: "aal2", sub: "u1" })}.sig`;
  const fakeAal1 = `${encodePart({ alg: "HS256" })}.${encodePart({ aal: "aal1", sub: "u1" })}.sig`;
  assertEquals(decodeJwtAalClaim(fakeAal2), "aal2");
  assertEquals(decodeJwtAalClaim(fakeAal1), "aal1");
  assertEquals(decodeJwtAalClaim("not.a.valid.jwt.token"), null);
});

Deno.test("validateFeedbackInput requires explicit consent and bounded message", () => {
  const ok = validateFeedbackInput({
    category: "suggestion",
    message: "Please add dark icon option",
    contact: "  ",
    app_version: "2.1.0",
    consent: true,
  });
  assertEquals(ok.contact, null);
  assertEquals(ok.consent, true);

  assertThrows(() =>
    validateFeedbackInput({ category: "bug", message: "No consent", consent: false })
  );
  assertThrows(() =>
    validateFeedbackInput({ category: "invalid", message: "Bad cat", consent: true })
  );
  assertThrows(() =>
    validateFeedbackInput({ category: "bug", message: "a".repeat(3001), consent: true })
  );
});

Deno.test("StreamingSha256 matches WebCrypto SHA-256 across chunk boundaries", async () => {
  const payload = new TextEncoder().encode("PrivateVault-APK-Stream-Test-Payload-" + "x".repeat(500));
  const expectedBuf = await crypto.subtle.digest("SHA-256", payload);
  const expectedHex = Array.from(new Uint8Array(expectedBuf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  const hasher = new StreamingSha256();
  hasher.update(payload.subarray(0, 17));
  hasher.update(payload.subarray(17, 79));
  hasher.update(payload.subarray(79));
  assertEquals(hasher.digestHex(), expectedHex);
});

Deno.test("verifyApkBinaryStream validates real SHA-256, ZIP magic, and APK Sig Block magic", async () => {
  const header = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00]);
  const sigMagic = new TextEncoder().encode("APK Sig Block 42");
  const full = new Uint8Array(header.length + 100 + sigMagic.length);
  full.set(header, 0);
  full.set(sigMagic, header.length + 50);

  const digestBuf = await crypto.subtle.digest("SHA-256", full);
  const shaHex = Array.from(new Uint8Array(digestBuf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  const makeStream = (bytes: Uint8Array) =>
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.subarray(0, 31));
        controller.enqueue(bytes.subarray(31));
        controller.close();
      },
    });

  const verified = await verifyApkBinaryStream(makeStream(full), full.length, shaHex);
  assertEquals(verified.byteCount, full.length);
  assertEquals(verified.sha256Hex, shaHex);
  assertEquals(verified.hasZipHeader, true);
  assertEquals(verified.hasApkSigningBlockMagic, true);

  await assertRejects(() => verifyApkBinaryStream(makeStream(full), full.length + 1, shaHex));
  await assertRejects(() => verifyApkBinaryStream(makeStream(full), full.length, "0".repeat(64)));

  const nonZip = new Uint8Array(full);
  nonZip[0] = 0x00;
  const nonZipSha = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", nonZip)))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  await assertRejects(() => verifyApkBinaryStream(makeStream(nonZip), nonZip.length, nonZipSha));
});
