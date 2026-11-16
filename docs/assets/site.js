// French: English is the HTML's own text.
const FR = {
  "nav.screens": "Captures", "nav.desktop": "Ordinateur", "nav.features": "Fonctionnalités", "nav.install": "Installer",
  "hero.eyebrow": "Version 0.5.4 · Android · Windows · Linux · macOS · GPL-3.0",
  "hero.title": "Tes serveurs,<br><em>dans ta poche.</em>",
  "hero.lead": "Nautile est un client SSH, Mosh et bureau à distance libre pour Android, désormais aussi sur ton ordinateur : un vrai terminal, SFTP, tunnels, snippets et synchro chiffrée entre tes appareils. Pas de cloud, pas de compte : tes hôtes, clés et mots de passe restent chiffrés chez toi.",
  "cta.desktop": "Pour ton ordinateur",
  "desk.title": "Aussi sur ton ordinateur",
  "desk.lead": "Une app native pour Windows, Linux et macOS (écrite en Rust, environ 35 Mo de RAM) avec l’apparence et les réglages du téléphone. Elle lit les sauvegardes du téléphone et reste synchronisée avec lui, par un fichier partagé ou directement sur le Wi-Fi.",
  "desk.hosts": "Les hôtes par groupe, avec connexion rapide et clic droit",
  "desk.assistant": "Le terminal et son assistant IA côte à côte",
  "desk.palette": "Ctrl+K : hôtes, snippets, réglages et actions au clavier",
  "desk.monitor": "Processeur, mémoire, disque et charge d’un serveur, en direct",
  "desk.editor": "Les fichiers distants modifiés sur place, Ctrl+S pour enregistrer",
  "desk.appearance": "Les thèmes, couleurs et styles de boutons du téléphone",
  "desk.ai": "Plusieurs fournisseurs d’IA, tes propres clés",
  "d1.t": "Dans la zone de notification", "d1.d": "Fermer la fenêtre garde les sessions, tunnels et synchros actifs. Démarre avec la session si tu veux.",
  "d2.t": "Pensée pour le clavier et la souris", "d2.d": "Ctrl+K, Ctrl+N, Ctrl+1…9, clic droit sur un hôte, glisser un hôte sur un groupe ou un onglet ailleurs.",
  "d3.t": "Les mêmes données que le téléphone", "d3.d": "Hôtes, clés, identités, snippets et tunnels synchronisés dans les deux sens ; une sauvegarde du téléphone se restaure ici telle quelle.",
  "d4.t": "Apporte tes hôtes", "d4.d": "Importe <code>~/.ssh/config</code> ou un CSV, avec groupes, rebonds et clés. Ou tape <code>root@192.168.1.2</code> dans la recherche.",
  "d5.t": "Une synchro qui attend ton VPN", "d5.d": "Ton Nextcloud ou ton S3 est privé ? La synchro attend WireGuard, Tailscale ou OpenVPN, puis part, sur l’ordinateur comme sur le téléphone.",
  "d6.t": "Les mises à jour depuis l’app", "d6.d": "Si tu acceptes la vérification au premier lancement, une nouvelle version s’affiche au démarrage, avec ses nouveautés ; elle est téléchargée, vérifiée par sa somme de contrôle et installée en un clic.",
  "dl.title": "Télécharger pour ton ordinateur",
  "dl.win": "installeur <code>setup.exe</code>, sans droits administrateur · ou <code>portable.zip</code>",
  "dl.linux": "<code>.deb</code> (Debian, Ubuntu) · <code>.rpm</code> (Fedora, openSUSE) · <code>.tar.gz</code>",
  "dl.mac": "<code>.dmg</code>, pas encore notarisé : ouvre-le une fois, puis Réglages Système › Confidentialité et sécurité › « Ouvrir quand même »",
  "dl.note": "Les fichiers Windows ne sont pas encore signés : SmartScreen affiche « Windows a protégé votre ordinateur » › <i>Informations complémentaires</i> › <i>Exécuter quand même</i>. Les sommes de contrôle sont dans <code>SHA256SUMS-desktop.txt</code>.",
  "cta.download": "Télécharger l’APK", "cta.obtainium": "Installer avec Obtainium", "cta.source": "Code source",
  "facts.server": "compte, pub ou traqueur",
  "screens.title": "Pensé pour un téléphone",
  "screens.lead": "De grandes zones tactiles, une barre de touches pour ce qui manque au clavier, et tout à portée de pouce : se connecter, se reconnecter, ouvrir des fichiers, lancer un snippet.",
  "shot.hosts": "Hôtes et groupes",
  "shot.quick": "Connexion rapide sans enregistrer",
  "shot.terminal": "Un vrai terminal avec barre de touches",
  "shot.connections": "Sessions actives et historique",
  "shot.snippets": "Snippets lancés sur plusieurs serveurs",
  "shot.assistant": "Un assistant IA avec ta propre clé",
  "shot.backup": "Sauvegardes chiffrées planifiées",
  "shot.settings": "Réglages en un coup d’œil",
  "features.title": "Tout ce qu’il faut en déplacement",
  "features.lead": "Ce que tu utilises tous les jours sur un portable, repensé pour un écran tactile.",
  "f1.t": "Un vrai terminal", "f1.d": "xterm-256color, onglets et écran partagé, pincer pour zoomer, une barre de touches à ta façon, 10 thèmes et 5 Nerd Fonts (ou la tienne).",
  "f2.t": "SSH comme il faut", "f2.d": "Clés et identités, transfert d’agent, ProxyJump, proxys SOCKS5/HTTP, port knocking et épinglage des clés d’hôte. Aussi Mosh, Telnet, série USB et shell local.",
  "f3.t": "Bureau à distance", "f3.d": "FreeRDP 3 intégré : NLA, H.264 matériel, son, presse-papier, tactile direct ou pavé tactile, via un tunnel SSH si besoin. Aussi le VNC, pour Linux, macOS et Raspberry Pi.",
  "f4.t": "Connexion rapide", "f4.d": "Tape root@192.168.1.2 dans la recherche et connecte-toi sans rien enregistrer. Tu la retrouves dans l’historique, prête à être enregistrée.",
  "f5.t": "Fichiers, tunnels, snippets", "f5.d": "SFTP avec transferts en arrière-plan et édition distante, redirections -L/-R/-D, commandes enregistrées avec variables lancées sur plusieurs serveurs à la fois.",
  "f6.t": "Synchro et sauvegarde chiffrées", "f6.d": "Garde téléphones, tablettes et ordinateurs à jour via Google Drive, OneDrive, Nextcloud, un stockage S3 ou un dossier Syncthing, ou directement sur le Wi-Fi ; elle peut attendre ton VPN. Sauvegardes à l’heure et sur le réseau choisis, les cinq dernières gardées. Chiffré avant de partir.",
  "f7.t": "Un assistant IA à ta façon", "f7.d": "Claude, OpenAI, Gemini, Grok, Mistral, DeepSeek ou un modèle local, avec ta propre clé et tes prompts système. Écris ou dicte, joins l’écran ou un fichier de config, enchaîne les questions, exporte en Markdown.",
  "f8.t": "Tes sessions d’un coup d’œil", "f8.d": "Aperçu en direct des terminaux et bureaux ouverts, historique avec recherche et logos des systèmes, confirmation avant de fermer une session.",
  "privacy.title": "Privé par conception",
  "privacy.lead": "Nautile n’a pas de serveur et ne collecte rien. Aucune donnée ne part sans ton accord ; la vérification des mises à jour, si tu l’acceptes au premier lancement, contacte GitHub une fois par jour.",
  "p1": "Base SQLCipher, dont la clé reste dans l’Android Keystore (sur ordinateur : le trousseau du système).",
  "p2": "Verrouillage par biométrie, code PIN ou mot de passe avec délai, masquage dans les apps récentes si tu veux.",
  "p3": "Sauvegardes et synchro sont chiffrées sur le téléphone (Argon2id + AES-256-GCM) avant d’aller vers le stockage choisi.",
  "p4": "L’assistant IA optionnel utilise ta propre clé et n’envoie que ta question, l’adresse de l’hôte et ce que tu joins (l’écran du terminal, des fichiers).",
  "p5": "Après un plantage, un rapport anonymisé que tu relis, puis que tu publies toi-même sur GitHub. Rien n’est envoyé sinon.",
  "install.title": "Installer Nautile", "install.lead": "Gratuit pour Android 8.0 et plus récent, en français et en anglais. Sur ordinateur, vois <a href=\"#desktop\">les téléchargements pour ordinateur</a>.",
  "s1.t": "Télécharge l’APK", "s1.d": "<code>arm64-v8a</code> pour presque tous les téléphones récents, <code>armeabi-v7a</code> pour les plus anciens, l’APK universel en cas de doute.",
  "s2.t": "Ouvre-le sur ton téléphone", "s2.d": "Autorise l’installation depuis ton navigateur ou ton gestionnaire de fichiers.",
  "s3.t": "Ajoute un hôte, ou tape-le", "s3.d": "Touche + et choisis SSH, RDP, Telnet, série ou local, importe ton ~/.ssh/config, ou tape utilisateur@adresse pour te connecter tout de suite.",
  "verify.t": "Vérifie la signature", "verify.d": "Chaque version est signée avec la même clé. Paquet <code>com.nautile</code>, empreinte SHA-256 du certificat :",
  "footer.license": "Logiciel libre sous <a href=\"https://github.com/MrDDream/Nautile/blob/main/LICENSE\">GNU GPL v3</a>.",
  "footer.credits": "Construit avec <a href=\"https://github.com/termux/termux-app\">Termux</a>, <a href=\"https://github.com/hierynomus/sshj\">sshj</a>, <a href=\"https://www.freerdp.com\">FreeRDP</a>, <a href=\"https://iced.rs\">iced</a>, <a href=\"https://github.com/Devolutions/IronRDP\">IronRDP</a>, <a href=\"https://github.com/Eugeny/russh\">russh</a> et les <a href=\"https://www.nerdfonts.com\">Nerd Fonts</a>.",
};
const EN = {};
document.querySelectorAll("[data-i18n]").forEach((el) => { EN[el.dataset.i18n] = el.textContent; });
document.querySelectorAll("[data-i18n-html]").forEach((el) => { EN[el.dataset.i18nHtml] = el.innerHTML; });
const META = {
  en: { title: "Nautile · Free SSH & RDP client for Android and desktop", desc: document.querySelector('meta[name="description"]').content },
  fr: { title: "Nautile · Client SSH et RDP libre pour Android et ordinateur", desc: "Nautile est un client SSH, Mosh et bureau à distance libre pour Android, Windows, Linux et macOS : vrai terminal, SFTP, tunnels, snippets et synchro chiffrée entre tes appareils. Pas de cloud, pas de compte." },
};

