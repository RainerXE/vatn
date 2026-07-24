package dev.vatn.plugins.containers;

public final class ContainersHtml {
    private ContainersHtml() {}

    public static String render(String basePath) {
        return HTML.replace("__BASE__", basePath);
    }

    // language=HTML
    private static final String HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>VATN Containers</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
  
  <!-- CDNs for HTMX, Alpine, and xterm -->
  <script src="https://cdn.jsdelivr.net/npm/htmx.org@1.9.10/dist/htmx.min.js"></script>
  <script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.css" />
  <script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.js"></script>

  <style>
    body { padding-top: 40px; }
    :root {
      --bg-color: #0d0e11;
      --sidebar-bg: #14161d;
      --card-bg: rgba(22, 25, 33, 0.7);
      --card-border: rgba(255, 255, 255, 0.05);
      --text-main: #f3f4f6;
      --text-muted: #9ca3af;
      --accent: #3b82f6;
      --accent-hover: #1d4ed8;
      --green: #10b981;
      --red: #ef4444;
      --border-radius: 16px;
      --font-sans: 'Inter', sans-serif;
      --font-mono: 'JetBrains Mono', monospace;
    }

    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }

    body {
      background-color: var(--bg-color);
      color: var(--text-main);
      font-family: var(--font-sans);
      min-height: 100vh;
      display: flex;
      overflow-x: hidden;
    }

    /* Glassmorphism card utility */
    .glass-card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: var(--border-radius);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.4);
      padding: 24px;
      transition: transform 0.2s ease, box-shadow 0.2s ease;
    }

    .glass-card:hover {
      box-shadow: 0 12px 40px 0 rgba(0, 0, 0, 0.5);
    }

    /* Sidebar navigation */
    .top-nav {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      z-index: 100;
      height: 40px;
      background: rgba(20,22,29,0.95);
      backdrop-filter: blur(8px);
      border-bottom: 1px solid rgba(255,255,255,0.06);
      padding: 10px 24px;
      display: flex;
      align-items: center;
      gap: 20px;
    }
    .nav-brand {
      color: #60a5fa;
      font-weight: 600;
      font-size: 14px;
      margin-right: 4px;
    }
    .nav-link {
      color: #9ca3af;
      text-decoration: none;
      font-size: 12px;
      transition: color 0.2s;
    }
    .nav-link:hover { color: #f3f4f6; }
    .nav-current {
      color: #f3f4f6;
      font-size: 12px;
      font-weight: 500;
    }
    .nav-meta {
      color: #6b7280;
      font-size: 11px;
      margin-left: auto;
    }

    .sidebar {
      width: 260px;
      background: var(--sidebar-bg);
      border-right: 1px solid var(--card-border);
      display: flex;
      flex-direction: column;
      height: calc(100vh - 40px);
      position: fixed;
      left: 0;
      top: 40px;
      z-index: 10;
      padding: 20px 24px;
    }

    .logo-container {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 40px;
    }

    .logo-icon {
      font-size: 24px;
      color: var(--accent);
    }

    .logo-text {
      font-size: 18px;
      font-weight: 700;
      letter-spacing: -0.5px;
    }

    .nav-list {
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 8px;
      flex-grow: 1;
    }

    .nav-item {
      padding: 12px 16px;
      border-radius: 12px;
      color: var(--text-muted);
      cursor: pointer;
      font-weight: 500;
      transition: background 0.2s, color 0.2s;
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .nav-item:hover, .nav-item.active {
      background: rgba(255, 255, 255, 0.05);
      color: var(--text-main);
    }

    .nav-item.active {
      border-left: 3px solid var(--accent);
    }

    /* Main container */
    .main-content {
      margin-left: 260px;
      flex-grow: 1;
      padding: 24px 32px;
      min-height: calc(100vh - 40px);
      box-sizing: border-box;
    }

    .header-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 40px;
    }

    .title-group h1 {
      font-size: 28px;
      font-weight: 700;
      letter-spacing: -0.5px;
    }

    .title-group p {
      color: var(--text-muted);
      font-size: 14px;
      margin-top: 4px;
    }

    /* Dashboard grid */
    .dashboard-grid {
      display: grid;
      grid-template-columns: repeat(12, 1fr);
      gap: 24px;
    }

    .col-4 { grid-column: span 4; }
    .col-6 { grid-column: span 6; }
    .col-8 { grid-column: span 8; }
    .col-12 { grid-column: span 12; }

    @media (max-width: 1024px) {
      .col-4, .col-6, .col-8 { grid-column: span 12; }
    }

    /* Headings */
    .card-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 16px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    /* Tables */
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 14px;
    }

    th {
      text-align: left;
      color: var(--text-muted);
      font-weight: 500;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--card-border);
    }

    td {
      padding: 16px 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.02);
      vertical-align: middle;
    }

    /* Progress bar */
    .progress-track {
      background: rgba(255, 255, 255, 0.05);
      border-radius: 99px;
      height: 8px;
      width: 100%;
      overflow: hidden;
      margin-top: 8px;
    }

    .progress-fill {
      height: 100%;
      border-radius: 99px;
      transition: width 0.3s ease;
    }

    /* Status Pill */
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 99px;
      font-size: 12px;
      font-weight: 500;
    }

    .status-running {
      background: rgba(16, 185, 129, 0.1);
      color: var(--green);
    }

    .status-stopped {
      background: rgba(239, 68, 68, 0.1);
      color: var(--red);
    }

    /* Buttons */
    .btn {
      background: var(--accent);
      color: #fff;
      border: none;
      padding: 8px 16px;
      border-radius: 8px;
      font-weight: 500;
      cursor: pointer;
      font-size: 13px;
      transition: background 0.2s;
    }

    .btn:hover {
      background: var(--accent-hover);
    }

    .btn-icon {
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      padding: 6px;
      border-radius: 6px;
      transition: color 0.2s, background 0.2s;
    }

    .btn-icon:hover {
      color: var(--text-main);
      background: rgba(255, 255, 255, 0.05);
    }

    /* Overlay Terminal Modal */
    .terminal-overlay {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      background: rgba(0, 0, 0, 0.85);
      z-index: 100;
      display: flex;
      align-items: center;
      justify-content: center;
      backdrop-filter: blur(8px);
    }

    .terminal-container {
      width: 80%;
      height: 75%;
      background: #000;
      border: 1px solid var(--card-border);
      border-radius: 12px;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .terminal-header {
      background: #14161d;
      padding: 12px 24px;
      border-bottom: 1px solid var(--card-border);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .terminal-body {
      flex-grow: 1;
      padding: 12px;
    }

    .close-btn {
      color: var(--text-muted);
      cursor: pointer;
      font-size: 20px;
    }

    .close-btn:hover {
      color: var(--text-main);
    }

    /* Tabs content */
    .tab-content {
      display: none;
    }

    .tab-content.active {
      display: block;
    }

    /* Template Form */
    .template-form { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .template-form .full { grid-column: 1 / -1; }
    .template-form label { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; display: block; }
    .template-form input, .template-form select, .template-form textarea {
      width: 100%; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--card-border);
      background: rgba(0,0,0,0.3); color: var(--text-main); font-size: 13px; font-family: var(--font-mono);
    }
    .template-form textarea { min-height: 60px; resize: vertical; }
    .template-form input:focus, .template-form select:focus, .template-form textarea:focus {
      outline: none; border-color: var(--accent);
    }
    .template-actions { display: flex; gap: 8px; align-items: center; }
    .tag { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 11px;
           background: rgba(59,130,246,0.1); color: var(--accent); margin: 2px; }
    .result-box { background: rgba(16,185,129,0.1); border: 1px solid var(--green); border-radius: 8px;
                  padding: 12px; margin-top: 12px; font-size: 13px; }
    .result-box.error { background: rgba(239,68,68,0.1); border-color: var(--red); }
    .result-box pre { font-family: var(--font-mono); font-size: 12px; margin-top: 8px;
                      background: rgba(0,0,0,0.3); padding: 8px; border-radius: 6px; }
  </style>
</head>
<body x-data="dashboard()"
      x-init="authed && $nextTick(function() { document.querySelectorAll('[hx-get]').forEach(function(el) { htmx.trigger(el, 'load'); }); })">

  <!-- Auth gate (shown when no session token) -->
  <div x-show="!authed"
       x-data="{ loading: false, error: '' }"
       class="fixed inset-0 z-50 flex items-center justify-center"
       style="background: rgba(13,14,17,0.98);"
       x-cloak>
    <div style="width: 360px;" class="glass-card">
      <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 4px;">VATN Containers</h2>
      <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 20px;">Sign in with your admin credentials.</p>
      <input id="login-user" type="text" placeholder="Username"
             style="width:100%; padding:8px 12px; border-radius:8px; border:1px solid var(--card-border); background:rgba(0,0,0,0.3); color:var(--text-main); font-size:13px; margin-bottom:12px; outline:none;"/>
      <input id="login-pass" type="password" placeholder="Password"
             style="width:100%; padding:8px 12px; border-radius:8px; border:1px solid var(--card-border); background:rgba(0,0,0,0.3); color:var(--text-main); font-size:13px; margin-bottom:20px; outline:none;"/>
      <button @click="
        const u = document.getElementById('login-user').value.trim();
        const p = document.getElementById('login-pass').value.trim();
        if (!u || !p) return;
        loading = true; error = '';
        fetch('/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: u, password: p })
        }).then(function(r) { return r.json(); }).then(function(data) {
          if (data.accessToken) {
            sessionStorage.setItem('vatn_admin_token', data.accessToken);
            authed = true;
            setTimeout(function() {
              document.querySelectorAll('[hx-get]').forEach(function(el) { htmx.trigger(el, 'load'); });
            }, 100);
          } else {
            error = 'Login failed: ' + (data.error || 'no accessToken');
            loading = false;
          }
        }).catch(function() {
          fetch('/vatn/admin/api/overview', {
            headers: { 'Authorization': 'Bearer ' + p }
          }).then(function(r) {
            if (r.ok) {
              sessionStorage.setItem('vatn_admin_token', p);
              authed = true;
              setTimeout(function() {
                document.querySelectorAll('[hx-get]').forEach(function(el) { htmx.trigger(el, 'load'); });
              }, 100);
            } else {
              error = 'Login failed (status ' + r.status + ')';
              loading = false;
            }
          });
        });
      " style="width:100%; padding:8px 16px; border-radius:8px; border:none; background:var(--accent); color:#fff; font-weight:500; cursor:pointer; font-size:13px;"
      x-text="loading ? 'Signing in\u2026' : 'Sign in'">
        Sign in
      </button>
      <p x-show="error" x-text="error" style="color: var(--red); font-size: 12px; margin-top: 12px;"></p>
    </div>
  </div>

  <!-- Authenticated content -->
  <div x-show="authed" x-cloak>

  <!-- Full-width top nav bar (spans sidebar + content, like a desktop menu bar) -->
  <nav class="top-nav">
    <span class="nav-brand">&#x2B21; VATN</span>
    <a href="/vatn/admin" class="nav-link">Dashboard</a>
    <span class="nav-link nav-current">Containers</span>
    <span id="top-node" class="nav-meta">&#8212;</span>
    <span id="top-flavor" class="nav-meta">&#8212;</span>
    <span id="top-uptime" class="nav-meta">&#8212;</span>
  </nav>

  <!-- Sidebar -->
  <aside class="sidebar">
    <div class="logo-container">
      <span class="logo-icon">&#x2B21;</span>
      <span class="logo-text">VATN Containers</span>
    </div>
    
    <ul class="nav-list">
      <li class="nav-item" :class="{ 'active': currentTab === 'overview' }" @click="currentTab = 'overview'">
        <span>&#8862;</span> Overview
      </li>
      <li class="nav-item" :class="{ 'active': currentTab === 'containers' }" @click="currentTab = 'containers'">
        <span>&#9638;</span> Containers
      </li>
      <li class="nav-item" :class="{ 'active': currentTab === 'network' }" @click="currentTab = 'network'">
        <span>&#9072;</span> Network & Routes
      </li>
      <li class="nav-item" :class="{ 'active': currentTab === 'templates' }" @click="currentTab = 'templates'; showTemplateForm = false; editingTemplate = null; createResult = null">
        <span>&#9733;</span> Templates
      </li>
      <li class="nav-item" :class="{ 'active': currentTab === 'profiles' }"
          @click="currentTab = 'profiles'; showProfileForm = false; editingProfile = null">
        <span>&#9881;</span> Resource Profiles
      </li>
      <li class="nav-item" :class="{ 'active': currentTab === 'stacks' }"
          @click="currentTab = 'stacks'; showStackForm = false; editingStack = null; deployResult = null">
        <span>&#9776;</span> Stacks
      </li>
    </ul>
    
    <div style="font-size: 12px; color: var(--text-muted); border-top: 1px solid var(--card-border); padding-top: 16px;">
      Node: <span style="font-family: var(--font-mono); color: var(--text-main);" hx-get="__BASE__/api/node-id" hx-trigger="load">Loading...</span>
    </div>
  </aside>

  <!-- Main Content Area -->
  <main class="main-content">
    
    <div class="header-bar">
      <div class="title-group">
        <h1 x-text="currentTab.charAt(0).toUpperCase() + currentTab.slice(1)">Overview</h1>
        <p>Live stats and system operations dashboard.</p>
      </div>
      
      <!-- Refresh Trigger -->
      <button class="btn" hx-get="__BASE__/api/trigger-refresh" hx-swap="none" style="display: flex; align-items: center; gap: 8px;">
        <span>&#8634;</span> Force Sync
      </button>
    </div>

    <!-- TAB: Overview -->
    <div class="tab-content" :class="{ 'active': currentTab === 'overview' }">
      <div class="dashboard-grid">
        <!-- Node Status -->
        <div class="glass-card col-4">
          <div class="card-title">System Status</div>
          <div hx-get="__BASE__/api/system-status" hx-trigger="load, every 5s" style="font-size: 14px; line-height: 1.6;">
            Loading status metrics...
          </div>
        </div>

        <!-- Performance / Resource Monitor -->
        <div class="glass-card col-8">
          <div class="card-title">Resource Usage</div>
          <div hx-get="__BASE__/api/resources" hx-trigger="load, every 5s">
            Loading performance metrics...
          </div>
        </div>

        <!-- System Health Checks -->
        <div class="glass-card col-12">
          <div class="card-title">Lattice & Services Health</div>
          <div hx-get="__BASE__/api/health" hx-trigger="load, every 10s">
            Loading health states...
          </div>
        </div>
      </div>
    </div>

    <!-- TAB: Containers -->
    <div class="tab-content" :class="{ 'active': currentTab === 'containers' }">
      <div class="dashboard-grid">
        <!-- Distrobox Containers (Isolated display as requested) -->
        <div class="glass-card col-12">
          <div class="card-title">
            <span>Distrobox Environments</span>
          </div>
          <div hx-get="__BASE__/api/containers?engine=DISTROBOX" hx-trigger="load, every 5s">
            Loading Distrobox containers...
          </div>
        </div>

        <!-- Toolbox Containers (Fedora) -->
        <div class="glass-card col-12">
          <div class="card-title">
            <span>Toolbox Containers</span>
          </div>
          <div hx-get="__BASE__/api/containers?engine=TOOLBOX" hx-trigger="load, every 5s">
            Loading Toolbox containers...
          </div>
        </div>

        <!-- Docker / Podman Containers -->
        <div class="glass-card col-12">
          <div class="card-title">
            <span>Docker & Podman Containers</span>
          </div>
          <div hx-get="__BASE__/api/containers?engine=RAW" hx-trigger="load, every 5s">
            Loading system containers...
          </div>
        </div>
      </div>
    </div>

    <!-- TAB: Network & Routes -->
    <div class="tab-content" :class="{ 'active': currentTab === 'network' }">
      <div class="dashboard-grid">
        <!-- HTTP endpoints and network routes -->
        <div class="glass-card col-12">
          <div class="card-title">Registered HTTP Routes</div>
          <div hx-get="__BASE__/api/routes" hx-trigger="load">
            Loading HTTP routes...
          </div>
        </div>
      </div>
    </div>

    <!-- TAB: Templates -->
    <div class="tab-content" :class="{ 'active': currentTab === 'templates' }">
      <div class="dashboard-grid">
        <!-- Template List -->
        <div class="glass-card col-12" x-show="!showTemplateForm">
          <div class="card-title">
            <span>Container Templates</span>
            <button class="btn" @click="showTemplateForm = true; editingTemplate = null; createResult = null">
              + New Template
            </button>
          </div>
          <div hx-get="__BASE__/api/templates" hx-trigger="load, every 10s" hx-swap="innerHTML">
            Loading templates...
          </div>
        </div>

        <!-- Template Form / Editor -->
        <div class="glass-card col-12" x-show="showTemplateForm" x-cloak>
          <div class="card-title">
            <span x-text="editingTemplate ? 'Edit Template' : 'New Template'">Template Editor</span>
            <div class="template-actions">
              <button class="btn" @click="saveTemplate()">Save</button>
              <button class="btn" style="background: transparent; border: 1px solid var(--card-border); color: var(--text-muted);"
                      @click="showTemplateForm = false; editingTemplate = null; createResult = null">Cancel</button>
            </div>
          </div>
          <div class="template-form">
            <div>
              <label>Name</label>
              <input type="text" x-model="editingTemplate ? editingTemplate.name : templateName" placeholder="my-web-app"/>
            </div>
            <div>
              <label>Engine</label>
              <select x-model="editingTemplate ? editingTemplate.engine : templateEngine">
                <option value="PODMAN">Podman</option>
                <option value="DOCKER">Docker</option>
                <option value="DISTROBOX">Distrobox</option>
                <option value="TOOLBOX">Toolbox</option>
              </select>
            </div>
            <div>
              <label>Image</label>
              <input type="text" x-model="editingTemplate ? editingTemplate.image : templateImage" placeholder="nginx:latest"/>
            </div>
            <div>
              <label>Container Name (optional)</label>
              <input type="text" x-model="editingTemplate ? editingTemplate.containerName : templateContainerName" placeholder="web1"/>
            </div>
            <div class="full">
              <label>Ports (one per line, e.g. 8080:80)</label>
              <textarea x-model="editingTemplate ? editingTemplate.ports.join('\\n') : templatePorts" placeholder="8080:80"></textarea>
            </div>
            <div class="full">
              <label>Environment Variables (one per line, e.g. ENV=prod)</label>
              <textarea x-model="editingTemplate ? Object.entries(editingTemplate.env).map(([k,v]) => k+'='+v).join('\\n') : templateEnv" placeholder="ENV=prod"></textarea>
            </div>
            <div>
              <label>Resource Profile</label>
              <select x-model="editingTemplate ? editingTemplate.resourceProfileId : templateProfileId">
                <option value="">None</option>
                <template x-for="p in profiles" :key="p.id">
                  <option :value="p.id" x-text="p.name"></option>
                </template>
              </select>
            </div>
            <div class="full">
              <label>Post-Start Commands (one per line)</label>
              <textarea x-model="editingTemplate ? (editingTemplate.postStartCommands || []).join('\\n') : templatePostStart" placeholder="touch /tmp/ready&#10;echo 'started'"></textarea>
            </div>
          </div>
        </div>

        <!-- Create Result -->
        <div class="glass-card col-12" x-show="createResult" x-cloak>
          <div class="card-title">Creation Result</div>
          <div class="result-box" :class="{ 'error': createResult?.error }">
            <template x-if="createResult?.error">
              <div><strong>Error:</strong> <span x-text="createResult.error"></span></div>
            </template>
            <template x-if="!createResult?.error">
              <div>
                <div><strong>Container ID:</strong> <span x-text="createResult?.containerId" style="font-family: var(--font-mono);"></span></div>
                <div style="margin-top: 8px;"><strong>Post-Start Results:</strong></div>
                <template x-for="(r, i) in (createResult?.postStartResults || [])" :key="i">
                  <div style="font-size: 12px; margin-top: 4px;">
                    <span>Cmd <span x-text="i+1"></span>: exit code <span x-text="r.exitCode" style="font-family: var(--font-mono);"></span></span>
                    <template x-if="r.stdout">
                      <pre x-text="r.stdout"></pre>
                    </template>
                  </div>
                </template>
              </div>
            </template>
            <button class="btn" style="margin-top: 12px;" @click="createResult = null">Dismiss</button>
          </div>
        </div>

        <!-- Templates List (HTMX rendered) -->
        <div class="glass-card col-12">
          <div class="card-title">
            <span>Saved Templates</span>
          </div>
          <table>
            <thead>
              <tr><th>Name</th><th>Image</th><th>Engine</th><th>Ports</th><th>Post-Start</th><th>Actions</th></tr>
            </thead>
            <tbody hx-get="__BASE__/api/templates" hx-trigger="load, every 10s" hx-swap="innerHTML">
              <tr><td colspan="6" style="text-align:center; color: var(--text-muted);">Loading...</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- TAB: Resource Profiles -->
    <div class="tab-content" :class="{ 'active': currentTab === 'profiles' }">
      <div class="dashboard-grid">

        <!-- Profile Form -->
        <div class="glass-card col-12" x-show="showProfileForm" x-cloak>
          <div class="card-title">
            <span x-text="editingProfile ? 'Edit Profile' : 'New Profile'">Profile Editor</span>
            <div class="template-actions">
              <button class="btn" @click="saveProfile()">Save</button>
              <button class="btn" style="background: transparent; border: 1px solid var(--card-border); color: var(--text-muted);"
                      @click="showProfileForm = false; editingProfile = null">Cancel</button>
            </div>
          </div>
          <div class="template-form">
            <div>
              <label>Name</label>
              <input type="text" x-model="profileName" placeholder="high-performance"/>
            </div>
            <div>
              <label>GPU Mode</label>
              <select x-model="profileGpuMode">
                <option value="none">None</option>
                <option value="all">All GPUs</option>
                <option value="count:1">1 GPU</option>
                <option value="count:2">2 GPUs</option>
              </select>
            </div>
            <div>
              <label>CPU Max</label>
              <input type="text" x-model="profileCpuMax" placeholder="2.0"/>
            </div>
            <div>
              <label>Memory Max</label>
              <input type="text" x-model="profileMemoryMax" placeholder="512m"/>
            </div>
            <div class="full">
              <label>
                Device Mounts (one per line, e.g. /dev/sda:/dev/sda:rwm)
                <span style="font-size:11px;color:var(--text-muted);margin-left:8px;">
                  <a href="#" @click.prevent="showProfileCli = !showProfileCli"
                     x-text="showProfileCli ? 'Show structured form' : 'Edit raw CLI flags'"
                     style="color:var(--accent);cursor:pointer;text-decoration:none;"></a>
                </span>
              </label>
              <div x-show="!showProfileCli">
                <textarea x-model="profileDevices" placeholder="/dev/sda:/dev/sda:rwm" rows="2"></textarea>
              </div>
              <div x-show="showProfileCli">
                <textarea x-model="profileExtraCli" placeholder="--cpus 2 --memory 512m --device /dev/sda:/dev/sda:rwm --gpus all" rows="3"></textarea>
              </div>
            </div>
          </div>
        </div>

        <!-- Profile List -->
        <div class="glass-card col-12">
          <div class="card-title">
            <span>Resource Profiles</span>
            <button class="btn" @click="showProfileForm = true; editingProfile = null">+ New Profile</button>
          </div>
          <table>
            <thead>
              <tr><th>Name</th><th>CPU</th><th>Memory</th><th>Devices</th><th>GPU</th><th>Actions</th></tr>
            </thead>
            <tbody hx-get="__BASE__/api/profiles" hx-trigger="load, every 10s">
              <tr><td colspan="6" style="text-align:center;color:var(--text-muted);">Loading...</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- TAB: Stacks -->
    <div class="tab-content" :class="{ 'active': currentTab === 'stacks' }">
      <div class="dashboard-grid">

        <!-- Stack Form -->
        <div class="glass-card col-12" x-show="showStackForm" x-cloak>
          <div class="card-title">
            <span x-text="editingStack ? 'Edit Stack' : 'New Stack'">Stack Editor</span>
            <div class="template-actions">
              <button class="btn" @click="saveStack()">Save</button>
              <button class="btn" style="background: transparent; border: 1px solid var(--card-border); color: var(--text-muted);"
                      @click="showStackForm = false; editingStack = null">Cancel</button>
            </div>
          </div>
          <div class="template-form">
            <div>
              <label>Name</label>
              <input type="text" x-model="stackName" placeholder="my-app-stack"/>
            </div>
            <div>
              <label>Description</label>
              <input type="text" x-model="stackDescription" placeholder="My application stack"/>
            </div>
          </div>
          <div style="font-size: 12px; color: var(--text-muted); margin-top: 8px;">
            Services are managed via the template system. Create templates first, then add services to this stack.
          </div>
        </div>

        <!-- Deploy Result -->
        <div class="glass-card col-12" x-show="deployResult" x-cloak>
          <div class="card-title">Deployment Result</div>
          <div class="result-box" :class="{ 'error': deployResult?.overallStatus === 'FAILED' || deployResult?.overallStatus === 'DEGRADED' }">
            <div><strong>Status:</strong> <span x-text="deployResult?.overallStatus" style="font-weight: 600;"></span></div>
            <template x-if="deployResult?.results">
              <div style="margin-top: 8px;">
                <table>
                  <thead><tr><th>Service</th><th>Status</th><th>Container</th><th>Error</th></tr></thead>
                  <tbody>
                    <template x-for="r in deployResult.results" :key="r.serviceName">
                      <tr>
                        <td x-text="r.serviceName" style="font-weight: 500;"></td>
                        <td><span class="status-pill" :class="r.status === 'RUNNING' ? 'status-running' : 'status-stopped'" x-text="r.status"></span></td>
                        <td style="font-family: var(--font-mono); font-size: 12px; color: var(--text-muted);" x-text="r.containerId || '-'"></td>
                        <td style="font-size: 12px; color: var(--red);" x-text="r.error || ''"></td>
                      </tr>
                    </template>
                  </tbody>
                </table>
              </div>
            </template>
            <button class="btn" style="margin-top: 12px;" @click="deployResult = null">Dismiss</button>
          </div>
        </div>

        <!-- Stack List -->
        <div class="glass-card col-12">
          <div class="card-title">
            <span>Application Stacks</span>
            <button class="btn" @click="showStackForm = true; editingStack = null; deployResult = null">+ New Stack</button>
          </div>
          <table>
            <thead>
              <tr><th>Name</th><th>Description</th><th>Services</th><th>Actions</th></tr>
            </thead>
            <tbody hx-get="__BASE__/api/stacks" hx-trigger="load, every 10s">
              <tr><td colspan="4" style="text-align:center;color:var(--text-muted);">Loading...</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

  </main>

  <!-- Interactive Terminal overlay (Dynamic xterm.js integration) -->
  <div class="terminal-overlay" x-show="termOpen" style="display: none;">
    <div class="terminal-container" @click.away="termOpen = false">
      <div class="terminal-header">
        <span style="font-weight: 600;" x-text="termTitle">Container Terminal</span>
        <span class="close-btn" @click="termOpen = false">&times;</span>
      </div>
      <div class="terminal-body" id="xterm-hook"></div>
    </div>
  </div>

  <script>
    let ws = null;
    let term = null;

    function dashboard() {
      return {
        authed: !!sessionStorage.getItem('vatn_admin_token'),
        currentTab: 'containers',
        termOpen: false,
        termTitle: '',
        showTemplateForm: false,
        editingTemplate: null,
        createResult: null,
        templateName: '',
        templateEngine: 'PODMAN',
        templateImage: '',
        templateContainerName: '',
        templatePorts: '',
        templateEnv: '',
        templatePostStart: '',
        showProfileForm: false,
        editingProfile: null,
        profileName: '',
        profileCpuMin: '',
        profileCpuMax: '',
        profileMemoryMin: '',
        profileMemoryMax: '',
        profileDevices: '',
        profileGpuMode: 'none',
        profileExtraCli: '',
        showProfileCli: false,
        templateProfileId: '',
        profiles: [],
        showStackForm: false,
        editingStack: null,
        deployResult: null,
        stackName: '',
        stackDescription: '',

        editTemplate(t) {
          this.editingTemplate = t;
          this.showTemplateForm = true;
          this.createResult = null;
        },

        async saveTemplate() {
          const t = this.editingTemplate || {};
          const ports = (this.editingTemplate ? (t.ports || []) : (this.templatePorts ? this.templatePorts.split('\\n').filter(Boolean) : []));
          const envArr = this.editingTemplate ? Object.entries(t.env || {}).map(([k,v]) => k+'='+v) : (this.templateEnv ? this.templateEnv.split('\\n').filter(Boolean) : []);
          const env = {};
          envArr.forEach(line => { const [k, ...v] = line.split('='); if(k) env[k.trim()] = v.join('=').trim(); });
          const postStart = this.editingTemplate ? (t.postStartCommands || []) : (this.templatePostStart ? this.templatePostStart.split('\\n').filter(Boolean) : []);
          const body = {
            id: t.id || null,
            name: this.editingTemplate ? t.name : this.templateName,
            description: '',
            engine: this.editingTemplate ? t.engine : this.templateEngine,
            image: this.editingTemplate ? t.image : this.templateImage,
            containerName: this.editingTemplate ? t.containerName : this.templateContainerName,
            resourceProfileId: t.resourceProfileId || this.templateProfileId || null,
            ports,
            env,
            postStartCommands: postStart,
            postStartWaitMs: 0
          };

          try {
            const resp = await fetch('__BASE__/api/templates', {
              method: 'POST',
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify(body)
            });
            if (!resp.ok) throw new Error('Save failed: ' + (await resp.text()));
            this.showTemplateForm = false;
            this.editingTemplate = null;
            // trigger HTMX refresh
            document.querySelector('[hx-get="__BASE__/api/templates"]')?.dispatchEvent(new CustomEvent('htmx:load'));
            htmx.trigger('body', 'htmx:load');
          } catch(e) {
            alert('Error: ' + e.message);
          }
        },

        deleteTemplate(id) {
          if (!confirm('Delete this template?')) return;
          fetch('__BASE__/api/templates/' + id, { method: 'DELETE' })
            .then(() => htmx.trigger('body', 'htmx:load'))
            .catch(e => alert('Error: ' + e.message));
        },

        async createFromTemplate(t) {
          this.editingTemplate = t;
          try {
            const resp = await fetch('__BASE__/api/containers/create', {
              method: 'POST',
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({ templateId: t.id })
            });
            const result = await resp.json();
            this.createResult = result;
          } catch(e) {
            this.createResult = { error: e.message };
          }
        },

        init() {
          fetch('__BASE__/api/profiles').then(r => r.json()).then(d => this.profiles = d).catch(() => {});
        },

        editProfile(p) {
          this.editingProfile = p;
          this.showProfileForm = true;
          this.profileName = p.name;
          this.profileCpuMin = p.cpuMin || '';
          this.profileCpuMax = p.cpuMax || '';
          this.profileMemoryMin = p.memoryMin || '';
          this.profileMemoryMax = p.memoryMax || '';
          this.profileDevices = (p.deviceMounts || []).join('\\n');
          this.profileGpuMode = p.gpuMode || 'none';
          this.profileExtraCli = p.extraCliArgs || '';
        },

        async saveProfile() {
          const body = {
            id: this.editingProfile?.id || null,
            name: this.profileName,
            description: '',
            cpuMin: this.profileCpuMin || null,
            cpuMax: this.profileCpuMax || null,
            memoryMin: this.profileMemoryMin || null,
            memoryMax: this.profileMemoryMax || null,
            deviceMounts: this.profileDevices ? this.profileDevices.split('\\n').filter(Boolean) : [],
            gpuMode: this.profileGpuMode,
            extraCliArgs: this.profileExtraCli || null,
            createdAt: this.editingProfile?.createdAt || 0
          };
          const resp = await fetch('__BASE__/api/profiles', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
          });
          if (!resp.ok) throw new Error('Save failed');
          this.showProfileForm = false;
          this.editingProfile = null;
          htmx.trigger('body', 'htmx:load');
        },

        deleteProfile(id) {
          if (!confirm('Delete this profile?')) return;
          fetch('__BASE__/api/profiles/' + id, { method: 'DELETE' })
            .then(() => htmx.trigger('body', 'htmx:load'));
        },

        editStack(s) {
          this.editingStack = s;
          this.showStackForm = true;
          this.stackName = s.name;
          this.stackDescription = s.description || '';
          this.deployResult = null;
        },

        async saveStack() {
          const body = {
            id: this.editingStack?.id || null,
            name: this.stackName,
            description: this.stackDescription,
            services: this.editingStack?.services || [],
            createdAt: this.editingStack?.createdAt || 0
          };
          const resp = await fetch('__BASE__/api/stacks', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
          });
          if (!resp.ok) throw new Error('Save failed');
          this.showStackForm = false;
          this.editingStack = null;
          htmx.trigger('body', 'htmx:load');
        },

        deleteStack(id) {
          if (!confirm('Delete this stack?')) return;
          fetch('__BASE__/api/stacks/' + id, { method: 'DELETE' })
            .then(() => htmx.trigger('body', 'htmx:load'));
        },

        async deployStack(id) {
          try {
            const resp = await fetch('__BASE__/api/stacks/' + id + '/deploy', {
              method: 'POST'
            });
            this.deployResult = await resp.json();
          } catch(e) {
            this.deployResult = { overallStatus: 'FAILED', error: e.message };
          }
        }
      };
    }

    window.openTerminal = function(engine, containerId, containerName) {
      Alpine.store('currentTab', 'containers');
      let alpine = document.querySelector('body').__x__;
      if (alpine) {
        alpine.$data.termOpen = true;
        alpine.$data.termTitle = 'Terminal: ' + containerName + ' (' + engine + ')';
      }

      const container = document.getElementById('xterm-hook');
      container.innerHTML = '';

      term = new Terminal({
        cursorBlink: true,
        fontFamily: 'JetBrains Mono, monospace',
        fontSize: 14,
        theme: {
          background: '#000000',
          foreground: '#f3f4f6'
        }
      });
      term.open(container);

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = protocol + '//' + window.location.host + '__BASE__/ws/exec?engine=' + engine.toLowerCase() + '&id=' + containerId;
      
      ws = new WebSocket(wsUrl);

      ws.onopen = () => { term.write('Connected to container shell.\\r\\n'); };
      ws.onmessage = (event) => { term.write(event.data); };
      ws.onclose = () => { term.write('\\r\\nConnection closed.\\r\\n'); };
      ws.onerror = (err) => { term.write('\\r\\nWebSocket error: ' + err.message + '\\r\\n'); };
      term.onData((data) => { if (ws && ws.readyState === WebSocket.OPEN) ws.send(data); });
    };
  </script>
  <script>
    document.body.addEventListener('htmx:configRequest', function(evt) {
      const token = sessionStorage.getItem('vatn_admin_token');
      if (token) evt.detail.headers['Authorization'] = 'Bearer ' + token;
    });
  </script>
</div> <!-- /authenticated content -->
</body>
</html>
""";
}
