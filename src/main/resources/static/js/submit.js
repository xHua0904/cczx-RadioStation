/* 学生点播与公告栏 */
(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const form = $("#submitForm");
  const message = $("#message"), reason = $("#reason");
  const alert = $("#formAlert");
  const submit = $("#submitBtn");

  async function api(path, options = {}) {
    const res = await fetch(path, { ...options, headers: { "Content-Type": "application/json", ...(options.headers || {}) } });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "请求失败");
    return data;
  }
  function showError(message) { alert.textContent = message; alert.classList.add("show"); }
  function value(name) { return form.elements[name].value.trim(); }
  function markField(name, message) {
    const box = $('[data-field="' + name + '"]', form);
    box.classList.toggle("invalid", !!message);
    let tip = box.querySelector(".field-error");
    if (message) {
      if (!tip) { tip = document.createElement("p"); tip.className = "field-error"; box.appendChild(tip); }
      tip.textContent = message;
    } else if (tip) tip.remove();
  }
  const rules = {
    author: v => v.length >= 2 ? "" : "请填写姓名（至少 2 个字）",
    class_name: v => /^高(?:一(?:[1-9]|1[01])|二[1-9])班$/.test(v) ? "" : "请选择班级（高一 1–11 班、高二 1–9 班）",
    song_name: v => !v ? "请填写点播歌曲名称" : v.length > 80 ? "歌曲名称最多 80 字" : "",
    artist: v => !v ? "请填写歌手" : v.length > 80 ? "歌手名称最多 80 字" : "",
    message: v => v.length > 100 ? "简短寄语最多 100 字" : "",
    reason: v => v.length > 300 ? "点播理由最多 300 字" : "",
    contact: () => ""
  };
  Object.keys(rules).forEach(name => form.elements[name].addEventListener("input", () => markField(name, rules[name](value(name)))));
  function updatePreview() { $("#previewSong").textContent = value("song_name") || "歌曲名称"; $("#previewArtist").textContent = value("artist") || "歌手"; }
  for (const name of ["song_name", "artist"]) form.elements[name].addEventListener("input", updatePreview);
  function updateCounter(name, limit) { const counter = $("#" + name + "Counter"); counter.textContent = form.elements[name].value.length + " / " + limit; counter.classList.toggle("over", form.elements[name].value.length > limit); }
  message.addEventListener("input", () => updateCounter("message", 100));
  reason.addEventListener("input", () => updateCounter("reason", 300));
  async function loadAnnouncements() {
    const feed = $("#announcementFeed");
    $("#announcementRetry").hidden = true;
    try {
      const data = await api("/api/announcements");
      const items = data.items || [];
      $("#announcementCount").textContent = items.length + " 条";
      feed.replaceChildren();
      if (!items.length) {
        const empty = document.createElement("p"); empty.className = "empty-state"; empty.textContent = "暂无公告，新消息发布后会显示在这里。"; feed.appendChild(empty);
      }
      items.forEach(item => {
        const card = document.createElement("article"); card.className = "announcement-card";
        const title = document.createElement("h3"); title.textContent = item.title;
        const meta = document.createElement("p"); meta.className = "announcement-meta"; meta.textContent = (item.published_at || item.created_at).slice(0, 10) + " · 发布者：" + (item.publisher || "新媒体中心");
        const text = document.createElement("p"); text.className = "announcement-content"; text.textContent = item.content;
        card.append(title, meta, text);
        feed.appendChild(card);
      });
    } catch (error) { feed.replaceChildren(); const tip = document.createElement("p"); tip.className = "empty-state"; tip.textContent = "公告加载失败，请稍后重试。"; feed.appendChild(tip); $("#announcementRetry").hidden = false; }
  }
  form.addEventListener("submit", async event => {
    event.preventDefault();
    let first = null;
    Object.entries(rules).forEach(([name, rule]) => { const error = rule(value(name)); markField(name, error); if (error && !first) first = name; });
    if (first) { showError("请检查标红的内容。"); form.elements[first].focus(); return; }
    submit.disabled = true; $("#submitLabel").textContent = "提交中…";
    try {
      const payload = { author: value("author"), class_name: value("class_name"), song_name: value("song_name"), artist: value("artist"), message: value("message"), reason: value("reason"), contact: value("contact") };
      const data = await api("/api/submissions", { method: "POST", body: JSON.stringify(payload) });
      $("#submissionCode").textContent = data.code; form.hidden = true; $("#successPanel").classList.add("show"); alert.classList.remove("show");
    } catch (error) { showError(error.message); }
    finally { submit.disabled = false; $("#submitLabel").textContent = "提交点播"; }
  });
  $("#againBtn").addEventListener("click", () => {
    form.reset(); updateCounter("message", 100); updateCounter("reason", 300); updatePreview();
    Object.keys(rules).forEach(name => markField(name, "")); alert.classList.remove("show"); $("#successPanel").classList.remove("show"); form.hidden = false;
  });
  $("#announcementRetry").addEventListener("click", loadAnnouncements);
  if (location.protocol === "file:") { showError("请按照 README.md 启动项目，并通过 http://127.0.0.1/ 打开投稿页面。"); return; }
  submit.disabled = false; loadAnnouncements();
})();
