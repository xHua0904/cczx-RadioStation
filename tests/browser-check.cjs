/* 使用独立数据库和 Edge 无界面浏览器验证真实页面，测试产物统一由 cleanup.ps1 清理。 */
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { mkdir, writeFile } = require('node:fs/promises');
const path = require('node:path');
const net = require('node:net');

const root = path.resolve(__dirname, '..');
const run = path.join(root, 'target', 'test-runtime', 'browser-' + Date.now());
const profile = path.join(root, 'target', 'ui-check', 'edge-' + Date.now());
const edgePath = process.env.EDGE_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const testPassword = 'admin';
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let java, browser, cdp, base;
const browserErrors = [];
let approvePosts = 0;

async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function until(action, description, timeout = 15000) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    try { const result = await action(); if (result) return result; } catch (error) { last = error; }
    await delay(100);
  }
  throw new Error('等待超时：' + description + (last ? ' / ' + last.message : ''));
}

class CDP {
  constructor(input, output) {
    this.input = input; this.nextId = 0; this.pending = new Map(); this.buffer = ''; this.session = '';
    output.setEncoding('utf8');
    output.on('data', chunk => {
      this.buffer += chunk;
      while (this.buffer.includes('\0')) {
        const split = this.buffer.indexOf('\0');
        const packet = this.buffer.slice(0, split); this.buffer = this.buffer.slice(split + 1);
        if (packet) this.receive(JSON.parse(packet));
      }
    });
  }
  receive(message) {
      if (process.env.CDP_DEBUG) console.log('CDP message', message.id || message.method);
      if (message.id) {
        const pending = this.pending.get(message.id); if (!pending) return;
        this.pending.delete(message.id); clearTimeout(pending.timer);
        if (message.error) pending.reject(new Error(message.error.message)); else pending.resolve(message.result);
      } else if (message.method === 'Runtime.exceptionThrown') browserErrors.push(message.params.exceptionDetails.text);
      else if (message.method === 'Network.requestWillBeSent' && message.params.request.method === 'POST' && message.params.request.url.endsWith('/approve-all')) approvePosts++;
  }
  send(method, params = {}) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP 超时：' + method)); }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.input.write(JSON.stringify({ id, method, params, ...(this.session && !/^(Browser|Target)\./.test(method) ? { sessionId: this.session } : {}) }) + '\0');
    });
  }
  async eval(expression) {
    const result = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  }
}

