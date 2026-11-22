/* 不构建 jar 的界面预览：只监听本机，所有示例数据存在内存中，不连接正式数据库。 */
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const root = path.resolve(__dirname, '../src/main/resources/static');
const statuses = ['待审核', '已通过', '已播出', '已退稿'];
const time = () => new Date().toISOString();
const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date());
const songs = [['稻香', '周杰伦'], ['起风了', '买辣椒也用券'], ['平凡之路', '朴树'], ['夜空中最亮的星', '逃跑计划'], ['如愿', '王菲'], ['倔强', '五月天'], ['这世界那么多人', '莫文蔚'], ['向云端', '小霞 / 海洋Bo']];
let items = songs.map(([song_name, artist], index) => ({
  id: index + 1, code: 'GB' + today.replaceAll('-', '').slice(2) + String(index + 1).padStart(3, '0'),
  author: ['张同学', '李同学', '王同学', '陈同学'][index % 4], class_name: '高' + (index % 2 ? '二' : '一') + (index % 9 + 1) + '班',
  song_name, artist, title: song_name, category: '点歌祝福', contact: '', source: '在线投稿',
  message: index % 2 ? '' : '送给努力生活的同学们，愿我们都能找到自己的节奏。',
  reason: index % 2 ? '希望在午间广播听到这首歌。' : '', legacy_content: '', content: '点播《' + song_name + '》 · ' + artist,
  status: index < 3 ? '待审核' : index < 6 ? '已通过' : index === 6 ? '已播出' : '已退稿',
  broadcast_date: index >= 4 && index <= 6 ? today : '', broadcast_time: index >= 4 && index <= 6 ? '12:' + (index * 5) : '',
  note: '', created_at: time(), updated_at: time(), reviewed_by: index >= 3 ? '预览账号' : '', scheduled_by: index >= 4 && index <= 6 ? '预览账号' : '', updated_by: index >= 3 ? '预览账号' : ''
}));
let announcements = [{ id: 1, title: '本周校园点播开放', content: '选一首想听的歌，也可以把简短寄语送给身边的人。新媒体中心将审核点播申请并安排播出。', publisher: '预览账号', published: true, published_at: time(), created_at: time(), updated_at: time(), updated_by: '预览账号' }];
let history = items.map(row => ({ id: row.id, username: '投稿人：' + row.author, action: '在线投稿', target_type: 'submission', target_id: String(row.id), target_label: row.code, created_at: time() }));
const sessions = new Map();
function audit(username, action, type, id, label) { history.unshift({ id: randomUUID(), username, action, target_type: type, target_id: String(id), target_label: label, created_at: time() }); }
for (const row of items) {
  if (row.reviewed_by) audit(row.reviewed_by, row.status === '已退稿' ? '退稿' : '审核通过', 'submission', row.id, row.code);
  if (row.broadcast_date) audit(row.scheduled_by, '安排播出日程', 'submission', row.id, row.code);
}
const operationActions = { review: ['审核通过'], schedule: ['安排播出日程','移出日程'], reject: ['退稿'], reopen: ['重新提交审批'], played: [], note: ['修改备注'], delete: ['删除稿件'], announcement: ['发布公告','修改公告','删除公告'], account: [], export: ['导出稿件'], import: ['导入旧稿件'] };
const pick = (row, keys) => Object.fromEntries(keys.map(key => [key, row[key]]));
function filtered(params) {
  return items.filter(row => (!params.get('phase') || (params.get('phase') === 'pending' ? row.status === '待审核' : row.status !== '待审核'))
    && (!params.get('schedule') || (params.get('schedule') === 'unscheduled' ? !row.broadcast_date : row.broadcast_date && ['已通过', '已播出'].includes(row.status)))
    && (!params.get('status') || params.get('status') === row.status)
    && (!params.get('q') || [row.song_name, row.artist, row.author, row.class_name, row.code, row.message, row.reason].join(' ').includes(params.get('q'))));
}
function paged(rows, params, pageSize = 15) {
  const page = Math.max(1, Number(params.get('page')) || 1), counts = {};
  for (const row of rows) if (row.status) counts[row.status] = (counts[row.status] || 0) + 1;
  return { items: rows.slice((page - 1) * pageSize, page * pageSize), page, pages: Math.max(1, Math.ceil(rows.length / pageSize)), total: rows.length, page_size: pageSize, counts };
}
function stats() {
  const counts = Object.fromEntries(statuses.map(s => [s, items.filter(row => row.status === s).length]));
  const byClass = new Map(); for (const row of items) byClass.set(row.class_name, (byClass.get(row.class_name) || 0) + 1);
  return { total: items.length, this_week: items.length, pending: counts['待审核'], adopted: counts['已通过'] + counts['已播出'], broadcast: counts['已播出'],
    adoption_rate: Math.round((counts['已通过'] + counts['已播出']) / Math.max(1, items.length) * 100),
    weekly: [12, 18, 14, 22, 19, 26, 21, items.length].map((count, i) => ({ label: '第' + (i + 1) + '周', count })),
    by_class: [...byClass].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count) };
}
function send(res, data, status = 200) { res.writeHead(status, { 'Content-Type': 'application/json;charset=UTF-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)); }
async function body(req) {
  let data = ''; for await (const chunk of req) { data += chunk; if (data.length > 65536) throw new Error('预览请求过大'); }
  return data ? JSON.parse(data) : {};
}
function create(input, actor) {
  const id = Math.max(0, ...items.map(row => row.id)) + 1, stamp = time();
  const row = { id, code: 'GB' + today.replaceAll('-', '').slice(2) + String(id).padStart(3, '0'), author: input.author || '预览同学', class_name: input.class_name || '高一1班',
    song_name: input.song_name || '', artist: input.artist || '', message: input.message || '', reason: input.reason || '', title: input.song_name || input.title || '',
    content: input.content || '', legacy_content: input.content || '', category: '点歌祝福', contact: input.contact || '', status: actor ? input.status || '待审核' : '待审核',
    source: actor ? '手动登记' : '在线投稿', note: input.note || '', broadcast_date: '', broadcast_time: '', reviewed_by: '', scheduled_by: '', updated_by: actor || '', created_at: stamp, updated_at: stamp };
  items.unshift(row); audit(actor || '投稿人：' + row.author, actor ? '手动登记' : '在线投稿', 'submission', row.id, row.code);
  return { ok: true, code: row.code, created_at: stamp };
}
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1'), endpoint = url.pathname, method = req.method;
    if (!endpoint.startsWith('/api/')) {
      const name = endpoint === '/' ? '/index.html' : endpoint === '/admin' ? '/admin.html' : decodeURIComponent(endpoint);
      const file = path.resolve(root, '.' + name);
      if (!file.startsWith(root + path.sep)) return send(res, { error: '未找到页面' }, 404);
      let bytes; try { bytes = await fs.readFile(file); } catch { return send(res, { error: '未找到页面' }, 404); }
      const extension = path.extname(file);
      if (extension === '.html') {
        let html = bytes.toString('utf8').replaceAll('@assets.version@', 'preview');
        const notice = '<aside style="position:fixed;right:16px;bottom:16px;z-index:2000;padding:8px 12px;border-radius:8px;background:#252a30;color:white;font:12px system-ui;box-shadow:0 4px 16px #0002">界面预览 · 示例数据</aside>';
        html = html.replace('</body>', notice + '</body>');
        if (name === '/admin.html') html = html.replace('</h1>', '</h1><p style="margin:12px 0;color:#4c535c">预览时，账号和密码任意填写即可进入后台。</p>');
        bytes = Buffer.from(html);
      }
      res.writeHead(200, { 'Content-Type': ({ '.html': 'text/html;charset=UTF-8', '.css': 'text/css;charset=UTF-8', '.js': 'application/javascript;charset=UTF-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' })[extension] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      return res.end(bytes);
    }
    const input = ['POST', 'PATCH'].includes(method) ? await body(req) : {};
    if (endpoint === '/api/health') return send(res, { ok: true, time: time() });
    if (endpoint === '/api/meta') return send(res, { total: items.length, statuses });
    if (endpoint === '/api/announcements') return send(res, { items: announcements.filter(row => row.published) });
    if (endpoint === '/api/submissions' && method === 'POST') return send(res, create(input));
    if (endpoint === '/api/submissions/progress' && method === 'POST') {
      const query = (input.query || '').trim(), byCode = /^GB[0-9]+$/i.test(query);
      const rows = items.filter(row => byCode ? row.code === query.toUpperCase() : row.author === query).map(row => pick(row, ['code','class_name','song_name','artist','status','broadcast_date','broadcast_time','created_at']));
      return send(res, { ...paged(rows, new URLSearchParams({ page: String(input.page || 1) }), 10), input_type: byCode ? 'code' : 'name' });
    }
    if (endpoint === '/api/submissions/song-exists') return send(res, { exists: items.some(row => row.song_name.trim().toLowerCase() === (url.searchParams.get('song_name') || '').trim().toLowerCase()) });
    if (endpoint === '/api/schedule') {
      const rows = items.filter(row => row.broadcast_date >= today && ['已通过','已播出'].includes(row.status)).sort((a,b) => (a.broadcast_date + a.broadcast_time).localeCompare(b.broadcast_date + b.broadcast_time)).map(row => pick(row, ['song_name','artist','status','broadcast_date','broadcast_time']));
      return send(res, paged(rows, url.searchParams, 20));
    }
    if (endpoint === '/api/admin/login' && method === 'POST') {
      if (!input.username?.trim() || !input.password) return send(res, { error: '请填写预览账号和密码' }, 400);
      const token = randomUUID(); sessions.set(token, input.username.trim());
      return send(res, { token, username: input.username.trim(), role: 'admin', expires_at: time() });
    }
    const token = (req.headers.authorization || '').replace(/^Bearer /, ''), username = sessions.get(token);
    if (!username) return send(res, { error: '请登录界面预览' }, 401);
    if (endpoint === '/api/admin/me') return send(res, { username, role: 'admin' });
    if (endpoint === '/api/admin/logout' || endpoint === '/api/admin/password') { sessions.delete(token); return send(res, { ok: true }); }
    if (endpoint === '/api/admin/stats') return send(res, stats());
    if (endpoint === '/api/admin/server') return send(res, { server_ip: '127.0.0.1', server_ips: ['127.0.0.1'], uptime_seconds: 8620, storage: '界面预览（示例数据）', database_latency_ms: 1.2, java_version: '21 LTS（示例）', memory_used_mb: 186, memory_max_mb: 512, processors: 2, time: time() });
    if (endpoint === '/api/admin/logs') return send(res, { file: 'springboot.log（预览示例）', updated_at: time(), lines: [
      time() + ' INFO  Application : 校园广播站已启动（示例日志）', time() + ' DEBUG Submission : 收到点播申请（示例日志）',
      time() + ' WARN  Submission : 请检查歌曲信息（示例日志）', time() + ' ERROR Network : 示例连接错误，用于查看高亮样式', '    at Example.preview(Example.java:12)' ] });
    if (endpoint === '/api/admin/audit') {
      const operation = url.searchParams.get('operation');
      const rows = history.filter(row => !['在线投稿','手动登记'].includes(row.action) && (!operation || (operation === 'played' ? row.action.startsWith('切换播出状态：') : (operationActions[operation] || []).includes(row.action))) && (!url.searchParams.get('target_type') || row.target_type === url.searchParams.get('target_type')) && (!url.searchParams.get('target_id') || row.target_id === url.searchParams.get('target_id')) && (!url.searchParams.get('q') || [row.username, row.action, row.target_label].join(' ').includes(url.searchParams.get('q'))));
      return send(res, paged(rows, url.searchParams, 20));
    }
    if (endpoint === '/api/admin/schedule') return send(res, { items: items.filter(row => row.broadcast_date && ['已通过', '已播出'].includes(row.status)) });
    if (endpoint === '/api/admin/review-summary') { const ids = items.filter(row => row.status === '待审核').map(row => row.id); return send(res, { count: ids.length, ids }); }
    if (endpoint === '/api/admin/submissions') return send(res, method === 'POST' ? create(input, username) : paged(filtered(url.searchParams), url.searchParams));
    if (['/api/admin/submissions/batch', '/api/admin/submissions/approve-all'].includes(endpoint) && method === 'POST') {
      const action = endpoint.endsWith('approve-all') ? 'approve' : input.action, rows = items.filter(row => input.ids?.includes(row.id));
      for (const row of rows) {
        if (action === 'schedule') { row.broadcast_date = input.broadcast_date; row.broadcast_time = input.broadcast_time || ''; row.scheduled_by = username; }
        else if (action === 'clear_schedule') { row.broadcast_date = row.broadcast_time = ''; row.scheduled_by = username; }
        else { row.status = ({ approve: '已通过', reject: '已退稿', played: '已播出', reopen: '待审核' })[action] || row.status; row.reviewed_by = username; }
        row.note = input.note || row.note; row.updated_by = username; row.updated_at = time(); audit(username, ({ approve: '审核通过', reject: '退稿', schedule: '安排播出日程', played: '切换播出状态：已播出', clear_schedule: '移出日程', reopen: '重新提交审批' })[action], 'submission', row.id, row.code);
      }
      return send(res, { ok: true, updated: rows.length, remaining: items.filter(row => row.status === '待审核').length });
    }
    const submission = endpoint.match(/^\/api\/admin\/submissions\/(\d+)$/);
    if (submission) {
      const row = items.find(row => row.id === Number(submission[1])); if (!row) return send(res, { error: '未找到预览稿件' }, 404);
      if (method === 'DELETE') items = items.filter(item => item !== row);
      else if (method === 'PATCH') { for (const key of ['status', 'note', 'broadcast_date', 'broadcast_time']) if (key in input) row[key] = input[key]; row.updated_by = username; }
      audit(username, method === 'DELETE' ? '删除稿件' : '更新稿件', 'submission', row.id, row.code); return send(res, { ok: true });
    }
    if (endpoint === '/api/admin/announcements') {
      if (method === 'GET') return send(res, { items: announcements });
      const row = { id: Math.max(0, ...announcements.map(row => row.id)) + 1, title: input.title, content: input.content, publisher: username, published: true, published_at: time(), created_at: time(), updated_at: time(), updated_by: username };
      announcements.unshift(row); audit(username, '发布公告', 'announcement', row.id, row.title); return send(res, { ok: true, id: row.id });
    }
    const announcement = endpoint.match(/^\/api\/admin\/announcements\/(\d+)$/);
    if (announcement) {
      const row = announcements.find(row => row.id === Number(announcement[1])); if (!row) return send(res, { error: '未找到预览公告' }, 404);
      if (method === 'DELETE') announcements = announcements.filter(item => item !== row); else { row.published = input.published; row.updated_by = username; }
      audit(username, method === 'DELETE' ? '删除公告' : '修改公告', 'announcement', row.id, row.title); return send(res, { ok: true });
    }
    if (endpoint === '/api/admin/export') return send(res, { error: '界面预览不生成正式导出文件，请在编译后的应用中验证导出。' }, 400);
    return send(res, { error: '预览未提供此接口' }, 404);
  } catch (error) { send(res, { error: error.message }, 400); }
});
const port = Number(process.argv[2] || 18080);
server.listen(port, '127.0.0.1', () => console.log('界面预览：http://127.0.0.1:' + port + '（仅示例数据，不连接数据库）'));
server.on('error', error => { console.error(error.message); process.exitCode = 1; });
