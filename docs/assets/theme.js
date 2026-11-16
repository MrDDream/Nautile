// Theme and language before the first paint. Language: the saved choice, else English.
// Theme: the saved choice (light, dark or auto), else auto, which follows the system.
(function () {
  var m = null, l = null;
  try { m = localStorage.getItem("nautile-theme"); l = localStorage.getItem("nautile-lang"); } catch (e) {}
  if (m !== "light" && m !== "dark") m = "auto";
  var d = document.documentElement;
  d.dataset.themeMode = m;
  d.dataset.theme = m === "auto" ? (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark") : m;
  d.lang = l === "fr" ? "fr" : "en";
})();
