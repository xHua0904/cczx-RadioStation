/* 从日志行头识别级别，异常堆栈继承所属日志级别，正文中的级别单词不影响分类。 */
(() => {
  "use strict";
  const header = /^\s*(?:(?:\d{4}-\d{2}-\d{2}[T ]|\d{2}:\d{2}:)\S*\s+)?(?:\[[^\]]+\]\s*)?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\b/;
  function classify(lines) {
    let level = "neutral";
    return lines.map(text => {
      const match = text.match(header);
      if (match) level = match[1].toLowerCase();
      else if (level === "neutral" && /^\s*(?:at\s|Caused by:|Suppressed:)|^[\w.$]+(?:Exception|Error)(?::|$)/.test(text)) level = "error";
      return { text, level };
    });
  }
  if (typeof module !== "undefined" && module.exports) module.exports = classify;
  else window.RadioLogLevels = classify;
})();
