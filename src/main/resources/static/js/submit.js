/* 学生投稿与公告栏 */
(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const form = $("#submitForm");
  const content = $("#content");
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
    class_name: v => v.length >= 2 ? "" : "请填写班级",
    content: v => !v ? "投稿内容不能为空" : v.length > 100 ? "投稿内容不得超过 100 字" : "",
    contact: () => ""
  };
  Object.keys(rules).forEach(name => form.elements[name].addEventListener("input", () => markField(name, rules[name](value(name)))));
  content.addEventListener("input", () => {
    $("#contentCounter").textContent = content.value.length + " / 100";
    $("#contentCounter").classList.toggle("over", content.value.length > 100);
  });
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
        const tag = document.createElement("span"); tag.className = "message-kind"; tag.textContent = item.kind;
        const title = document.createElement("h3"); title.textContent = item.title;
        const meta = document.createElement("p"); meta.className = "announcement-meta"; meta.textContent = (item.published_at || item.created_at).slice(0, 10) + " · " + (item.publisher || "新媒体中心");
        const text = document.createElement("p"); text.className = "announcement-content"; text.textContent = item.content;
        card.append(tag, title, meta, text);
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
      const payload = { author: value("author"), class_name: value("class_name"), content: value("content"), contact: value("contact") };
      const data = await api("/api/submissions", { method: "POST", body: JSON.stringify(payload) });
      $("#submissionCode").textContent = data.code; form.hidden = true; $("#successPanel").classList.add("show"); alert.classList.remove("show");
    } catch (error) { showError(error.message); }
    finally { submit.disabled = false; $("#submitLabel").textContent = "提交稿件"; }
  });
  $("#againBtn").addEventListener("click", () => {
    form.reset(); content.value = ""; $("#contentCounter").textContent = "0 / 100";
    Object.keys(rules).forEach(name => markField(name, "")); alert.classList.remove("show"); $("#successPanel").classList.remove("show"); form.hidden = false;
  });
  $("#announcementRetry").addEventListener("click", loadAnnouncements);
  if (location.protocol === "file:") { showError("请按照 README.md 启动项目，并通过 http://127.0.0.1:8000/ 打开投稿页面。"); return; }
  submit.disabled = false; loadAnnouncements();
})();
