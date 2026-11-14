# Initial public export review — 2026-11-14

The public repository starts from a new O9-based source snapshot. Private Git objects, branches and tags are not imported; the original private repository remains archived separately.

## Source hygiene

Gitleaks scanned original reachable Git history and the public candidate, with report values redacted. Additional checks searched for original signing passwords, known operational credentials and personal identifiers. Tracked signing passwords, provider keys and inherited Google/Huawei project files were removed. API/provider values and release signing settings now come from ignored local properties, Gradle properties or environment variables. The real signing key stays outside Git.

The final source scan retains ten recognitions of public WebRTC test keys, a STUN test password, dependency checksums and SQLite syntax. These were reviewed as test fixtures or ordinary source, not deployment credentials. They must not be used for production authentication. No broad scanner allowlist was added to conceal them.

All 21 pinned submodule repositories and commits were checked for public accessibility. Source files are below GitHub's ordinary 100 MB single-file limit. Existing copyright headers, GPL license text and third-party license files remain.

## Build verification

The complete `:TMessagesProj_AppStandalone:assembleAfatDebug` task passed with no production API/provider credentials or release signing material supplied. The four-ABI APK uses the `.debug` package suffix and Android's debug signing identity. Generated API fields were confirmed zero/empty. The signed-release configuration also passed `validateSigningAfatStandalone` with the operator's ignored local settings and retained key. The public export was not rebuilt as a release APK, installed or runtime-tested in this task.

The website O9 APK is unchanged. Source configuration/signing improvements mean this snapshot is not a byte-for-byte reproduction claim. Build success and source hygiene checks are not a penetration test or a guarantee of no security issues. See SECURITY.md for private reporting.
