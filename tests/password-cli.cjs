/* 在独立数据目录中验证 jar 密码命令，保留手动文档并检查会话注销。 */
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { mkdir, readFile, writeFile } = require('node:fs/promises');
const path = require('node:path');
const net = require('node:net');

const root = path.resolve(__dirname, '..');
const run = path.join(root, 'target', 'test-runtime', 'password-' + Date.now());
const storage = path.join(run, 'data');
const jar = path.join(root, 'target', 'cczx-RadioStation.jar');
const config = ['--app.storage=sqlite', '--spring.profiles.active=', '--spring.flyway.enabled=false', '--spring.datasource.driver-class-name=org.sqlite.JDBC', '--spring.datasource.url=jdbc:sqlite:' + path.join(storage, 'test.db'), '--app.data-dir=' + storage, '--app.admin-password='];
const docs = ['PROJECT.md', 'README.md'];
const content = '# 用户手动维护的项目说明\n默认密码：cczx123456\n不要自动修改此文件。\n';
let server;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

function launch(args) {
  const child = spawn('java', ['-jar', jar, ...config, ...args], { cwd: run, windowsHide: true, env: { ...process.env, DATA_DIR: storage, LOG_DIR: path.join(run, 'logs') } });
  child.output = ''; child.stdout.on('data', chunk => { child.output += chunk; }); child.stderr.on('data', chunk => { child.output += chunk; });
  return child;
}

async function command(args, success = true) {
  const child = launch(args);
  const timer = setTimeout(() => child.kill(), 30000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
    if (success) assert.equal(code, 0, child.output); else assert.notEqual(code, 0);
    for (const doc of docs) assert.equal(await readFile(path.join(run, doc), 'utf8'), content);
    return child.output;
  } finally { clearTimeout(timer); }
}

async function freePort() {
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve)); return port;
}

async function request(base, route, body, token) {
  const response = await fetch(base + route, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  return { status: response.status, data: await response.json() };
}

async function startServer() {
  const port = await freePort(), base = 'http://127.0.0.1:' + port;
  server = launch(['--server.port=' + port]);
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    try { if ((await fetch(base + '/api/health')).ok) return base; } catch (_) { /* 等待独立测试服务启动。 */ }
    if (server.exitCode !== null) throw new Error(server.output);
    await delay(100);
  }
  throw new Error('密码测试服务启动超时');
}

async function stopServer() {
  if (!server || server.exitCode !== null) return;
  const stopped = new Promise(resolve => server.once('exit', resolve)); server.kill();
  await Promise.race([stopped, delay(5000)]);
}

async function main() {
  await mkdir(run, { recursive: true });
  for (const doc of docs) await writeFile(path.join(run, doc), content);
  assert.match(await command(['--help']), /--add-account/);
  assert.match(await command(['--list-accounts']), /暂无账号/);
  await command(['--show-password'], false); await command(['--reset-password'], false);
  assert.match(await command(['--add-account=网络部']), /已添加账号：网络部/);
  assert.match(await command(['--add-account=科创社']), /已添加账号：科创社/);
  const accounts = await command(['--list-accounts']);
  assert.match(accounts, /网络部/); assert.match(accounts, /科创社/); assert.equal(accounts.includes('新媒体中心'), false);
  assert.match(await command(['--show-password', '--account=网络部']), /账号 网络部 的密码：cczx123456/);
  await command(['--add-account=网络部'], false); await command(['--add-account'], false);
  await command(['--show-password', '--account=网络部', '--add-account=多余账号'], false);
  const base = await startServer();
  assert.equal((await request(base, '/api/admin/login', { username: '新媒体中心', password: 'cczx123456' })).status, 401);
  const network = await request(base, '/api/admin/login', { username: '网络部', password: 'cczx123456' });
  const science = await request(base, '/api/admin/login', { username: '科创社', password: 'cczx123456' });
  assert.equal(network.status, 200); assert.equal(science.status, 200);
  assert.equal(network.data.role, 'admin'); assert.equal(science.data.role, 'admin');
  assert.equal((await request(base, '/api/admin/stats', undefined, network.data.token)).status, 200);
  assert.equal((await request(base, '/api/admin/password', { old_password: 'cczx123456', new_password: 'NetworkChanged123', username: '科创社' }, network.data.token)).status, 200);
  assert.equal((await request(base, '/api/admin/me', undefined, network.data.token)).status, 401);
  assert.equal((await request(base, '/api/admin/me', undefined, science.data.token)).data.username, '科创社');
  await stopServer();
  assert.match(await command(['--show-password', '--account=网络部']), /账号 网络部 的密码：NetworkChanged123/);
  await command(['--reset-password', '--account=网络部']);
  assert.match(await command(['--show-password', '--account=网络部']), /账号 网络部 的密码：cczx123456/);
  await command(['--delete-account=科创社']);
  const restarted = await startServer();
  const token = (await request(restarted, '/api/admin/login', { username: '网络部', password: 'cczx123456' })).data.token;
  await command(['--delete-account=网络部']);
  assert.equal((await request(restarted, '/api/admin/me', undefined, token)).status, 401);
  await stopServer();
  assert.match(await command(['--list-accounts']), /暂无账号/);
  await command(['--show-password', '--account=网络部'], false);
  await command(['--delete-account=网络部'], false);
  const empty = await startServer();
  assert.equal((await request(empty, '/api/admin/login', { username: '新媒体中心', password: 'cczx123456' })).status, 401);
  assert.equal((await request(empty, '/api/meta')).data.total, 0);
  for (const doc of docs) assert.equal(await readFile(path.join(run, doc), 'utf8'), content);
  console.log('PASS jar 账号命令：无默认账户、命令添加与删除、全部管理员、显式查询重置、会话注销、文档不变');
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(stopServer);
