const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const root = path.resolve(__dirname, "..");
const html = fs.readFileSync(path.join(root, "src/main/resources/static/index.html"), "utf8");
const source = fs.readFileSync(path.join(root, "src/main/resources/static/js/submit.js"), "utf8");

async function page(messages = []) {
  const elements = new Map();
  function element(tag = "div") {
    const classes = new Set();
    return {
      tagName: tag.toUpperCase(), value: "", textContent: "", hidden: false, disabled: false, style: {}, handlers: {}, children: [],
      classList: { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x), toggle: (x, yes) => yes ? classes.add(x) : classes.delete(x) },
      addEventListener(name, callback) { (this.handlers[name] ||= []).push(callback); },
      focus() {}, remove() { if (this.parent) this.parent.children = this.parent.children.filter(x => x !== this); },
      appendChild(child) { child.parent = this; this.children.push(child); },
      append(...children) { children.forEach(child => this.appendChild(child)); },
      replaceChildren(...children) { this.children = []; this.append(...children); },
      querySelector() { return this.children.find(child => child.className === "field-error") || null; },
      async fire(name) { for (const callback of this.handlers[name] || []) await callback({ preventDefault() {} }); }
    };
  }
  for (const match of html.matchAll(/\bid="([^"]+)"/g)) elements.set("#" + match[1], element());
  const fields = new Map(), form = elements.get("#submitForm"); form.elements = {};
  for (const match of html.matchAll(/\bname="([^"]+)"/g)) if (elements.has("#" + match[1])) form.elements[match[1]] = elements.get("#" + match[1]);
  for (const match of html.matchAll(/data-field="([^"]+)"/g)) fields.set(match[1], element());
  form.querySelector = selector => fields.get(selector.match(/data-field="([^"]+)"/)[1]);
  form.reset = () => Object.values(form.elements).forEach(x => { x.value = ""; });
  const requests = [], responses = [];
  const document = {
    querySelector(selector) { assert.ok(elements.has(selector), "页面缺少 " + selector); return elements.get(selector); },
    createElement: element
  };
  vm.runInNewContext(source, {
    document, location: { protocol: "http:" },
    fetch: async (url, options) => {
      requests.push({ url, options, body: options.body ? JSON.parse(options.body) : null });
      if (url === "/api/announcements") return { ok: true, json: async () => ({ items: messages }) };
      const response = responses.shift(); assert.ok(response, "没有准备接口响应");
      return { ok: response.ok !== false, status: response.status || 200, json: async () => response.body };
    }
  });
  await new Promise(resolve => setImmediate(resolve));
  return { elements, fields, form, requests, responses };
}
const fill = p => {
  Object.assign(p.form.elements.author, { value: "测试同学" });
  Object.assign(p.form.elements.class_name, { value: "高一1班" });
  Object.assign(p.form.elements.song_name, { value: "稻香" });
  Object.assign(p.form.elements.artist, { value: "周杰伦" });
};

test("提交完整点播后显示编号，再次点播重置字段与预览", async () => {
  const p = await page(); fill(p);
  p.form.elements.message.value = "愿大家在校园里找到自己的节奏。";
  p.form.elements.reason.value = "想在午间广播听到这首歌。";
  await p.form.elements.song_name.fire("input"); await p.form.elements.artist.fire("input");
  assert.equal(p.elements.get("#previewSong").textContent, "稻香");
  assert.equal(p.elements.get("#previewArtist").textContent, "周杰伦");
  p.responses.push({ body: { ok: true, code: "GB261115001" } }); await p.form.fire("submit");
  const request = p.requests.find(r => r.url === "/api/submissions");
  assert.equal(Object.hasOwn(request.body, "period"), false);
  assert.equal(Object.hasOwn(request.body, "content"), false);
  assert.equal(p.requests.some(r => r.url === "/api/meta"), false);
  assert.equal(request.body.song_name, "稻香"); assert.equal(request.body.artist, "周杰伦");
  assert.equal(request.body.message, p.form.elements.message.value); assert.equal(request.body.reason, p.form.elements.reason.value);
  assert.equal(p.elements.get("#submissionCode").textContent, "GB261115001"); assert.equal(p.form.hidden, true);
  await p.elements.get("#againBtn").fire("click");
  for (const name of ["song_name", "artist", "message", "reason"]) assert.equal(p.form.elements[name].value, "");
  assert.equal(p.elements.get("#previewSong").textContent, "歌曲名称"); assert.equal(p.elements.get("#previewArtist").textContent, "歌手");
  assert.equal(p.elements.get("#messageCounter").textContent, "0 / 100"); assert.equal(p.elements.get("#reasonCounter").textContent, "0 / 300");
  assert.equal(Object.hasOwn(p.form.elements, "period"), false); assert.equal(p.form.hidden, false);
});

