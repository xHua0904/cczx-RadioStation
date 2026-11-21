const { test } = require('node:test');
const assert = require('node:assert/strict');
const classify = require('../src/main/resources/static/js/log-levels.js');

test('根据真实行头分级且正文中的 ERROR 不会把 INFO 错标为错误', () => {
  const result = classify([
    ' :: Spring Boot ::',
    '2026-11-21T18:00:00.001+08:00  INFO 123 --- [main] App : message mentions ERROR',
    '2026-11-21T18:00:00.002+08:00 DEBUG 123 --- [main] App : detail',
    '18:00:01.123 [main] WARN App -- slow request',
    '\tat example.App.run(App.java:1)',
    '2026-11-21 18:00:02.345 ERROR 123 --- [main] App : failed',
    'Caused by: java.io.IOException: failure',
    'FATAL service unavailable',
    'TRACE low level detail'
  ]);
  assert.deepEqual(result.map(item => item.level), ['neutral', 'info', 'debug', 'warn', 'warn', 'error', 'error', 'fatal', 'trace']);
});
test('尾部截断的异常堆栈识别为错误且日志文本原样保留', () => {
  const source = ['\tat example.App.run(App.java:1)', 'Caused by: java.io.IOException: Broken pipe', 'INFO <img src=x onerror=alert(1)>'];
  const result = classify(source);
  assert.deepEqual(result.map(item => item.level), ['error', 'error', 'info']);
  assert.deepEqual(result.map(item => item.text), source);
});
