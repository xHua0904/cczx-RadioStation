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
const fill = p => { p.form.elements.author.value = "测试同学"; p.form.elements.class_name.value = "高一一班"; };

test("无需选择期次即可提交一百字稿件，显示编号并可继续投稿", async () => {
  const p = await page(); fill(p); p.form.elements.content.value = "稿".repeat(100);
  p.responses.push({ body: { ok: true, code: "GB261115001" } }); await p.form.fire("submit");
  const request = p.requests.find(r => r.url === "/api/submissions");
  assert.equal(Object.hasOwn(request.body, "period"), false); assert.equal(request.body.content.length, 100);
  assert.equal(p.requests.some(r => r.url === "/api/meta"), false);
  assert.equal(Object.hasOwn(request.body, "song_name"), false);
  assert.equal(p.elements.get("#submissionCode").textContent, "GB261115001"); assert.equal(p.form.hidden, true);
  await p.elements.get("#againBtn").fire("click");
  assert.equal(p.form.elements.content.value, ""); assert.equal(Object.hasOwn(p.form.elements, "period"), false); assert.equal(p.form.hidden, false);
});

test("空白和超过一百字的稿件不能提交", async () => {
  const p = await page(); fill(p);
  for (const content of [" ", "稿".repeat(101)]) { p.form.elements.content.value = content; await p.form.fire("submit"); }
  assert.equal(p.requests.filter(r => r.url === "/api/submissions").length, 0);
});

test("公告按块展示日期和实际发布者，内容作为文本呈现", async () => {
  const p = await page([{ title: "下一期投稿计划", content: "<script>alert(1)</script>", kind: "投稿计划", period: "2026-W46", publisher: "网络部", published_at: "2026-11-15T12:00:00+08:00" }]);
  const card = p.elements.get("#announcementFeed").children[0];
  assert.equal(card.className, "announcement-card");
  assert.equal(card.children.find(x => x.className === "announcement-meta").textContent, "2026-11-15 · 网络部");
  assert.equal(card.children.find(x => x.className === "announcement-content").textContent, "<script>alert(1)</script>");
});

test("投稿失败后保留表单并恢复提交按钮", async () => {
  const p = await page(); fill(p); p.form.elements.content.value = "校园投稿";
  p.responses.push({ ok: false, status: 400, body: { error: "服务器繁忙，请稍后重试" } }); await p.form.fire("submit");
  assert.equal(p.form.hidden, false); assert.equal(p.elements.get("#submitBtn").disabled, false);
  assert.equal(p.elements.get("#formAlert").textContent, "服务器繁忙，请稍后重试");
});

