// Theme and language before the first paint: saved choice, else the system's.
(function () {
  var t = null, l = null;
  try { t = localStorage.getItem("nautile-theme"); l = localStorage.getItem("nautile-lang"); } catch (e) {}
  if (!t) t = matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  document.documentElement.dataset.theme = t;
  document.documentElement.lang = l || ((navigator.language || "en").toLowerCase().startsWith("fr") ? "fr" : "en");
})();