async function api(url, method = 'GET', body, token) {
  const response = await fetch(base + url, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const data = await response.json();
  assert.equal(response.status, 200, url + ': ' + (data.error || response.status));
  return data;
}
const waitPage = expression => until(() => cdp.eval(expression), expression);
const click = selector => cdp.eval('document.querySelector(' + JSON.stringify(selector) + ').click()');
const fill = (selector, value) => cdp.eval('(() => { const node = document.querySelector(' + JSON.stringify(selector) + '); node.value = ' + JSON.stringify(value) + '; node.dispatchEvent(new Event("input", {bubbles:true})); node.dispatchEvent(new Event("change", {bubbles:true})); })()');
async function navigate(url, ready) {
  await cdp.send('Page.navigate', { url: base + url });
  await waitPage('location.pathname === ' + JSON.stringify(url === '/' ? '/' : url) + ' && document.readyState === "complete" && (' + ready + ')');
}
async function noOverflow(label) {
  const sizes = await cdp.eval('({width:innerWidth, scroll:document.documentElement.scrollWidth})');
  assert.ok(sizes.scroll <= sizes.width, label + ' 页面横向溢出：' + JSON.stringify(sizes));
}
async function screenshot(name) {
  const capture = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  await writeFile(path.join(profile, name + '.png'), Buffer.from(capture.data, 'base64'));
}

async function main() {
  await mkdir(run, { recursive: true }); await mkdir(profile, { recursive: true });
  const port = await freePort(); base = 'http://127.0.0.1:' + port;
  let javaLog = '', browserLog = '';
  java = spawn('java', ['-jar', path.join(root, 'target', 'cczx-RadioStation.jar'), '--server.port=' + port, '--app.storage=sqlite', '--spring.profiles.active=', '--spring.flyway.enabled=false', '--spring.datasource.driver-class-name=org.sqlite.JDBC', '--spring.datasource.url=jdbc:sqlite:' + path.join(run, 'test.db'), '--app.data-dir=' + run, '--app.admin-password='], { cwd: root, windowsHide: true, env: { ...process.env, DATA_DIR: run } });
  java.stdout.on('data', data => { javaLog += data; }); java.stderr.on('data', data => { javaLog += data; });
  java.on('error', error => { javaLog += error.message; });
  try { await until(async () => (await fetch(base + '/api/health')).ok, '测试服务启动', 45000); }
  catch (error) { await writeFile(path.join(run, 'server.log'), javaLog); throw error; }

  browser = spawn(edgePath, ['--headless=new', '--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--disable-sync', '--remote-debugging-pipe', '--user-data-dir=' + profile, '--window-size=1440,1100', 'about:blank'], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] });
  browser.stderr.on('data', data => { browserLog += data; }); browser.on('error', error => { browserLog += error.message; });
  cdp = new CDP(browser.stdio[3], browser.stdio[4]);
  let target;
  try { target = (await cdp.send('Target.createTarget', { url: 'about:blank' })).targetId; }
  catch (error) { await writeFile(path.join(run, 'browser.log'), browserLog); throw error; }
  cdp.session = (await cdp.send('Target.attachToTarget', { targetId: target, flatten: true })).sessionId;
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable'); await cdp.send('Network.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await navigate('/', 'document.querySelector("#submitBtn") && !document.querySelector("#submitBtn").disabled');
  assert.equal(await cdp.eval('document.querySelector("#period") === null'), true);
  assert.equal(await cdp.eval('document.querySelectorAll("#song_name,#artist,#song_url,#trackForm").length'), 0);
  await fill('#author', '浏览器同学'); await fill('#class_name', '高一一班');
  for (const invalid of ['   ', '稿'.repeat(101)]) {
    await fill('#content', invalid); await click('#submitBtn');
    assert.ok(await cdp.eval('document.querySelector("[data-field=content]").classList.contains("invalid")'));
    assert.equal((await api('/api/meta')).total, 0);
  }
  await fill('#content', '校园里的每一天都值得记录。'); await click('#submitBtn');
  await waitPage('document.querySelector("#successPanel").classList.contains("show")');
  assert.equal((await api('/api/meta')).total, 1);
  await noOverflow('桌面投稿页'); await screenshot('public-desktop');
  console.log('PASS 投稿页：无期次选择、字数校验、提交、旧字段移除');

  const token = (await api('/api/admin/login', 'POST', { password: testPassword })).token;
  const manuscript = { author: '测试同学', class_name: '高一二班', content: '待审批的校园稿件。' };
  await api('/api/submissions', 'POST', manuscript); await api('/api/submissions', 'POST', manuscript);
  await navigate('/admin.html', 'document.querySelector("#loginView") && !document.querySelector("#loginView").hidden');
  await fill('#password', testPassword); await click('#loginBtn');
  await waitPage('!document.querySelector("#dashView").hidden && document.querySelector("#serverStatusBadge").textContent === "运行中"');
  assert.equal(await cdp.eval('document.querySelectorAll(".nav-item").length'), 4);
  assert.equal(await cdp.eval('document.querySelectorAll("#filterPeriod,#newPeriod,#messagePeriod").length'), 0);
  const server = await api('/api/admin/server', 'GET', undefined, token);
  assert.ok(server.server_ips.length > 0);
  assert.ok(await cdp.eval('document.querySelector("#serverData").textContent.includes(' + JSON.stringify(server.server_ip) + ')'));
  assert.ok(await cdp.eval('document.querySelector(".workspace-top-actions a").textContent.includes("返回投稿页")'));
  await noOverflow('桌面控制台'); await screenshot('dashboard-desktop');
  console.log('PASS 控制台：四模块导航、服务器数据与 IP、返回投稿页');

  await click('.nav-item[data-view=review]');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 3');
  const firstId = await cdp.eval('Number(document.querySelector("#manuscriptBody tr[data-id]").dataset.id)');
  await click('#manuscriptBody tr[data-id="' + firstId + '"]'); await click('#approveSelected');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 2 && !document.querySelector("#approveAllButton").disabled');
  assert.equal((await api('/api/admin/submissions?phase=reviewed', 'GET', undefined, token)).total, 1);
  await click('#approveAllButton'); await waitPage('document.querySelector("#confirmDialog").open');
  assert.equal(await cdp.eval('document.querySelector("#confirmLabel").textContent'), '风险操作');
  await click('#confirmDialog button[value=cancel]');
  await waitPage('!document.querySelector("#confirmDialog").open && !document.querySelector("#approveAllButton").disabled');
  assert.equal(approvePosts, 0);
  assert.equal((await api('/api/admin/submissions?phase=pending', 'GET', undefined, token)).total, 2);
  await click('#approveAllButton'); await waitPage('document.querySelector("#confirmDialog").open');
  await api('/api/submissions', 'POST', { ...manuscript, content: '确认期间新收到的稿件。' });
  await click('#confirmAccept');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 1 && !document.querySelector("#approveAllButton").disabled');
  assert.equal(approvePosts, 1);
  assert.equal((await api('/api/admin/submissions?phase=pending', 'GET', undefined, token)).total, 1);
  console.log('PASS 审批：选中通过、全部审核取消无写请求、确认快照保留新投稿');

  await click('.nav-item[data-view=approved]');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 3');
  await click('#manuscriptBody tr[data-id="' + firstId + '"]');
  await fill('#scheduleDate', '2026-12-01'); await fill('#scheduleTime', '12:30'); await click('#scheduleSelected');
  await waitPage('!document.querySelector("#scheduleSelected").disabled || document.querySelector("#selectionCount").textContent === "已选 0 篇"');
  await until(async () => (await api('/api/admin/schedule', 'GET', undefined, token)).items.length === 1, '播出日程保存');
  assert.equal((await api('/api/admin/schedule', 'GET', undefined, token)).items.length, 1);
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 2');
  assert.equal(await cdp.eval('document.querySelector("#manuscriptBody tr[data-id=\\"' + firstId + '\\"]") === null'), true);
  await click('#approvedScheduledTab');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 1');
  assert.ok(await cdp.eval('document.querySelector("#manuscriptBody").textContent.includes("2026-12-01 12:30")'));
  assert.equal(await cdp.eval('document.querySelector("#selectionCount").textContent'), '已选 0 篇');
  await click('#manuscriptBody tr[data-id="' + firstId + '"]');
  await waitPage('!document.querySelector("#clearSchedule").disabled'); await click('#clearSchedule');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 0 && !document.querySelector("#approveAllButton").disabled');
  await click('#approvedListTab');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 3');
  await click('#manuscriptBody tr[data-id="' + firstId + '"]');
  await fill('#scheduleDate', '2026-12-01'); await fill('#scheduleTime', '12:30'); await click('#scheduleSelected');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 2 && !document.querySelector("#approveAllButton").disabled');
  await noOverflow('桌面审批页'); await screenshot('approved-desktop');
  console.log('PASS 稿件列表：已审批与已安排分开、排期与移出后自动归类、切换清空选择');

  assert.equal(await cdp.eval('document.querySelector("#scheduleBoard") === null'), true);
  assert.equal(await cdp.eval('document.querySelector("#calendarPanel").hidden'), true);
  const others = (await api('/api/admin/submissions?phase=reviewed', 'GET', undefined, token)).items.filter(item => item.id !== firstId);
  await api('/api/admin/submissions/batch', 'POST', { ids: [others[0].id], action: 'schedule', broadcast_date: '2026-12-01', broadcast_time: '13:00' }, token);
  await api('/api/admin/submissions/batch', 'POST', { ids: [others[1].id], action: 'schedule', broadcast_date: '2026-12-02', broadcast_time: '14:00' }, token);
  await click('#approvedCalendarTab');
  await waitPage('!document.querySelector("#calendarPanel").hidden && document.querySelector("#calendarDayCount").textContent === "2 篇"');
  assert.equal(await cdp.eval('document.querySelector("#manuscriptListPanel").hidden'), true);
  assert.equal(await cdp.eval('document.querySelectorAll("#calendarDayItems .day-manuscript").length'), 2);
  assert.equal(await cdp.eval('[...document.querySelectorAll("#calendarDayItems time")].map(node => node.textContent).join(",")'), '12:30,13:00');
  assert.equal(await cdp.eval('document.querySelector("[data-calendar-date=\\"2026-12-01\\"] small").textContent'), '2 篇');
  const firstSchedule = (await api('/api/admin/schedule', 'GET', undefined, token)).items.find(item => item.id === firstId);
  for (const status of ['已播出', '已通过']) {
    await fill('#calendarStatus' + firstId, status); await click('[data-save-calendar-status="' + firstId + '"]');
    await waitPage('document.querySelector(".day-manuscript[data-manuscript-id=\\"' + firstId + '\\"] .badge").textContent === ' + JSON.stringify(status) + ' && !document.querySelector("#calendarStatus' + firstId + '").disabled');
    const items = (await api('/api/admin/schedule', 'GET', undefined, token)).items;
    const changed = items.find(item => item.id === firstId);
    assert.equal(changed.status, status); assert.equal(changed.broadcast_date, firstSchedule.broadcast_date);
    assert.equal(changed.broadcast_time, firstSchedule.broadcast_time); assert.equal(changed.note, firstSchedule.note);
    assert.ok(items.filter(item => item.id !== firstId).every(item => item.status === '已通过'));
    assert.equal(await cdp.eval('document.querySelector("#calendarDayHeading").textContent'), '2026-12-01 的稿件');
    assert.equal(await cdp.eval('document.querySelector("#calendarDayCount").textContent'), '2 篇');
  }
  await screenshot('calendar-desktop'); await noOverflow('桌面播出日历');
  await click('[data-calendar-date="2026-12-02"]');
  assert.equal(await cdp.eval('document.querySelector("#calendarDayCount").textContent'), '1 篇');
  assert.equal(await cdp.eval('document.querySelector("#calendarDayItems time").textContent'), '14:00');
  await click('[data-calendar-date="2026-12-03"]');
  assert.ok(await cdp.eval('document.querySelector("#calendarDayItems").textContent.includes("当天没有")'));
  await click('#calendarPrev'); assert.equal(await cdp.eval('document.querySelector("#calendarMonth").value'), '2026-11');
  await click('#calendarNext'); assert.equal(await cdp.eval('document.querySelector("#calendarMonth").value'), '2026-12');
  await fill('#calendarMonth', '2027-01'); await click('#calendarPrev');
  assert.equal(await cdp.eval('document.querySelector("#calendarMonth").value'), '2026-12');
  await fill('#calendarMonth', '2028-02');
  assert.equal(await cdp.eval('document.querySelectorAll(".calendar-day:not(.outside)").length'), 29);
  await click('#calendarToday');
  assert.equal(await cdp.eval('document.querySelector(".calendar-day.selected").classList.contains("today")'), true);
  await click('#approvedListTab');
  assert.equal(await cdp.eval('document.querySelector("#manuscriptListPanel").hidden'), false);
  await waitPage('document.querySelector("#listCount").textContent === "共 0 篇"');
  await click('#approvedScheduledTab');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 3');
  await fill('#filterStatus', '已播出');
  await waitPage('document.querySelector("#listCount").textContent === "共 0 篇"');
  await click('#resetFilters');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 3');
  console.log('PASS 播出日历：单篇状态往返切换保留日程、其他稿件不变、数量与排序、跨月跨年与闰年');

  await click('.nav-item[data-view=messages]');
  await waitPage('!document.querySelector("#messagesPanel").hidden && document.querySelector("#messageCount").textContent === "0 条"');
  await fill('#messageKind', '投稿计划'); await fill('#messageTitle', '下一期投稿计划');
  await fill('#messageContent', '<img src=x onerror="window.injected=true">欢迎分享校园见闻'); await click('#publishMessage');
  await waitPage('document.querySelector("#messageCount").textContent === "1 条"');
  await screenshot('messages-desktop');
  await navigate('/', 'document.querySelectorAll(".announcement-card").length === 1');
  assert.ok(await cdp.eval('document.querySelector(".announcement-card").textContent.includes("新媒体中心")'));
  assert.ok(await cdp.eval('/\\d{4}-\\d{2}-\\d{2}/.test(document.querySelector(".announcement-meta").textContent)'));
  assert.equal(await cdp.eval('document.querySelectorAll(".announcement-card img").length'), 0);
  assert.equal(await cdp.eval('window.injected === true'), false);
  await navigate('/admin.html', 'document.querySelector("#serverStatusBadge") && document.querySelector("#serverStatusBadge").textContent === "运行中"');
  await click('.nav-item[data-view=messages]'); await waitPage('document.querySelector("#messageHistory [data-message-id]") !== null');
  await click('#messageHistory [data-message-id]');
  await waitPage('document.querySelector("#messageHistory").textContent.includes("已撤回")');
  await navigate('/', 'document.querySelector("#announcementCount") && document.querySelector("#announcementCount").textContent === "0 条"');
  console.log('PASS 公告：计划发布、日期与发布者、HTML 作为文本、撤回');

  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await noOverflow('手机投稿页'); await screenshot('public-mobile');
  await navigate('/admin.html', 'document.querySelector("#serverStatusBadge") && document.querySelector("#serverStatusBadge").textContent === "运行中"');
  for (const view of ['dashboard', 'review', 'approved', 'messages']) {
    await click('.nav-item[data-view=' + view + ']');
    await waitPage('document.querySelector(".nav-item[data-view=' + view + ']").classList.contains("active")');
    await noOverflow('手机 ' + view);
  }
  await click('.nav-item[data-view=approved]'); await click('#approvedCalendarTab');
  await fill('#calendarMonth', '2026-12'); await click('[data-calendar-date="2026-12-01"]');
  await noOverflow('手机播出日历'); await screenshot('calendar-mobile');
  await click('#approvedScheduledTab');
  await waitPage('document.querySelectorAll("#manuscriptBody tr[data-id]").length === 3');
  await noOverflow('手机已安排稿件');
  await click('#btnSettings');
  await fill('#oldPassword', 'admin'); await fill('#newPassword', 'BrowserChanged123'); await fill('#confirmPassword', 'BrowserChanged123');
  await click('#changePassword');
  await waitPage('!document.querySelector("#loginView").hidden && document.querySelector("#loginError").textContent.includes("密码已更新")');
  await fill('#password', 'BrowserChanged123'); await click('#loginBtn');
  await waitPage('!document.querySelector("#dashView").hidden && document.querySelector("#serverStatusBadge").textContent === "运行中"');
  await click('#btnSettings');
  await fill('#oldPassword', 'BrowserChanged123'); await fill('#newPassword', 'admin'); await fill('#confirmPassword', 'admin'); await click('#changePassword');
  await waitPage('!document.querySelector("#loginView").hidden && document.querySelector("#loginError").textContent.includes("密码已更新")');
  await fill('#password', 'admin'); await click('#loginBtn');
  await waitPage('!document.querySelector("#dashView").hidden && document.querySelector("#serverStatusBadge").textContent === "运行中"');
  console.log('PASS 管理员密码：默认 admin 登录、后台改密与改回 admin');
  await screenshot('messages-mobile');
  assert.deepEqual(browserErrors, []);
  console.log('PASS 手机布局：投稿页、四个后台模块及日历无页面横向溢出');
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  if (cdp) { try { await cdp.send('Browser.close'); } catch (_) { /* 浏览器关闭时连接可能先断开 */ } cdp.input.destroy(); }
  if (browser && browser.exitCode === null) browser.kill();
  if (java && java.exitCode === null) {
    const stopped = new Promise(resolve => java.once('exit', resolve)); java.kill();
    await Promise.race([stopped, delay(5000)]);
  }
});
