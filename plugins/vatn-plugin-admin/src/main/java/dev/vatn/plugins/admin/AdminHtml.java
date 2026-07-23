package dev.vatn.plugins.admin;

import dev.vatn.api.admin.VAdminContribution;
import java.util.List;
import java.util.stream.Collectors;

final class AdminHtml {

    private AdminHtml() {}

    static String render(String basePath, List<VAdminContribution> contributions) {
        String navLinks = contributions.stream()
            .filter(c -> c.path() != null && c.path().startsWith("/"))
            .map(c -> "<a href=\"" + esc(c.path()) + "\" class=\"text-gray-300 hover:text-white transition text-xs\">"
                   + esc(c.icon()) + " " + esc(c.title()) + "</a>")
            .collect(Collectors.joining("\n          "));
        return HTML
            .replace("__BASE__", basePath)
            .replace("<!-- CONTRIBUTED_LINKS -->", navLinks);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
  <script src="https://unpkg.com/htmx.org@2.0.4"></script>
  <script defer src="https://cdn.jsdelivr.net/npm/@alpinejs/collapse@3.x.x/dist/cdn.min.js"></script>
  <script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
</head>
<body class="min-h-screen font-mono text-sm"
      x-data="{ authed: !!sessionStorage.getItem('vatn_admin_token') }">

<!-- Nav bar -->
<nav class="sticky top-0 z-40 bg-gray-900/80 backdrop-blur border-b border-gray-800 px-6 py-3 flex items-center gap-4">
  <span class="text-blue-400 font-semibold tracking-tight">&#x2B21; VATN Admin</span>
  <a href="__BASE__" class="text-gray-300 hover:text-white transition text-xs">Dashboard</a>
  <!-- CONTRIBUTED_LINKS -->
  <span id="hdr-node" class="text-gray-500 text-xs ml-auto">&#8212;</span>
  <span id="hdr-flavor" class="text-gray-600 text-xs">&#8212;</span>
  <span id="hdr-uptime" class="text-gray-600 text-xs">&#8212;</span>
</nav>

<!-- Auth modal (Alpine.js) -->
<div x-show="!authed"
     class="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/95"
     style="display: none;">
  <div class="bg-gray-900 border border-gray-700 rounded-xl p-8 w-96 shadow-2xl">
    <h2 class="text-lg font-semibold text-white mb-1">VATN Admin</h2>
    <p class="text-gray-400 text-xs mb-6">Enter your bearer token to continue.</p>
    <input id="token-input" type="password" placeholder="Bearer token..."
           class="w-full bg-gray-800 border border-gray-600 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:border-blue-500 mb-4"/>
    <button @click="
      const t = document.getElementById('token-input').value.trim();
      if (t) {
        sessionStorage.setItem('vatn_admin_token', t);
        authed = true;
        setTimeout(function() {
          document.querySelectorAll('[hx-get]').forEach(function(el) {
            htmx.trigger(el, 'load');
          });
        }, 100);
      }
    " class="w-full bg-blue-600 hover:bg-blue-500 text-white rounded-lg py-2 font-medium transition">
      Sign in
    </button>
    <p id="auth-error" class="text-red-400 text-xs mt-3 hidden">Invalid token.</p>
  </div>
</div>

<!-- Main content (HTMX sections) -->
<main x-show="authed" style="display: none;"
      class="max-w-7xl mx-auto px-4 py-6 space-y-6">

  <!-- Row 1: Overview + Health -->
  <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Node</h2>
      <div hx-get="__BASE__/fragments/overview" hx-trigger="load, every 10s"
           class="text-xs">Loading overview...</div>
    </div>
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Health</h2>
      <div hx-get="__BASE__/fragments/health" hx-trigger="load, every 10s"
           class="text-xs">Loading health...</div>
    </div>
  </div>

  <!-- Plugins (full width) -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Plugins</h2>
    <div id="plugins-section" hx-get="__BASE__/fragments/plugins" hx-trigger="load, every 10s"
         class="text-xs">Loading plugins...</div>
  </div>

  <!-- JVM / System -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">JVM / System</h2>
    <div hx-get="__BASE__/fragments/jvm" hx-trigger="load, every 10s"
         class="text-xs">Loading JVM data...</div>
  </div>

  <!-- Workflows -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Workflow Runs</h2>
    <div hx-get="__BASE__/fragments/workflows" hx-trigger="load, every 10s"
         class="text-xs">Loading workflows...</div>
  </div>

  <!-- Workloads -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Workloads</h2>
    <div hx-get="__BASE__/fragments/workloads" hx-trigger="load, every 10s"
         class="text-xs">Loading workloads...</div>
  </div>

  <!-- Queues -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Named Queues</h2>
    <div hx-get="__BASE__/fragments/queues" hx-trigger="load, every 10s"
         class="text-xs">Loading queues...</div>
  </div>

  <!-- Routes -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Registered Routes</h2>
    <div hx-get="__BASE__/fragments/routes" hx-trigger="load, every 10s"
         class="text-xs">Loading routes...</div>
  </div>
</main>

<script>
  document.body.addEventListener('htmx:configRequest', function(evt) {
    const token = sessionStorage.getItem('vatn_admin_token');
    if (token) evt.detail.headers['Authorization'] = 'Bearer ' + token;
  });

  async function restartPlugin(id) {
    const token = sessionStorage.getItem('vatn_admin_token');
    const headers = token ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } : {};
    await fetch('__BASE__/api/plugins/' + encodeURIComponent(id) + '/restart', { method: 'POST', headers });
    setTimeout(() => htmx.trigger('#plugins-section', 'load'), 1500);
  }

  async function stopPlugin(id) {
    if (!confirm('Stop plugin ' + id + '?')) return;
    const token = sessionStorage.getItem('vatn_admin_token');
    const headers = token ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } : {};
    await fetch('__BASE__/api/plugins/' + encodeURIComponent(id) + '/stop', { method: 'POST', headers });
    setTimeout(() => htmx.trigger('#plugins-section', 'load'), 500);
  }

</script>
</body>
</html>
""";
}
