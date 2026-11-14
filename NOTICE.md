# Source provenance and third-party notices

Talky Android derives from Telegram Android and OwpenGram. The initial public snapshot was exported from Talky's O9 source commit `2dfc661312` on 2026-11-14. Private Git history is not imported.

- Telegram Android: https://github.com/DrKLO/Telegram
- OwpenGram: https://github.com/owpengram/owpengram-android-client
- Telegram's stated Android license: GNU GPL version 2 or later, https://telegram.org/apps/

The existing GPL v2 text is retained in LICENSE, with the GPL-2.0-or-later basis described in the upstream source headers. Original copyright notices and dependency licenses remain. This notice does not relicense individual libraries, assets, SDKs or brands.

The public snapshot keeps the client source, native dependency build scripts and pinned submodules. It excludes private credentials, signing material, service project configuration and generated artifacts. Provider/API values and release signing settings are read from local configuration. Debug signing is separated from the official release identity. These changes mean the export is not a byte-identical O9 APK reproduction claim.
