# Private Vault documentation

Developer, operator and quality-assurance references for the Private Vault project.
For the application overview, download links and build commands, see the [main README](../README.md).

## Guides

| Guide | Purpose |
| --- | --- |
| [Google Drive setup](guides/google-drive.md) | Optional backup and Google configuration |
| [Admin setup](guides/admin-setup.md) | Authorized control-center operation |
| [Supabase deployment](guides/supabase-deployment.md) | Backend deployment process |
| [Deployment and hosting](guides/deployment-hosting.md) | Hosting and setup reference |
| [Release setup](guides/release-setup.md) | Production signing and release workflow |

## Technical reference

| Reference | Purpose |
| --- | --- |
| [Security and data format](reference/security-data-format.md) | Vault and cryptographic format reference |
| [Backup compatibility](reference/backup-compatibility.md) | Supported backup format history |
| [OTA update contract](reference/ota-contract.md) | Update and release metadata |
| [Release policy and privacy](reference/release-policy-privacy.md) | Operational and privacy policies |

## Quality assurance

These records describe the verification completed or planned for the existing source. A historical passing result does not prove a later build has passed.

| Reference | Purpose |
| --- | --- |
| [Feature checklist](quality/feature-checklist.md) | Implemented functionality reference |
| [Test report](quality/test-report.md) | Recorded test and build results |
| [Android device checklist](quality/android-device-checklist.md) | Owner-device functional validation |
| [Admin checklist](quality/admin-checklist.md) | Control-center verification |
| [Schema compatibility](quality/schema-compatibility.md) | Backend/client compatibility record |

The automated tests are maintained within the [Android app](../app/) and [admin frontend](../admin-web/), with CI in [GitHub Actions](../.github/workflows/ci.yml).
