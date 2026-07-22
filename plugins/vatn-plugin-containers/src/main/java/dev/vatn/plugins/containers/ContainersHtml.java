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
  <title>VATN ZimaOS Dashboard</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
  
  <!-- CDNs for HTMX, Alpine, and xterm -->
  <script src="https://unpkg.com/htmx.org@1.9.10"></script>
  <script src="https://unpkg.com/alpinejs@3.x.x/dist/cdn.min.js" defer></script>
  <link rel="stylesheet" href="https://unpkg.com/xterm@5.3.0/css/xterm.css" />
  <script src="https://unpkg.com/xterm@5.3.0/lib/xterm.js"></script>

  <style>
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
      min-height: 100 screen;
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
    .sidebar {
      width: 260px;
      background: var(--sidebar-bg);
      border-right: 1px solid var(--card-border);
      display: flex;
      flex-direction: column;
      height: 100vh;
      position: fixed;
      left: 0;
      top: 0;
      z-index: 10;
      padding: 24px;
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
      padding: 40px;
      overflow-y: auto;
      height: 100vh;
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
  </style>
</head>
<body x-data="{ currentTab: 'overview', termOpen: false, termTitle: '' }">

  <!-- Sidebar -->
  <aside class="sidebar">
    <div class="logo-container">
      <span class="logo-icon">&#x2B21;</span>
      <span class="logo-text">VATN ZimaOS</span>
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

    window.openTerminal = function(engine, containerId, containerName) {
      Alpine.store('currentTab', 'containers'); // ensure state aligns
      document.querySelector('body').__x__.$data.termOpen = true;
      document.querySelector('body').__x__.$data.termTitle = 'Terminal: ' + containerName + ' (' + engine + ')';

      // Reset xterm Hook
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

      // Setup WebSocket connection (pointing to our WebTerminalHandler ws endpoint)
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = protocol + '//' + window.location.host + '__BASE__/ws/exec?engine=' + engine.toLowerCase() + '&id=' + containerId;
      
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        term.write('Connected to container shell.\\r\\n');
      };

      ws.onmessage = (event) => {
        term.write(event.data);
      };

      ws.onclose = () => {
        term.write('\\r\\nConnection closed.\\r\\n');
      };

      ws.onerror = (err) => {
        term.write('\\r\\nWebSocket error: ' + err.message + '\\r\\n');
      };

      term.onData((data) => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(data);
        }
      });
    };
  </script>
</body>
</html>
""";
}
