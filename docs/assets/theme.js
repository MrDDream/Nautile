// Theme and language before the first paint: the saved choice, else dark and English.
(function () {
  var t = null, l = null;
  try { t = localStorage.getItem("nautile-theme"); l = localStorage.getItem("nautile-lang"); } catch (e) {}
  var d = document.documentElement;
  d.dataset.theme = t === "light" ? "light" : "dark";
  d.lang = l === "fr" ? "fr" : "en";
})();
