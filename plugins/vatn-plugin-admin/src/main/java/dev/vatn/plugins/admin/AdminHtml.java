package dev.vatn.plugins.admin;

/** Self-contained HTML dashboard. No build step — pure HTML + vanilla JS + Tailwind CDN. */
final class AdminHtml {

    private AdminHtml() {}

    static String render(String basePath) {
        return HTML.replace("__BASE__", basePath);
    }

    // language=HTML
    private static final String HTML = """
<!DOCTYPE html>
<html lang="en" class="bg-gray-950 text-gray-100">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>VATN Admin</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <style>
    @keyframes spin { to { transform: rotate(360deg); } }
    .spinning { animation: spin 1s linear infinite; display: inline-block; }
  </style>
</head>
<body class="min-h-screen font-mono text-sm">

<!-- ── Auth overlay ──────────────────────────────────────────────────────── -->
<div id="auth-overlay" class="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/95 hidden">
  <div class="bg-gray-900 border border-gray-700 rounded-xl p-8 w-96 shadow-2xl">
    <h2 class="text-lg font-semibold text-white mb-1">VATN Admin</h2>
    <p class="text-gray-400 text-xs mb-6">Enter your bearer token to continue.</p>
    <input id="token-input" type="password" placeholder="Bearer token…"
           class="w-full bg-gray-800 border border-gray-600 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:border-blue-500 mb-4"/>
    <button onclick="submitToken()"
            class="w-full bg-blue-600 hover:bg-blue-500 text-white rounded-lg py-2 font-medium transition">
      Sign in
    </button>
    <p id="auth-error" class="text-red-400 text-xs mt-3 hidden">Invalid token.</p>
  </div>
</div>

<!-- ── Top bar ───────────────────────────────────────────────────────────── -->
<header class="sticky top-0 z-40 bg-gray-900/80 backdrop-blur border-b border-gray-800 px-6 py-3 flex items-center gap-4">
  <span class="text-blue-400 font-semibold tracking-tight">&#x2B21; VATN Admin</span>
  <span id="hdr-node"   class="text-gray-500 text-xs">&#8212;</span>
  <span id="hdr-flavor" class="text-gray-600 text-xs">&#8212;</span>
  <span id="hdr-uptime" class="text-gray-600 text-xs ml-auto">&#8212;</span>
  <button onclick="refreshAll()" title="Refresh"
          class="text-gray-400 hover:text-white transition text-base leading-none" id="refresh-btn">&#8634;</button>
</header>

<!-- ── Main grid ─────────────────────────────────────────────────────────── -->
<main class="max-w-7xl mx-auto px-4 py-6 space-y-6">

  <!-- Row 1: Overview + Health -->
  <div class="grid grid-cols-1 md:grid-cols-2 gap-4">

    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Node</h2>
      <div id="overview-body" class="space-y-1 text-xs">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-3/4"></div>
        <div class="h-4 bg-gray-800 rounded animate-pulse w-1/2"></div>
      </div>
    </div>

    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Health</h2>
      <div id="health-body" class="space-y-1 text-xs">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-full"></div>
      </div>
    </div>

  </div>

  <!-- Row 2: Plugins (full width) -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Plugins</h2>
    <div id="plugins-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-full"></div>
    </div>
  </div>

  <!-- Row 3: Agents -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Agents</h2>
    <div id="agents-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-1/3"></div>
    </div>
  </div>

  <!-- Row 4: Memory + Performance -->
  <div class="grid grid-cols-1 md:grid-cols-2 gap-4">

    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Memory</h2>
      <div id="jvm-memory-body" class="text-xs space-y-2">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-full"></div>
      </div>
    </div>

    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Performance</h2>
      <div id="jvm-perf-body" class="text-xs space-y-2">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-full"></div>
      </div>
    </div>

  </div>

  <!-- Row 5: Workflows -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Workflow Runs</h2>
    <div id="workflows-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-2/3"></div>
    </div>
  </div>

  <!-- Row 6: Queues -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Named Queues</h2>
    <div id="queues-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-2/3"></div>
    </div>
  </div>

  <!-- Row 7: Routes -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Registered Routes</h2>
    <div id="routes-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-1/2"></div>
    </div>
  </div>

</main>

<script>
const BASE = '__BASE__';
let token = sessionStorage.getItem('vatn_admin_token') || '';

// ── auth ──────────────────────────────────────────────────────────────────
function checkAuth() {
  if (!token) { showAuthOverlay(); return false; }
  return true;
}
function showAuthOverlay() {
  document.getElementById('auth-overlay').classList.remove('hidden');
}
function hideAuthOverlay() {
  document.getElementById('auth-overlay').classList.add('hidden');
}
function submitToken() {
  const t = document.getElementById('token-input').value.trim();
  if (!t) return;
  token = t;
  sessionStorage.setItem('vatn_admin_token', token);
  document.getElementById('auth-error').classList.add('hidden');
  hideAuthOverlay();
  refreshAll();
}
document.getElementById('token-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') submitToken();
});

// ── fetch helpers ─────────────────────────────────────────────────────────
async function api(path) {
  const headers = token ? { Authorization: 'Bearer ' + token } : {};
  const r = await fetch(BASE + '/api/' + path, { headers });
  if (r.status === 401) { showAuthOverlay(); return null; }
  if (!r.ok) throw new Error(r.status);
  return r.json();
}
async function apiPost(path) {
  const headers = token
    ? { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }
    : {};
  const r = await fetch(BASE + '/api/' + path, { method: 'POST', headers });
  if (r.status === 401) { showAuthOverlay(); return null; }
  return r.json().catch(() => ({}));
}

// ── rendering helpers ─────────────────────────────────────────────────────
function kv(label, value) {
  return `<div class="flex justify-between gap-2 py-0.5">
    <span class="text-gray-500">${label}</span>
    <span class="text-gray-200 font-medium truncate">${value}</span>
  </div>`;
}
function badge(text, cls) {
  return `<span class="px-1.5 py-0.5 rounded text-xs font-medium ${cls}">${text}</span>`;
}
function roleBadge(role) {
  if (role === 'PRIMARY') return badge('PRIMARY', 'bg-green-500/20 text-green-400 border border-green-500/30');
  if (role === 'TWIN')    return badge('TWIN',    'bg-blue-500/20 text-blue-400 border border-blue-500/30');
  return                         badge('STANDBY', 'bg-yellow-500/20 text-yellow-300 border border-yellow-500/30');
}
function pluginStateBadge(state) {
  const map = {
    RUNNING:    'bg-green-500/20 text-green-400 border border-green-500/30',
    RESTARTING: 'bg-yellow-500/20 text-yellow-400 border border-yellow-500/30',
    STOPPED:    'bg-gray-700 text-gray-400 border border-gray-600',
    ERROR:      'bg-red-500/20 text-red-400 border border-red-500/30',
  };
  return badge(state, map[state] || 'bg-gray-700 text-gray-400');
}
function workflowStateBadge(state) {
  const map = {
    SUCCESS:  'bg-green-500/20 text-green-400',
    FAILED:   'bg-red-500/20 text-red-400',
    RUNNING:  'bg-blue-500/20 text-blue-300',
    QUEUED:   'bg-gray-700 text-gray-400',
    CANCELED: 'bg-gray-700 text-gray-500',
  };
  return badge(state, map[state] || 'bg-gray-700 text-gray-400');
}
function progressBar(pct, colorClass) {
  const safe = Math.min(100, Math.max(0, pct || 0));
  const col  = colorClass || (safe > 85 ? 'bg-red-500' : safe > 65 ? 'bg-yellow-500' : 'bg-blue-500');
  return `<div class="w-full bg-gray-800 rounded-full h-1.5 mt-1">
    <div class="${col} h-1.5 rounded-full transition-all" style="width:${safe}%"></div>
  </div>`;
}

// ── plugin actions ────────────────────────────────────────────────────────
async function restartPlugin(id) {
  const btn = document.getElementById('btn-restart-' + id);
  if (btn) { btn.disabled = true; btn.textContent = '…'; }
  try {
    await apiPost('plugins/' + encodeURIComponent(id) + '/restart');
    setTimeout(loadPlugins, 1500);
  } catch(e) { console.error('restart failed', e); }
  finally { if (btn) { btn.disabled = false; btn.textContent = '\\u21BA'; } }
}
async function stopPlugin(id) {
  if (!confirm('Stop plugin ' + id + '?')) return;
  const btn = document.getElementById('btn-stop-' + id);
  if (btn) { btn.disabled = true; btn.textContent = '…'; }
  try {
    await apiPost('plugins/' + encodeURIComponent(id) + '/stop');
    setTimeout(loadPlugins, 500);
  } catch(e) { console.error('stop failed', e); }
  finally { if (btn) { btn.disabled = false; btn.textContent = '\\u25A0'; } }
}

// ── section loaders ───────────────────────────────────────────────────────
async function loadOverview() {
  const d = await api('overview');
  if (!d) return;
  document.getElementById('hdr-node').textContent   = 'node: ' + d.nodeId;
  document.getElementById('hdr-flavor').textContent = d.flavor;
  document.getElementById('hdr-uptime').textContent = 'up ' + d.uptimeHuman;
  document.getElementById('overview-body').innerHTML = [
    kv('Node ID',  '<span class="font-mono text-blue-300">' + d.nodeId + '</span>'),
    kv('Flavor',   d.flavor),
    kv('Uptime',   d.uptimeHuman),
    kv('VATN',     d.vatnVersion),
    kv('Plugins',  d.pluginCount),
    kv('Agents',   d.agentCount),
  ].join('');
}

async function loadHealth() {
  const d = await api('health');
  if (!d) return;
  const el = document.getElementById('health-body');
  if (!d.checks || d.checks.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No health checks registered</span>';
    return;
  }
  el.innerHTML = d.checks.map(c => {
    const dot = c.status === 'UP'
      ? '<span class="text-green-400">&#9679;</span>'
      : c.status === 'STANDBY'
        ? '<span class="text-yellow-400">&#9680;</span>'
        : '<span class="text-red-400">&#9679;</span>';
    return '<div class="flex items-center gap-2">' + dot +
      '<span class="text-gray-300">' + c.name + '</span>' +
      '<span class="ml-auto text-gray-500">' + (c.detail || c.status) + '</span></div>';
  }).join('');
}

async function loadPlugins() {
  const d = await api('plugins');
  if (!d) return;
  const el = document.getElementById('plugins-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No plugins registered</span>';
    return;
  }
  const ADMIN_ID = 'dev.vatn.plugins.admin';
  const rows = d.map(p => {
    const shortId   = p.id.replace('dev.vatn.plugins.', '');
    const isStopped = p.state === 'STOPPED';
    const isAdmin   = p.id === ADMIN_ID;
    const stopDis   = (isStopped || isAdmin) ? ' disabled' : '';
    return '<tr class="border-b border-gray-800/50">' +
      '<td class="py-1.5 pr-4 text-gray-300 font-mono">' + shortId + '</td>' +
      '<td class="py-1.5 pr-4 text-gray-400">' + p.name + '</td>' +
      '<td class="py-1.5 pr-4 text-gray-600">' + p.version + '</td>' +
      '<td class="py-1.5 pr-4">' + pluginStateBadge(p.state) + '</td>' +
      '<td class="py-1.5 pr-4 text-red-400 max-w-xs truncate">' + (p.lastError || '') + '</td>' +
      '<td class="py-1.5 whitespace-nowrap">' +
        '<button id="btn-restart-' + p.id + '" onclick="restartPlugin(\'' + p.id + '\')" title="Restart"' +
          ' class="text-yellow-400 hover:text-yellow-300 transition px-1.5 disabled:opacity-30">&#8634;</button>' +
        '<button id="btn-stop-' + p.id + '" onclick="stopPlugin(\'' + p.id + '\')" title="Stop"' +
          stopDis + ' class="text-red-400 hover:text-red-300 transition px-1.5 disabled:opacity-30">&#9632;</button>' +
      '</td>' +
    '</tr>';
  }).join('');
  el.innerHTML =
    '<table class="w-full">' +
      '<thead><tr class="text-gray-600 text-left border-b border-gray-800">' +
        '<th class="pb-2 pr-4 font-normal">ID</th>' +
        '<th class="pb-2 pr-4 font-normal">Name</th>' +
        '<th class="pb-2 pr-4 font-normal">Version</th>' +
        '<th class="pb-2 pr-4 font-normal">State</th>' +
        '<th class="pb-2 pr-4 font-normal">Error</th>' +
        '<th class="pb-2 font-normal">Actions</th>' +
      '</tr></thead>' +
      '<tbody>' + rows + '</tbody>' +
    '</table>';
}

async function loadAgents() {
  const d = await api('agents');
  if (!d) return;
  const el = document.getElementById('agents-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No agents registered</span>';
    return;
  }
  const rows = d.map(a =>
    '<tr class="border-b border-gray-800/50">' +
      '<td class="py-1.5 pr-4 text-gray-300">' + a.id + '</td>' +
      '<td class="py-1.5 pr-4 text-gray-500">' + a.channelType + '</td>' +
      '<td class="py-1.5 pr-4">' + roleBadge(a.role) + '</td>' +
      '<td class="py-1.5 text-gray-500">' + a.strategy.replace('_', ' ') + '</td>' +
    '</tr>'
  ).join('');
  el.innerHTML =
    '<table class="w-full">' +
      '<thead><tr class="text-gray-600 text-left border-b border-gray-800">' +
        '<th class="pb-2 pr-4 font-normal">ID</th>' +
        '<th class="pb-2 pr-4 font-normal">Channel</th>' +
        '<th class="pb-2 pr-4 font-normal">Role</th>' +
        '<th class="pb-2 font-normal">Strategy</th>' +
      '</tr></thead>' +
      '<tbody>' + rows + '</tbody>' +
    '</table>';
}

async function loadJvm() {
  const d = await api('jvm');
  if (!d) return;

  // ── Memory panel ─────────────────────────────────────────────────────
  const heap = d.heap || {};
  const pct  = heap.pct || 0;
  let memHtml = kv('Heap used', (heap.usedMb || 0) + ' / ' + (heap.maxMb || '?') + ' MB (' + pct + '%)') +
    progressBar(pct) +
    kv('Non-heap', (d.nonHeap ? d.nonHeap.usedMb : 0) + ' MB');
  if (d.gc && d.gc.length > 0) {
    memHtml += '<div class="pt-2 mt-1 border-t border-gray-800 space-y-0.5">' +
      d.gc.map(g =>
        '<div class="flex justify-between text-gray-500">' +
          '<span>' + g.name + '</span>' +
          '<span>' + g.collectionCount + '&#215; &nbsp;' + g.collectionTimeMs + 'ms</span>' +
        '</div>'
      ).join('') + '</div>';
  }
  document.getElementById('jvm-memory-body').innerHTML = memHtml;

  // ── Performance panel ─────────────────────────────────────────────────
  const cpu = d.cpu || {};
  const thr = d.threads || {};
  const rt  = d.runtime || {};
  let perfHtml = '';
  if (cpu.processCpuPct !== undefined && cpu.processCpuPct >= 0) {
    perfHtml +=
      kv('Process CPU', cpu.processCpuPct + '%') + progressBar(cpu.processCpuPct) +
      kv('System CPU',  cpu.systemCpuPct  + '%') + progressBar(cpu.systemCpuPct, 'bg-purple-500');
  } else {
    perfHtml += kv('Load avg', (cpu.loadAvg || 0).toFixed(2));
  }
  perfHtml += kv('Processors', cpu.processors || '—');
  if (thr.live !== undefined) {
    perfHtml += '<div class="pt-2 mt-1 border-t border-gray-800">' +
      kv('Threads live',   thr.live) +
      kv('Threads peak',   thr.peak) +
      kv('Threads daemon', thr.daemon) +
    '</div>';
  }
  if (rt.jvmName) {
    perfHtml += '<div class="pt-2 mt-1 border-t border-gray-800">' +
      kv('JVM', rt.jvmName + ' ' + rt.jvmVersion) +
      kv('JVM uptime', ((rt.uptimeMs || 0) / 60000).toFixed(1) + ' min') +
    '</div>';
  }
  document.getElementById('jvm-perf-body').innerHTML = perfHtml;
}

async function loadWorkflows() {
  const d = await api('workflows');
  if (!d) return;
  const el = document.getElementById('workflows-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No workflow runs found</span>';
    return;
  }
  const rows = d.map(r =>
    '<tr class="border-b border-gray-800/50">' +
      '<td class="py-1.5 pr-4 text-gray-500 font-mono">' + r.runId.substring(0, 8) + '&#8230;</td>' +
      '<td class="py-1.5 pr-4 text-gray-300">' + r.dagId + '</td>' +
      '<td class="py-1.5 pr-4">' + workflowStateBadge(r.state) + '</td>' +
      '<td class="py-1.5 pr-4 text-gray-500">' + (r.durationMs != null ? (r.durationMs / 1000).toFixed(1) + 's' : '&#8212;') + '</td>' +
      '<td class="py-1.5 pr-4 text-gray-600">' + r.triggered + '</td>' +
      '<td class="py-1.5 text-gray-600">' + (r.started ? new Date(r.started).toLocaleString() : '&#8212;') + '</td>' +
    '</tr>'
  ).join('');
  el.innerHTML =
    '<table class="w-full">' +
      '<thead><tr class="text-gray-600 text-left border-b border-gray-800">' +
        '<th class="pb-2 pr-4 font-normal">Run</th>' +
        '<th class="pb-2 pr-4 font-normal">DAG</th>' +
        '<th class="pb-2 pr-4 font-normal">State</th>' +
        '<th class="pb-2 pr-4 font-normal">Duration</th>' +
        '<th class="pb-2 pr-4 font-normal">Trigger</th>' +
        '<th class="pb-2 font-normal">Started</th>' +
      '</tr></thead>' +
      '<tbody>' + rows + '</tbody>' +
    '</table>';
}

async function loadQueues() {
  const d = await api('queues');
  if (!d) return;
  const el = document.getElementById('queues-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No named queues found</span>';
    return;
  }
  function qbadge(label, count, cls) {
    return `<span class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs ${cls}">${label} <strong>${count}</strong></span>`;
  }
  const rows = d.map(q =>
    '<tr class="border-b border-gray-800/50">' +
      '<td class="py-1.5 pr-4 text-gray-300 font-mono">' + q.name + '</td>' +
      '<td class="py-1.5 pr-3">' + qbadge('pending', q.pending, 'bg-blue-500/20 text-blue-300') + '</td>' +
      '<td class="py-1.5 pr-3">' + qbadge('claimed', q.claimed, 'bg-yellow-500/20 text-yellow-300') + '</td>' +
      '<td class="py-1.5 pr-3">' + qbadge('done', q.done, 'bg-green-500/20 text-green-400') + '</td>' +
      '<td class="py-1.5">'       + qbadge('dead', q.dead, 'bg-red-500/20 text-red-400') + '</td>' +
    '</tr>'
  ).join('');
  el.innerHTML =
    '<table class="w-full">' +
      '<thead><tr class="text-gray-600 text-left border-b border-gray-800">' +
        '<th class="pb-2 pr-4 font-normal">Queue</th>' +
        '<th class="pb-2 pr-3 font-normal">Pending</th>' +
        '<th class="pb-2 pr-3 font-normal">Claimed</th>' +
        '<th class="pb-2 pr-3 font-normal">Done</th>' +
        '<th class="pb-2 font-normal">Dead</th>' +
      '</tr></thead>' +
      '<tbody>' + rows + '</tbody>' +
    '</table>';
}

async function loadRoutes() {
  const d = await api('routes');
  if (!d) return;
  const el = document.getElementById('routes-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No routes registered</span>';
    return;
  }
  el.innerHTML = '<div class="flex flex-wrap gap-2">' +
    d.map(r => '<span class="bg-gray-800 border border-gray-700 rounded px-2 py-0.5 text-gray-400">' + r + '</span>').join('') +
  '</div>';
}

// ── refresh ───────────────────────────────────────────────────────────────
async function refreshAll() {
  if (!checkAuth()) return;
  const btn = document.getElementById('refresh-btn');
  btn.classList.add('spinning');
  try {
    await Promise.all([
      loadOverview(), loadHealth(), loadPlugins(),
      loadAgents(), loadJvm(), loadWorkflows(), loadQueues(), loadRoutes()
    ]);
  } finally {
    btn.classList.remove('spinning');
  }
}

// ── boot ──────────────────────────────────────────────────────────────────
if (token) { refreshAll(); } else { showAuthOverlay(); }
setInterval(() => { if (token && !document.hidden) refreshAll(); }, 10000);
</script>
</body>
</html>
""";
}
