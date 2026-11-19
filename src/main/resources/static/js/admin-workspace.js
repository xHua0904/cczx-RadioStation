/* 新媒体中心：控制台、审批、已审批稿件、消息发布 */
(() => {
  "use strict";
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => [...root.querySelectorAll(s)];
  const todayKey = () => new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date());
  const state = { token: sessionStorage.getItem("gb_token") || "", view: "dashboard", items: [], selected: new Set(), page: 1, pages: 1, total: 0, pending: 0, busy: false, requestId: 0, approvedView: "list", schedule: [], calendarDate: todayKey(), calendarMonth: todayKey().slice(0, 7) };
  const names = { dashboard: "控制台", review: "稿件审批", approved: "已审批稿件", messages: "消息发布" };
  const esc = value => String(value ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
  const statusClass = status => ({ "待审核": "st-pending", "已通过": "st-passed", "已播出": "st-aired", "已退稿": "st-rejected" }[status] || "st-pending");
  function toast(message, error = false) {
    const node = document.createElement("div"); node.className = "toast " + (error ? "err" : "ok"); node.textContent = message; $("#toasts").appendChild(node); setTimeout(() => node.remove(), 4500);
  }
  function showError(message) { $("#workspaceError").textContent = message; $("#workspaceError").hidden = !message; }
  function setAccount(username) {
    state.username = username; $("#currentAccount").textContent = "当前账号：" + username;
    $("#messagePublisher").textContent = username; $("#username").value = username;
  }
  function passwordValid(value) { return value.length >= 10 && value.length <= 64 && /\p{L}/u.test(value) && /\p{N}/u.test(value) && value !== "cczx123456" && !/[\x00-\x1f\x7f]/.test(value); }
  function requirePasswordChange(data, oldPassword = "") {
    if (data.username) setAccount(data.username);
    $("#dashView").hidden = $("#loginView").hidden = true; $("#changeRequiredView").hidden = false;
    $("#initialAccount").textContent = "账号：" + state.username; $("#initialPasswordForm").reset(); $("#initialOldPassword").value = oldPassword; $("#initialPasswordError").textContent = "";
  }
  async function enterSession(data, oldPassword = "") {
    setAccount(data.username);
    if (data.initial_password) { requirePasswordChange(data, oldPassword); return; }
    $("#changeRequiredView").hidden = $("#loginView").hidden = true; $("#dashView").hidden = false;
    await showView("dashboard");
  }
  function operatorMeta(row) {
    return [["审稿人", row.reviewed_by], ["排期人", row.scheduled_by], ["最近操作人", row.updated_by]]
      .filter(([, name]) => name).map(([label, name]) => esc(label) + "：" + esc(name)).join("<br>");
  }
  async function loadAudit() {
    const data = await api("/api/admin/audit?page=" + (state.auditPage || 1));
    state.auditPage = data.page; state.auditPages = data.pages;
    $("#auditHistory").innerHTML = data.items.length ? data.items.map(item => `<article class="audit-entry"><p><strong>${esc(item.username)}</strong> · ${esc(item.action)}</p><span>${esc(item.target_label)} · ${esc(item.created_at.replace("T", " "))}</span></article>`).join("") : '<p class="empty-state">暂无操作记录</p>';
    $("#auditPageInfo").textContent = "第 " + data.page + " / " + data.pages + " 页";
    $("#auditPrevious").disabled = data.page <= 1; $("#auditNext").disabled = data.page >= data.pages;
  }
  function loginView(message = "") {
    state.token = ""; state.username = ""; sessionStorage.removeItem("gb_token"); $("#currentAccount").textContent = ""; $("#messagePublisher").textContent = ""; $("#dashView").hidden = $("#changeRequiredView").hidden = true; $("#initialPasswordForm").reset(); $("#loginView").hidden = false; $("#loginError").textContent = message;
  }
  async function api(path, options = {}) {
    const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json", ...(state.token ? { Authorization: "Bearer " + state.token } : {}), ...(options.headers || {}) } });
    const data = await response.json();
    if (!response.ok) { if (response.status === 401 && path !== "/api/admin/login") loginView("登录已过期，请重新登录。"); if (data.code === "PASSWORD_CHANGE_REQUIRED") requirePasswordChange({}); throw new Error(data.error || "请求失败"); }
    return data;
  }
  const post = (path, body) => api(path, { method: "POST", body: JSON.stringify(body) });
  async function refreshStats() {
    const data = await api("/api/admin/stats"); state.pending = Number(data.pending); $("#pendingBadge").textContent = data.pending;
    const cards = [["投稿总数", data.total, "本周新增 " + data.this_week + " 篇"], ["待审批", data.pending, "等待新媒体中心审核"], ["采用稿件", data.adopted, "采用率 " + data.adoption_rate + "%"], ["已播出", data.broadcast, "已完成的校园播出"]];
    $("#statGrid").innerHTML = cards.map(([label, count, note], i) => `<article class="overview-stat ${i === 1 ? "accent" : ""}"><p>${esc(label)}</p><strong>${count}</strong><small>${esc(note)}</small></article>`).join("");
    const weeks = data.weekly || [], max = Math.max(1, ...weeks.map(w => w.count));
    $("#weeklyChart").innerHTML = weeks.map(w => `<div class="week-bar"><b>${w.count}</b><i style="height:${Math.max(3, Math.round(w.count / max * 125))}px"></i><span>${esc(w.label)}</span></div>`).join("");
    $("#classRank").innerHTML = data.by_class?.length ? data.by_class.map((c, i) => `<div class="rank-row"><span>${i + 1}</span><strong>${esc(c.name)}</strong><b>${c.count} 篇</b></div>`).join("") : '<p class="empty-state">暂无投稿数据</p>';
    updateSelection();
  }
  async function loadServer() {
    const data = await api("/api/admin/server"); const seconds = data.uptime_seconds;
    const metrics = [["服务状态", "正常运行"], ["服务器 IP", (data.server_ips || [data.server_ip]).filter(Boolean).join("、") || "暂无可用地址"], ["运行时长", Math.floor(seconds / 3600) + " 小时 " + Math.floor(seconds % 3600 / 60) + " 分钟"], ["数据库", data.storage], ["连接耗时", Number(data.database_latency_ms).toFixed(1) + " ms"], ["JVM 版本", data.java_version], ["内存占用", data.memory_used_mb + " / " + data.memory_max_mb + " MB"], ["处理器", data.processors + " 核"], ["服务器时间", data.time.replace("T", " ").slice(0, 19)]];
    $("#serverData").innerHTML = metrics.map(([name, value]) => `<div><dt>${esc(name)}</dt><dd>${esc(value)}</dd></div>`).join(""); $("#serverDot").classList.add("online"); $("#serverLabel").textContent = "服务器运行正常"; $("#serverStatusBadge").className = "badge st-aired"; $("#serverStatusBadge").textContent = "运行中";
  }
  function scheduleMarkup(items) {
    return items.length ? items.map(row => `<div class="schedule-entry"><time>${esc(row.broadcast_date)} ${esc(row.broadcast_time || "")}</time><div class="schedule-copy"><p>${esc(row.content)}</p><small>${esc(row.author)} · ${esc(row.class_name)}</small></div><span class="badge ${statusClass(row.status)}">${esc(row.status)}</span></div>`).join("") : '<p class="empty-state">暂无播出日程</p>';
  }
  async function loadSchedule() {
    const data = await api("/api/admin/schedule"), items = data.items || [];
    state.schedule = items; renderCalendar();
    $("#upcomingSchedule").innerHTML = scheduleMarkup(items.filter(row => row.status === "已通过" && row.broadcast_date >= todayKey()).slice(0, 6));
  }
  // 使用本地中午构造日历日期，避免 UTC 转换导致日期偏移。
  function calendarDate(year, month, day) { const date = new Date(0); date.setFullYear(year, month, day); date.setHours(12, 0, 0, 0); return date; }
  function dateKey(date) { return String(date.getFullYear()).padStart(4, "0") + "-" + String(date.getMonth() + 1).padStart(2, "0") + "-" + String(date.getDate()).padStart(2, "0"); }
  function renderCalendar() {
    const [year, month] = state.calendarMonth.split("-").map(Number), first = calendarDate(year, month - 1, 1);
    $("#calendarMonthLabel").textContent = year + " 年 " + month + " 月"; $("#calendarMonth").value = state.calendarMonth;
    const counts = new Map(); for (const row of state.schedule) counts.set(row.broadcast_date, (counts.get(row.broadcast_date) || 0) + 1);
    const offset = (first.getDay() + 6) % 7, days = calendarDate(year, month, 0).getDate(), cells = Math.ceil((offset + days) / 7) * 7;
    $("#calendarDays").innerHTML = Array.from({ length: cells }, (_, index) => {
      const date = calendarDate(year, month - 1, index - offset + 1), key = dateKey(date), count = counts.get(key) || 0;
      const outside = key.slice(0, 7) !== state.calendarMonth, selected = key === state.calendarDate, today = key === todayKey();
      return `<button type="button" class="calendar-day${outside ? " outside" : ""}${selected ? " selected" : ""}${today ? " today" : ""}" data-calendar-date="${key}" aria-pressed="${selected}" aria-label="${key}，${count} 篇稿件"><span>${date.getDate()}</span>${count ? `<small>${count} 篇</small>` : ""}${today ? '<i aria-hidden="true"></i>' : ""}</button>`;
    }).join("");
    const items = state.schedule.filter(row => row.broadcast_date === state.calendarDate).sort((a, b) => (a.broadcast_time || "").localeCompare(b.broadcast_time || "") || a.id - b.id);
    $("#calendarDayHeading").textContent = state.calendarDate + " 的稿件"; $("#calendarDayCount").textContent = items.length + " 篇";
    $("#calendarDayItems").innerHTML = items.length ? items.map(row => `<article class="day-manuscript" data-manuscript-id="${row.id}"><div class="day-manuscript-heading"><time>${esc(row.broadcast_time || "未设置时间")}</time><span class="badge ${statusClass(row.status)}">${esc(row.status)}</span></div><p class="day-manuscript-content">${esc(row.content)}</p><p class="manuscript-meta">${esc(row.author)} · ${esc(row.class_name)}<br>${esc(row.code)}</p><p class="operator-meta">${operatorMeta(row)}</p>${row.note ? `<p class="day-manuscript-note">备注：${esc(row.note)}</p>` : ""}<div class="day-manuscript-actions"><label for="calendarStatus${row.id}">播出状态</label><select class="select" id="calendarStatus${row.id}" data-calendar-status="${row.id}" ${state.busy ? "disabled" : ""}><option value="已通过" ${row.status === "已通过" ? "selected" : ""}>待播出</option><option value="已播出" ${row.status === "已播出" ? "selected" : ""}>已播出</option></select><button type="button" class="btn btn-ghost btn-sm" data-save-calendar-status="${row.id}" ${state.busy ? "disabled" : ""}>保存状态</button></div></article>`).join("") : '<p class="empty-state">当天没有安排播出的稿件。</p>';
  }
  function changeCalendarMonth(value) {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(value)) return;
    const [year, month] = value.split("-").map(Number), day = Math.min(Number(state.calendarDate.slice(-2)), calendarDate(year, month, 0).getDate());
    state.calendarMonth = value; state.calendarDate = dateKey(calendarDate(year, month - 1, day)); renderCalendar();
  }
  function approvedView(view) {
    if (!["list", "scheduled", "calendar"].includes(view)) return;
    state.approvedView = view;
    const calendar = state.view === "approved" && view === "calendar";
    $("#approvedTabs").hidden = state.view !== "approved"; $("#manuscriptListPanel").hidden = calendar; $("#calendarPanel").hidden = !calendar;
    $$("[data-approved-view]").forEach(button => { const active = button.dataset.approvedView === view; button.classList.toggle("active", active); button.setAttribute("aria-selected", String(active)); });
    if (state.view === "approved") {
      $("#manuscriptDescription").textContent = calendar ? "选择日历日期，查看当天稿件并切换播出状态。" : view === "scheduled" ? "管理已安排播出日期的稿件，调整日程或更新播出状态。" : "管理尚未排期的已审批稿件，选择稿件添加到日程。";
      $("#manuscriptListPanel").setAttribute("role", "tabpanel"); $("#manuscriptListPanel").setAttribute("aria-labelledby", view === "scheduled" ? "approvedScheduledTab" : "approvedListTab");
      $("#scheduleSelected").textContent = view === "scheduled" ? "调整播出日程" : "添加到日程";
    } else { $("#manuscriptListPanel").removeAttribute("role"); $("#manuscriptListPanel").removeAttribute("aria-labelledby"); }
    if (calendar) renderCalendar();
  }
  async function switchApprovedView(view) {
    if (state.view !== "approved" || !["list", "scheduled", "calendar"].includes(view)) return;
    clearTimeout(searchTimer); state.requestId++; state.page = 1; state.pages = 1; state.total = 0; state.items = []; state.selected.clear();
    $("#filterQ").value = $("#filterStatus").value = ""; showError(""); approvedView(view); selectionChanged();
    try { if (view === "calendar") await loadSchedule(); else await loadList(); } catch (error) { showError(error.message); }
  }
  async function loadDashboard() {
    const results = await Promise.allSettled([refreshStats(), loadServer(), loadSchedule(), loadAudit()]);
    const failure = results.find(result => result.status === "rejected"); if (failure) showError(failure.reason.message);
  }
  function filters() {
    const params = new URLSearchParams({ phase: state.view === "review" ? "pending" : "reviewed", page: String(state.page) });
    if (state.view === "approved") params.set("schedule", state.approvedView === "list" ? "unscheduled" : "scheduled");
    if ($("#filterQ").value.trim()) params.set("q", $("#filterQ").value.trim());
    if (state.view === "approved" && $("#filterStatus").value) params.set("status", $("#filterStatus").value);
    return params;
  }
  async function loadList() {
    const requestId = ++state.requestId, view = state.view, approved = state.approvedView;
    const data = await api("/api/admin/submissions?" + filters());
    if (requestId !== state.requestId || view !== state.view || approved !== state.approvedView) return;
    state.pages = Number(data.pages); if (state.page > state.pages) { state.page = state.pages; return loadList(); }
    state.items = data.items || []; state.total = Number(data.total); state.selected.clear();
    renderList(); updateSelection();
  }
  function renderList() {
    $("#manuscriptBody").innerHTML = state.items.length ? state.items.map(row => `<tr data-id="${row.id}" class="${state.selected.has(row.id) ? "selected" : ""}"><td class="checkbox-cell"><input type="checkbox" data-select="${row.id}" ${state.selected.has(row.id) ? "checked" : ""} aria-label="选择稿件 ${esc(row.code)}"></td><td><p class="manuscript-copy">${esc(row.content)}</p><p class="manuscript-meta">${esc(row.author)} · ${esc(row.class_name)}<br>${esc(row.code)} · ${esc(row.created_at.replace("T", " ").slice(0, 16))}${row.broadcast_date ? `<br>播出：${esc(row.broadcast_date)} ${esc(row.broadcast_time)}` : ""}</p></td><td><span class="badge ${statusClass(row.status)}">${esc(row.status)}</span><p class="operator-meta">${operatorMeta(row)}</p></td></tr>`).join("") : '<tr><td colspan="3" class="empty-state">暂无符合条件的稿件</td></tr>';
    $("#listCount").textContent = "共 " + state.total + " 篇"; $("#pagerInfo").textContent = "第 " + state.page + " / " + state.pages + " 页";
    $("#pagerBtns").innerHTML = `<button data-page="prev" ${state.page === 1 ? "disabled" : ""}>上一页</button><button data-page="next" ${state.page >= state.pages ? "disabled" : ""}>下一页</button>`;
    $("#selectPage").disabled = !state.items.length; $("#selectPage").checked = !!state.items.length && state.selected.size === state.items.length; $("#selectPage").indeterminate = !!state.selected.size && state.selected.size < state.items.length;
  }
  function updateSelection() {
    const selected = state.items.filter(row => state.selected.has(row.id)), single = selected.length === 1, active = selected.length && !state.busy;
    $("#selectionCount").textContent = "已选 " + selected.length + " 篇"; $("#selectionEmpty").hidden = !!selected.length; $("#selectedDetails").hidden = !selected.length;
    $("#selectedDetails").innerHTML = single ? `<p>${esc(selected[0].content)}</p><small>${esc(selected[0].code)} · ${esc(selected[0].author)}<br>${esc(selected[0].class_name)}<br>联系方式：${esc(selected[0].contact || "未填写")}</small><p class="operator-meta">${operatorMeta(selected[0])}</p>` : selected.map(row => `<p>${esc(row.code)} · ${esc(row.author)}</p>`).join("");
    $("#approveSelected").disabled = $("#rejectSelected").disabled = !active;
    $("#scheduleSelected").disabled = $("#markPlayed").disabled = !active || !selected.every(row => row.status === "已通过");
    $("#clearSchedule").disabled = !active || !selected.every(row => row.broadcast_date && ["已通过", "已播出"].includes(row.status));
    $("#saveSelected").disabled = !single || state.busy; $("#deleteSelected").disabled = !single || state.busy;
    $("#reopenSelected").disabled = !active;
    $("#approveAllButton").disabled = state.busy || state.pending === 0;
    $$("[data-calendar-status], [data-save-calendar-status]").forEach(control => { control.disabled = state.busy; });
  }
  function selectionChanged() {
    const selected = state.items.filter(row => state.selected.has(row.id));
    $("#operationNote").value = selected.length === 1 ? selected[0].note : "";
    $("#operationScore").value = selected.length === 1 ? selected[0].score ?? "" : "";
    $("#scheduleDate").value = selected.length === 1 ? selected[0].broadcast_date : "";
    $("#scheduleTime").value = selected.length === 1 ? selected[0].broadcast_time || "12:00" : "12:00";
    renderList(); updateSelection();
  }
  async function showView(view) {
    if (!names[view]) return; clearTimeout(searchTimer); state.view = view; state.page = 1; state.selected.clear(); state.items = []; state.requestId++;
    showError(""); $("#activeViewName").textContent = names[view]; $$(".nav-item").forEach(button => { const active = button.dataset.view === view; button.classList.toggle("active", active); button.setAttribute("aria-current", active ? "page" : "false"); });
    $("#dashboardPanel").hidden = view !== "dashboard"; $("#manuscriptPanel").hidden = !["review", "approved"].includes(view); $("#messagesPanel").hidden = view !== "messages";
    try {
      if (view === "dashboard") await loadDashboard();
      else if (view === "messages") await loadMessages();
      else {
        $("#manuscriptTitle").textContent = names[view]; $("#manuscriptDescription").textContent = view === "review" ? "未审批稿件按列表展示，选中后在右侧操作。" : "管理已审批稿件，安排播出日程或更新播出状态。";
        $("#approveAllButton").hidden = $("#reviewActions").hidden = view !== "review"; $("#approvedActions").hidden = $("#filterStatus").hidden = view !== "approved";
        approvedView(state.approvedView);
        $("#filterQ").value = $("#filterStatus").value = ""; selectionChanged();
        await Promise.all([loadList(), refreshStats(), loadSchedule()]);
      }
    } catch (error) { showError(error.message); }
  }
  async function refreshAfterAction() { await Promise.all([loadList(), refreshStats(), loadSchedule()]); }
  async function saveCalendarStatus(id) {
    if (state.busy) return;
    const row = state.schedule.find(item => item.id === id && item.broadcast_date === state.calendarDate), control = $("#calendarStatus" + id);
    if (!row || !control) return;
    const status = control.value;
    if (!["已通过", "已播出"].includes(status)) return;
    if (status === row.status) return toast("稿件状态未改变。");
    state.busy = true; updateSelection();
    try {
      // 只更新选中稿件的状态，保留播出日期、时间和备注。
      await api("/api/admin/submissions/" + id, { method: "PATCH", body: JSON.stringify({ status }) });
      toast("稿件已更新为" + (status === "已通过" ? "待播出" : "已播出")); await refreshAfterAction();
    } catch (error) { toast(error.message, true); await loadSchedule().catch(() => {}); }
    finally { state.busy = false; updateSelection(); }
  }
  function confirm(title, message, accept = "确认操作", risk = false) {
    const dialog = $("#confirmDialog"); if (dialog.open) return Promise.resolve(false);
    $("#confirmTitle").textContent = title; $("#confirmMessage").textContent = message; $("#confirmLabel").textContent = risk ? "风险操作" : "操作确认"; $("#confirmAccept").textContent = accept; dialog.returnValue = "";
    return new Promise(resolve => { dialog.addEventListener("close", () => resolve(dialog.returnValue === "confirm"), { once: true }); dialog.showModal(); });
  }
  async function action(kind) {
    if (state.busy || !state.selected.size) return;
    const payload = { ids: [...state.selected], action: kind, note: $("#operationNote").value.trim() };
    if (kind === "schedule") { if (!$("#scheduleDate").value) return toast("请选择播出日期。", true); payload.broadcast_date = $("#scheduleDate").value; payload.broadcast_time = $("#scheduleTime").value; }
    state.busy = true; updateSelection();
    try { const data = await post("/api/admin/submissions/batch", payload); if (kind === "schedule") { state.calendarDate = payload.broadcast_date; state.calendarMonth = payload.broadcast_date.slice(0, 7); } toast("已处理 " + data.updated + " 篇稿件"); await refreshAfterAction(); }
    catch (error) { toast(error.message, true); await loadList().catch(() => {}); }
    finally { state.busy = false; updateSelection(); }
  }
  async function approveAll() {
    if (state.busy) return; state.busy = true; updateSelection();
    try {
      const summary = await api("/api/admin/review-summary"); if (!summary.count) return toast("当前没有待审核稿件。");
      const accepted = await confirm("全部审核", "将直接通过当前全部 " + summary.count + " 篇未审批稿件（包括其他分页）。\n\n未逐篇检查可能将不合适的内容加入已审批列表，请确认已完成内容核对。确认后新收到的投稿仍会保留待审批状态。", "确认全部通过", true);
      if (!accepted) return;
      const data = await post("/api/admin/submissions/approve-all", { ids: summary.ids, confirmed: true });
      toast("已通过 " + data.updated + " 篇，还有 " + data.remaining + " 篇待审批"); await refreshAfterAction();
    } catch (error) { toast(error.message, true); await loadList().catch(() => {}); }
    finally { state.busy = false; updateSelection(); }
  }
  async function saveSingle() {
    if (state.selected.size !== 1 || state.busy) return;
    const score = $("#operationScore").value, number = score === "" ? null : Number(score);
    if (number !== null && (!Number.isInteger(number) || number < 0 || number > 100)) return toast("评分须为 0–100 的整数。", true);
    try { await api("/api/admin/submissions/" + [...state.selected][0], { method: "PATCH", body: JSON.stringify({ score: number, note: $("#operationNote").value.trim() }) }); toast("评分和备注已保存"); await refreshAfterAction(); } catch (error) { toast(error.message, true); }
  }
  async function deleteSingle() {
    if (state.selected.size !== 1) return; const id = [...state.selected][0];
    if (!await confirm("删除稿件", "确定删除选中的这篇稿件吗？删除后无法恢复。", "确认删除", true)) return;
    try { await api("/api/admin/submissions/" + id, { method: "DELETE" }); toast("稿件已删除"); await refreshAfterAction(); } catch (error) { toast(error.message, true); }
  }
  async function loadMessages() {
    const data = await api("/api/admin/announcements"), items = data.items || [];
    $("#messageCount").textContent = items.length + " 条";
    $("#messageHistory").innerHTML = items.length ? items.map(item => `<article class="message-history-item ${item.published ? "" : "withdrawn"}"><span class="message-kind">${esc(item.kind)}${item.published ? "" : " · 已撤回"}</span><h3>${esc(item.title)}</h3><p class="announcement-meta">${esc(item.published_at.slice(0, 10))} · 发布者：${esc(item.publisher)}${item.updated_by ? `<br>最近操作人：${esc(item.updated_by)} · ${esc(item.updated_at.replace("T", " "))}` : ""}</p><p class="announcement-content">${esc(item.content)}</p><div class="message-operations"><button class="text-button" data-message-id="${item.id}" data-published="${!item.published}">${item.published ? "撤回消息" : "恢复发布"}</button><button class="text-button danger-text" data-delete-message="${item.id}">删除</button></div></article>`).join("") : '<p class="empty-state">暂无消息，发布后会显示在投稿页公告栏。</p>';
  }
  async function exportFile(format) {
    try {
      const params = filters(); params.delete("page"); params.set("format", format);
      const response = await fetch("/api/admin/export?" + params, { headers: { Authorization: "Bearer " + state.token } });
      if (!response.ok) { const error = await response.json(); throw new Error(error.error || "导出失败"); }
      const blob = await response.blob(), url = URL.createObjectURL(blob), link = document.createElement("a"); link.href = url; link.download = "稿件导出." + format; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { toast(error.message, true); }
  }
  $("#loginForm").addEventListener("submit", async event => {
    event.preventDefault(); $("#loginBtn").disabled = true;
    try { const old = $("#password").value; const data = await post("/api/admin/login", { username: $("#username").value.trim(), password: old }); state.token = data.token; sessionStorage.setItem("gb_token", data.token); $("#password").value = ""; await enterSession(data, old); }
    catch (error) { $("#loginError").textContent = error.message; if (state.token) showError(error.message); }
    finally { $("#loginBtn").disabled = false; }
  });
  $$("[data-view]").forEach(button => button.addEventListener("click", () => showView(button.dataset.view)));
  $$("[data-approved-view]").forEach(button => button.addEventListener("click", () => switchApprovedView(button.dataset.approvedView)));
  $("#calendarDayItems").addEventListener("click", event => { const button = event.target.closest("[data-save-calendar-status]"); if (button && !button.disabled) saveCalendarStatus(Number(button.dataset.saveCalendarStatus)); });
  $("#openBroadcastCalendar").addEventListener("click", () => { state.approvedView = "calendar"; showView("approved"); });
  $("#calendarDays").addEventListener("click", event => { const button = event.target.closest("[data-calendar-date]"); if (button) { state.calendarDate = button.dataset.calendarDate; state.calendarMonth = state.calendarDate.slice(0, 7); renderCalendar(); } });
  $("#calendarMonth").addEventListener("change", event => { changeCalendarMonth(event.target.value); });
  for (const [id, step] of [["#calendarPrev", -1], ["#calendarNext", 1]]) $(id).addEventListener("click", () => { const [year, month] = state.calendarMonth.split("-").map(Number); changeCalendarMonth(dateKey(calendarDate(year, month - 1 + step, 1)).slice(0, 7)); });
  $("#calendarToday").addEventListener("click", () => { state.calendarDate = todayKey(); state.calendarMonth = state.calendarDate.slice(0, 7); renderCalendar(); });
  $("#refreshDashboard").addEventListener("click", () => { showError(""); loadDashboard(); });
  for (const [id, step] of [["#auditPrevious", -1], ["#auditNext", 1]]) $(id).addEventListener("click", () => { state.auditPage = Math.max(1, Math.min(state.auditPages || 1, (state.auditPage || 1) + step)); loadAudit().catch(error => showError(error.message)); });
  $("#selectPage").addEventListener("change", event => { state.selected = new Set(event.target.checked ? state.items.map(row => row.id) : []); selectionChanged(); });
  $("#manuscriptBody").addEventListener("change", event => { if (event.target.matches("[data-select]")) { const id = Number(event.target.dataset.select); if (event.target.checked) state.selected.add(id); else state.selected.delete(id); selectionChanged(); } });
  $("#manuscriptBody").addEventListener("click", event => { if (event.target.closest("input,button,a")) return; const row = event.target.closest("tr[data-id]"); if (row) { const id = Number(row.dataset.id); if (state.selected.has(id)) state.selected.delete(id); else state.selected.add(id); selectionChanged(); } });
  $("#pagerBtns").addEventListener("click", event => { const button = event.target.closest("[data-page]"); if (!button || button.disabled) return; state.page += button.dataset.page === "next" ? 1 : -1; loadList().catch(error => showError(error.message)); });
  let searchTimer; $("#filterQ").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { state.page = 1; loadList().catch(error => showError(error.message)); }, 250); });
  $("#filterStatus").addEventListener("change", () => { state.page = 1; loadList().catch(error => showError(error.message)); });
  $("#resetFilters").addEventListener("click", () => { $("#filterQ").value = $("#filterStatus").value = ""; state.page = 1; loadList().catch(error => showError(error.message)); });
  for (const [id, kind] of [["#approveSelected", "approve"], ["#rejectSelected", "reject"], ["#scheduleSelected", "schedule"], ["#markPlayed", "played"], ["#clearSchedule", "clear_schedule"], ["#reopenSelected", "reopen"]]) $(id).addEventListener("click", () => action(kind));
  $("#approveAllButton").addEventListener("click", approveAll); $("#saveSelected").addEventListener("click", saveSingle); $("#deleteSelected").addEventListener("click", deleteSingle);
  $$("[data-export]").forEach(button => button.addEventListener("click", () => exportFile(button.dataset.export)));
  $("#newButton").addEventListener("click", () => { $("#newForm").reset(); $("#newDialog").showModal(); });
  $$("[data-close]").forEach(button => button.addEventListener("click", () => $("#" + button.dataset.close).close()));
  $("#newForm").addEventListener("submit", async event => {
    event.preventDefault(); const content = $("#newContent").value.trim(); if (!content || content.length > 100) return toast("投稿内容必须为 1–100 字。", true);
    $("#createManuscript").disabled = true;
    try { await post("/api/admin/submissions", { author: $("#newAuthor").value.trim(), class_name: $("#newClass").value.trim(), content, contact: $("#newContact").value.trim() }); $("#newDialog").close(); toast("已登记，等待审批"); await showView("review"); } catch (error) { toast(error.message, true); }
    finally { $("#createManuscript").disabled = false; }
  });
  $("#messageForm").addEventListener("submit", async event => {
    event.preventDefault(); const body = { title: $("#messageTitle").value.trim(), content: $("#messageContent").value.trim(), kind: $("#messageKind").value };
    if (!body.title || !body.content) return toast("请填写消息标题和内容。", true); $("#publishMessage").disabled = true;
    try { await post("/api/admin/announcements", body); $("#messageForm").reset(); toast("消息已发布到公告栏"); await loadMessages(); } catch (error) { toast(error.message, true); }
    finally { $("#publishMessage").disabled = false; }
  });
  $("#messageHistory").addEventListener("click", async event => {
    const visibility = event.target.closest("[data-message-id]"), deletion = event.target.closest("[data-delete-message]");
    try {
      if (visibility) { await api("/api/admin/announcements/" + visibility.dataset.messageId, { method: "PATCH", body: JSON.stringify({ published: visibility.dataset.published === "true" }) }); await loadMessages(); }
      if (deletion && await confirm("删除消息", "删除后公告栏将不再展示这条消息，且无法恢复。", "确认删除", true)) { await api("/api/admin/announcements/" + deletion.dataset.deleteMessage, { method: "DELETE" }); await loadMessages(); }
    } catch (error) { toast(error.message, true); }
  });
  $("#btnSettings").addEventListener("click", () => { $("#passwordForm").reset(); $("#settingsDialog").showModal(); });
  $("#passwordForm").addEventListener("submit", async event => {
    event.preventDefault(); const password = $("#newPassword").value; if (!passwordValid(password) || password !== $("#confirmPassword").value || password === $("#oldPassword").value) return toast("新密码至少 10 位且包含字母和数字，不能使用默认密码或原密码，两次输入须相同。", true);
    $("#changePassword").disabled = true;
    try { await post("/api/admin/password", { old_password: $("#oldPassword").value, new_password: password }); $("#settingsDialog").close(); loginView("密码已更新，请重新登录。项目说明中的默认密码保持不变。"); } catch (error) { toast(error.message, true); }
    finally { $("#changePassword").disabled = false; }
  });
  $("#btnLogout").addEventListener("click", async () => { try { await post("/api/admin/logout", {}); } catch (_) { /* 会话过期也返回登录页 */ } loginView(); });
  $("#initialLogout").addEventListener("click", async () => { try { await post("/api/admin/logout", {}); } catch (_) { /* 退出过期会话时仍返回登录页 */ } loginView(); });
  $("#initialPasswordForm").addEventListener("submit", async event => {
    event.preventDefault(); const old = $("#initialOldPassword").value, password = $("#initialNewPassword").value;
    if (!passwordValid(password) || password !== $("#initialConfirmPassword").value || password === old) { $("#initialPasswordError").textContent = "新密码至少 10 位且包含字母和数字，不能使用默认密码或原密码，两次输入须相同。"; return; }
    $("#initialPasswordSave").disabled = true;
    try { await post("/api/admin/password", { old_password: old, new_password: password }); loginView("密码已更新，请使用新密码重新登录。"); }
    catch (error) { $("#initialPasswordError").textContent = error.message; }
    finally { $("#initialPasswordSave").disabled = false; }
  });
  if (location.protocol === "file:") { loginView("请按照 README.md 启动项目，再通过 http://127.0.0.1:8000/admin 打开后台。"); return; }
  if (state.token) { api("/api/admin/me").then(data => enterSession(data)).catch(error => { if (state.token) loginView(error.message); }); }
  else loginView();
})();
