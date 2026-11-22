/* 学生点播与公告栏 */
(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const form = $("#submitForm");
  const message = $("#message"), reason = $("#reason");
  const alert = $("#formAlert");
  const submit = $("#submitBtn");
  const publicState = { query: "", progressPage: 1, progressPages: 1, progressRequest: 0, schedulePage: 1, schedulePages: 1, scheduleRequest: 0 };
  let duplicateTimer, duplicateRequest = 0;

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

  function clearDuplicate() {
    clearTimeout(duplicateTimer); duplicateRequest++;
    $("#songDuplicate").hidden = true; $("#songDuplicate").textContent = "";
  }
  async function checkDuplicate(song, requestId) {
    if (!song || song.length > 80) return;
    try {
      const data = await api("/api/submissions/song-exists?" + new URLSearchParams({ song_name: song }));
      if (requestId !== duplicateRequest || value("song_name") !== song) return;
      $("#songDuplicate").textContent = data.exists ? "本首歌已有同学提交" : "";
      $("#songDuplicate").hidden = !data.exists;
    } catch (_) {
      if (requestId !== duplicateRequest || value("song_name") !== song) return;
      $("#songDuplicate").textContent = "暂时无法检查相同歌曲，可正常提交。"; $("#songDuplicate").hidden = false;
    }
  }
  form.elements.song_name.addEventListener("input", () => {
    clearDuplicate(); const song = value("song_name"), requestId = duplicateRequest;
    if (song && song.length <= 80) duplicateTimer = setTimeout(() => checkDuplicate(song, requestId), 350);
  });
  form.elements.song_name.addEventListener("blur", () => { clearDuplicate(); return checkDuplicate(value("song_name"), duplicateRequest); });

  function textNode(tag, className, text) { const node = document.createElement(tag); node.className = className; node.textContent = text; return node; }
  function publicItem(item, progress) {
    const entry = document.createElement("article"); entry.className = "public-result-item";
    const heading = document.createElement("div"); heading.className = "public-result-heading";
    const badge = { "待审核": "st-pending", "已通过": "st-passed", "已退稿": "st-rejected", "已播出": "st-aired" }[item.status] || "st-pending";
    heading.append(textNode("h3", "public-song-title", item.song_name || "历史稿件"), textNode("span", "badge " + badge, !progress && item.status === "已通过" ? "待播出" : item.status));
    entry.appendChild(heading);
    if (item.artist) entry.appendChild(textNode("p", "song-artist", item.artist));
    if (progress) entry.appendChild(textNode("p", "public-result-meta", [item.code, item.class_name, (item.created_at || "").replace("T", " ").slice(0, 16)].filter(Boolean).join(" · ")));
    entry.appendChild(textNode("p", "public-result-time", item.broadcast_date ? item.broadcast_date + " " + (item.broadcast_time || "时间待定") : "尚未安排播出"));
    return entry;
  }
  function pager(prefix, page, pages) {
    $("#" + prefix + "Pager").hidden = pages <= 1;
    $("#" + prefix + "PageInfo").textContent = "第 " + page + " / " + pages + " 页";
    $("#" + prefix + "Previous").disabled = page <= 1; $("#" + prefix + "Next").disabled = page >= pages;
  }
  async function loadProgress(page = 1) {
    const query = publicState.query, requestId = ++publicState.progressRequest;
    $("#progressSubmit").disabled = true; $("#progressHint").textContent = "正在查询稿件进度…";
    try {
      const data = await api("/api/submissions/progress", { method: "POST", body: JSON.stringify({ query, page }) });
      if (requestId !== publicState.progressRequest) return;
      if (data.page > data.pages) return loadProgress(data.pages);
      publicState.progressPage = data.page; publicState.progressPages = data.pages;
      $("#progressHint").textContent = (data.input_type === "code" ? "编号查询" : "姓名查询") + " · 共 " + data.total + " 篇稿件";
      $("#progressResults").replaceChildren(...(data.items || []).map(item => publicItem(item, true)));
      if (!data.items?.length) $("#progressResults").appendChild(textNode("p", "public-empty", "没有找到匹配的稿件，请核对姓名或编号。"));
      pager("progress", data.page, data.pages);
    } catch (error) {
      if (requestId !== publicState.progressRequest) return;
      $("#progressHint").textContent = error.message; $("#progressResults").replaceChildren(); $("#progressPager").hidden = true;
    } finally { if (requestId === publicState.progressRequest) $("#progressSubmit").disabled = false; }
  }
  function startProgress() {
    const query = $("#progressQuery").value.trim();
    if (query.length < 2 || query.length > 40) { $("#progressHint").textContent = "请填写 2–40 个字符的姓名或稿件编号。"; return; }
    publicState.query = query; return loadProgress(1);
  }
  $("#progressForm").addEventListener("submit", event => { event.preventDefault(); return startProgress(); });
  $("#progressQuery").addEventListener("input", () => {
    publicState.progressRequest++; publicState.query = ""; $("#progressSubmit").disabled = false;
    $("#progressPager").hidden = true; $("#progressResults").replaceChildren();
    $("#progressHint").textContent = /^GB[0-9]+$/i.test($("#progressQuery").value.trim()) ? "已识别为稿件编号，点击查询查看进度" : "输入姓名或编号，点击查询查看进度";
  });
  $("#viewProgressBtn").addEventListener("click", () => {
    $("#progressQuery").value = $("#submissionCode").textContent; $("#progressHeading").scrollIntoView({ block: "start", behavior: "auto" }); return startProgress();
  });
  for (const [id, step] of [["#progressPrevious", -1], ["#progressNext", 1]]) $(id).addEventListener("click", () => loadProgress(Math.max(1, Math.min(publicState.progressPages, publicState.progressPage + step))));

  async function loadPublicSchedule(page = publicState.schedulePage) {
    const requestId = ++publicState.scheduleRequest; $("#refreshPublicSchedule").disabled = true;
    try {
      const data = await api("/api/schedule?page=" + page);
      if (requestId !== publicState.scheduleRequest) return;
      if (data.page > data.pages) return loadPublicSchedule(data.pages);
      publicState.schedulePage = data.page; publicState.schedulePages = data.pages;
      $("#publicSchedule").replaceChildren(...(data.items || []).map(item => publicItem(item, false)));
      if (!data.items?.length) $("#publicSchedule").appendChild(textNode("p", "public-empty", "暂时没有已安排的播放日程。"));
      pager("schedule", data.page, data.pages);
    } catch (error) {
      if (requestId !== publicState.scheduleRequest) return;
      $("#publicSchedule").replaceChildren(textNode("p", "public-empty", "播放日程加载失败：" + error.message)); $("#schedulePager").hidden = true;
    } finally { if (requestId === publicState.scheduleRequest) $("#refreshPublicSchedule").disabled = false; }
  }
  $("#refreshPublicSchedule").addEventListener("click", () => loadPublicSchedule());
  for (const [id, step] of [["#schedulePrevious", -1], ["#scheduleNext", 1]]) $(id).addEventListener("click", () => loadPublicSchedule(Math.max(1, Math.min(publicState.schedulePages, publicState.schedulePage + step))));
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
      clearDuplicate(); $("#submissionCode").textContent = data.code; form.hidden = true; $("#successPanel").classList.add("show"); alert.classList.remove("show");
    } catch (error) { showError(error.message); }
    finally { submit.disabled = false; $("#submitLabel").textContent = "提交点播"; }
  });
  $("#againBtn").addEventListener("click", () => {
    form.reset(); clearDuplicate(); updateCounter("message", 100); updateCounter("reason", 300); updatePreview();
    Object.keys(rules).forEach(name => markField(name, "")); alert.classList.remove("show"); $("#successPanel").classList.remove("show"); form.hidden = false;
  });
  $("#announcementRetry").addEventListener("click", loadAnnouncements);
  if (location.protocol === "file:") { showError("请按照 README.md 启动项目，并通过 http://127.0.0.1/ 打开投稿页面。"); return; }
  clearDuplicate(); submit.disabled = false; loadAnnouncements(); loadPublicSchedule();
})();