test("歌名与歌手必填且最长八十字，选填内容不能超过限制", async () => {
  for (const [name, invalid] of [["song_name", " "], ["artist", " "], ["song_name", "歌".repeat(81)], ["artist", "人".repeat(81)], ["message", "语".repeat(101)], ["reason", "理".repeat(301)]]) {
    const p = await page(); fill(p); p.form.elements[name].value = invalid; await p.form.fire("submit");
    assert.equal(p.requests.filter(r => r.url === "/api/submissions").length, 0, name);
    assert.ok(p.fields.get(name).classList.contains("invalid"), name);
  }
});

test("寄语与理由可以留空，也可以填到各自字数上限", async () => {
  for (const [message, reason] of [["", ""], ["语".repeat(100), "理".repeat(300)]]) {
    const p = await page(); fill(p); p.form.elements.message.value = message; p.form.elements.reason.value = reason;
    p.responses.push({ body: { ok: true, code: "GB261115002" } }); await p.form.fire("submit");
    const request = p.requests.find(r => r.url === "/api/submissions");
    assert.equal(request.body.message, message); assert.equal(request.body.reason, reason);
  }
});

test("寄语与理由分别计数，点播预览将输入作为文本显示", async () => {
  const p = await page(); fill(p); p.form.elements.message.value = "寄语"; p.form.elements.reason.value = "理由";
  await p.form.elements.message.fire("input"); await p.form.elements.reason.fire("input");
  assert.equal(p.elements.get("#messageCounter").textContent, "2 / 100"); assert.equal(p.elements.get("#reasonCounter").textContent, "2 / 300");
  p.form.elements.song_name.value = "<img src=x onerror=alert(1)>"; await p.form.elements.song_name.fire("input");
  assert.equal(p.elements.get("#previewSong").textContent, "<img src=x onerror=alert(1)>");
});

test("公告按块展示日期和实际发布者，内容作为文本呈现", async () => {
  const p = await page([{ title: "下一期投稿计划", content: "<script>alert(1)</script>", kind: "投稿计划", period: "2026-W46", publisher: "网络部", published_at: "2026-11-15T12:00:00+08:00" }]);
  const card = p.elements.get("#announcementFeed").children[0];
  assert.equal(card.className, "announcement-card");
  assert.equal(card.children.find(x => x.className === "announcement-meta").textContent, "2026-11-15 · 发布者：网络部");
  assert.equal(card.children.find(x => x.className === "announcement-content").textContent, "<script>alert(1)</script>");
  assert.equal(card.children.some(x => x.className === "message-kind"), false);
});
test("班级下拉框包含高一十一班和高二九班且不提供自由输入", () => {
  const select = html.match(/<select[^>]*id="class_name"[\s\S]*?<\/select>/)[0];
  assert.equal((select.match(/<option value="高一\d+班"/g) || []).length, 11);
  assert.equal((select.match(/<option value="高二\d+班"/g) || []).length, 9);
  assert.ok(select.includes('value="高一11班"')); assert.ok(select.includes('value="高二9班"'));
});
test("没有选择班级或超出班级范围时不会提交", async () => {
  const p = await page(); fill(p);
  for (const value of ["", "高一12班", "高二10班", "高三1班"]) { p.form.elements.class_name.value = value; await p.form.fire("submit"); }
  assert.equal(p.requests.filter(r => r.url === "/api/submissions").length, 0);
});

test("投稿失败后保留表单并恢复提交按钮", async () => {
  const p = await page(); fill(p); p.form.elements.message.value = "保留这句寄语";
  p.responses.push({ ok: false, status: 400, body: { error: "服务器繁忙，请稍后重试" } }); await p.form.fire("submit");
  assert.equal(p.form.hidden, false); assert.equal(p.elements.get("#submitBtn").disabled, false);
  assert.equal(p.elements.get("#formAlert").textContent, "服务器繁忙，请稍后重试");
  assert.equal(p.form.elements.song_name.value, "稻香"); assert.equal(p.form.elements.message.value, "保留这句寄语");
});

