# Fichiers de compatibilité Android ↔ Desktop

Générés par `DesktopFixturesTest` (core/data) et `LanFixturesTest` (app) :
`./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*FixturesTest*' -PnautileFixtures`

- `android.backup` : sauvegarde chiffrée par l'app Android (mot de passe `nautile-compat ✓`).
- `android-backup.json` : son contenu en clair, tel que sérialisé par Android.
- `lan-*.json` : poignées de main du protocole Wi-Fi v2 (SPAKE2 puis synchro) enregistrées avec des valeurs aléatoires fixes ; `LanFixturesTest` (Android) et `lan_compat.rs` (Desktop) les rejouent octet pour octet.
- `desktop.backup` : écrit par l'app Desktop ; `DesktopFixturesTest.readsDesktopBackup` vérifie qu'Android le relit.