function setLang(lang, save) {
  const dict = lang === "fr" ? FR : EN;
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-i18n]").forEach((el) => { const v = dict[el.dataset.i18n]; if (v != null) el.textContent = v; });
  document.querySelectorAll("[data-i18n-html]").forEach((el) => { const v = dict[el.dataset.i18nHtml]; if (v != null) el.innerHTML = v; });
  document.querySelectorAll("[data-lang]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.lang === lang)));
  document.title = META[lang].title;
  document.querySelector('meta[name="description"]').content = META[lang].desc;
  if (save) try { localStorage.setItem("nautile-lang", lang); } catch (e) {}
  updateShots();
}
/** mode: "light", "dark" or "auto" (follows the system, live). */
const systemLight = matchMedia("(prefers-color-scheme: light)");
function setTheme(mode, save) {
  const theme = mode === "auto" ? (systemLight.matches ? "light" : "dark") : mode;
  document.documentElement.dataset.themeMode = mode;
  document.documentElement.dataset.theme = theme;
  document.querySelectorAll("[data-theme-set]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.themeSet === mode)));
  document.querySelector('meta[name="theme-color"]').content = theme === "light" ? "#F6F4EF" : "#0B0E12";
  if (save) try { if (mode === "auto") localStorage.removeItem("nautile-theme"); else localStorage.setItem("nautile-theme", mode); } catch (e) {}
  updateShots();
}
systemLight.addEventListener("change", () => { if (document.documentElement.dataset.themeMode === "auto") setTheme("auto", false); });
/** Captures exist per language and theme: assets/shots/<lang>-<theme>-<screen>.png */
function updateShots() {
  const lang = document.documentElement.lang === "fr" ? "fr" : "en";
  const theme = document.documentElement.dataset.theme === "light" ? "light" : "dark";
  document.querySelectorAll("[data-shot]").forEach((img) => { img.src = `assets/shots/${lang}-${theme}-${img.dataset.shot}.png`; });
  document.querySelectorAll("[data-desk]").forEach((img) => { img.src = `assets/desktop/${lang}-${theme}-${img.dataset.desk}.webp`; });
}
document.querySelectorAll("[data-lang]").forEach((b) => b.addEventListener("click", () => setLang(b.dataset.lang, true)));
document.querySelectorAll("[data-theme-set]").forEach((b) => b.addEventListener("click", () => setTheme(b.dataset.themeSet, true)));
setLang(document.documentElement.lang === "fr" ? "fr" : "en", false);
setTheme(document.documentElement.dataset.themeMode || "auto", false);
