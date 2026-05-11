let entities = [];
let activeJobIds = [];
let pollTimer = null;
const PAGE_SIZE = 4;
let historyPage = 1;
let entityPage = 1;
let historyRowsCache = [];
let historyServicePathCache = '';
let historyFilter = '';
let entityFilter = '';

function onHistorySearch() {
  historyFilter = (document.getElementById('historySearch').value || '').toLowerCase();
  historyPage = 1;
  renderHistoryTable();
}

function onEntitySearch() {
  entityFilter = (document.getElementById('entitySearch').value || '').toLowerCase();
  entityPage = 1;
  renderEntityTable();
}

function renderPager(totalItems, currentPage, onPageChange) {
  const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE));
  const page = Math.min(Math.max(1, currentPage), totalPages);
  if (totalItems <= PAGE_SIZE) return '';
  const startIdx = (page - 1) * PAGE_SIZE + 1;
  const endIdx = Math.min(totalItems, page * PAGE_SIZE);
  const disPrev = page <= 1 ? 'disabled' : '';
  const disNext = page >= totalPages ? 'disabled' : '';
  return '<div class="pager">'
    + '<span>Showing ' + startIdx + '\u2013' + endIdx + ' of ' + totalItems + '</span>'
    + '<button class="btn-refresh" data-pager="first" ' + disPrev + '>&laquo; First</button>'
    + '<button class="btn-refresh" data-pager="prev" ' + disPrev + '>&lsaquo; Prev</button>'
    + '<span>Page ' + page + ' / ' + totalPages + '</span>'
    + '<button class="btn-refresh" data-pager="next" ' + disNext + '>Next &rsaquo;</button>'
    + '<button class="btn-refresh" data-pager="last" ' + disNext + '>Last &raquo;</button>'
    + '</div>';
}

function wirePager(containerEl, totalItems, currentPage, setPage) {
  const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE));
  containerEl.querySelectorAll('button[data-pager]').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.pager;
      let p = currentPage;
      if (action === 'first') p = 1;
      else if (action === 'prev') p = Math.max(1, currentPage - 1);
      else if (action === 'next') p = Math.min(totalPages, currentPage + 1);
      else if (action === 'last') p = totalPages;
      setPage(p);
    });
  });
}

async function callDelta(path, method, label) {
  const area = document.getElementById('deltaArea');
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> ' + label + '...</div>';
  try {
    const res = await fetch(path, { method: method });
    const data = await res.json();
    const ok = data.success;
    const color = ok ? 'var(--success,#22c55e)' : 'var(--danger,#ef4444)';
    let html = '<div style="margin-bottom:6px;color:' + color + ';font-weight:600">'
             + (ok ? 'OK' : 'FAILED') + ' — ' + (data.functionName || '') + '</div>'
             + '<div style="font-family:monospace;font-size:11px;color:#888;margin-bottom:6px;word-break:break-all">' + (data.url || '') + '</div>';
    if (ok) {
      const pretty = JSON.stringify(data.result, null, 2);
      html += '<pre style="background:#1e1e1e;color:#d4d4d4;padding:10px;border-radius:4px;overflow:auto;max-height:300px;font-size:12px">'
            + pretty.replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c])) + '</pre>';
    } else {
      html += '<div style="color:var(--danger);font-family:monospace;font-size:12px;white-space:pre-wrap">' + (data.error || 'Unknown error') + '</div>';
    }
    area.innerHTML = html;
  } catch (e) {
    area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Request failed: ' + e.message + '</div>';
  }
}

async function checkDeltaStatus(entity) {
  if (!entity) return;
  const svc = document.getElementById('serviceSelect').value || '';
  const qs = '?entitySet=' + encodeURIComponent(entity) + (svc ? '&service=' + encodeURIComponent(svc) : '');
  await callDelta('/api/delta/status' + qs, 'GET', 'Checking subscription status for ' + entity);
}

async function resetDelta(entity) {
  if (!entity) return;
  if (!confirm('Terminate the delta subscription for "' + entity + '" on SAP?\n\nThis drops the change-tracking chain. The next delta run will start a fresh full load.')) return;
  const svc = document.getElementById('serviceSelect').value || '';
  const qs = '?entitySet=' + encodeURIComponent(entity) + (svc ? '&service=' + encodeURIComponent(svc) : '');
  await callDelta('/api/delta/reset' + qs, 'POST', 'Terminating delta subscription for ' + entity);
}

async function loadHistory() {
  const area = document.getElementById('historyArea');
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading...</div>';
  try {
    const res = await fetch('/api/history');
    const data = await res.json();
    if (!data.available) {
      area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Database not reachable — history unavailable</div>';
      return;
    }
    historyRowsCache = data.rows || [];
    historyPage = 1;
    updateKpis();
    renderHistoryTable();
  } catch (e) {
    area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Failed to load: ' + e.message + '</div>';
  }
}

function updateKpis() {
  const rows = historyRowsCache || [];
  const total = rows.length;
  let running = 0, completed = 0, failed = 0;
  for (const r of rows) {
    const s = (r.state || '').toLowerCase();
    if (s === 'running' || s === 'queued') running++;
    else if (s === 'completed') completed++;
    else if (s === 'failed') failed++;
  }
  const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v.toLocaleString(); };
  set('kpiTotal', total);
  set('kpiRunning', running);
  set('kpiCompleted', completed);
  set('kpiFailed', failed);
}

function renderHistoryTable() {
  const area = document.getElementById('historyArea');
  if (!historyRowsCache || historyRowsCache.length === 0) {
    area.innerHTML = '<div class="empty-state">No historical jobs yet</div>';
    return;
  }
  const filtered = historyFilter
    ? historyRowsCache.filter(r => {
        const hay = [r.jobId, r.entitySet, r.mode, r.state, r.error, r.deltaToken]
          .map(v => (v == null ? '' : String(v).toLowerCase())).join(' ');
        return hay.indexOf(historyFilter) >= 0;
      })
    : historyRowsCache;
  if (filtered.length === 0) {
    area.innerHTML = '<div class="empty-state">No jobs match "' + escapeHtml(historyFilter) + '"</div>';
    return;
  }
  const total = filtered.length;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  if (historyPage > totalPages) historyPage = totalPages;
  const startIdx = (historyPage - 1) * PAGE_SIZE;
  const pageRows = filtered.slice(startIdx, startIdx + PAGE_SIZE);
  const badgeClass = s => 'status-badge badge-' + (s || 'queued');
  let html = '<table class="data-table"><thead><tr>'
    + '<th>Job ID</th><th>Entity Set</th><th>Mode</th><th>State</th>'
    + '<th class="cell-num">Records</th><th>Started</th><th>Completed</th><th class="cell-num">Duration</th><th>Delta Token</th><th>Logs</th>'
    + '</tr></thead><tbody>';
  let cards = '<div class="data-cards">';
  try {
    for (const r of pageRows) {
      const errMsg = r.error ? String(r.error) : '';
      const errEsc = errMsg.replace(/[<>&"]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c]));
      const stateBadge = '<span title="' + errEsc + '" class="' + badgeClass(r.state) + '">' + (r.state || '') + '</span>';
      const durationCell = formatDuration(r.startedAt, r.completedAt, r.state);
      const entityEsc = escapeHtml(r.entitySet || '');
      const tokenStr = r.deltaToken ? String(r.deltaToken) : '';
      const tokenEscQ = tokenStr.replace(/"/g, '&quot;');
      const logsBtn = '<button class="btn btn-secondary btn-sm" data-action="view-logs" data-job-id="' + r.jobId + '" data-entity="' + entityEsc + '" data-state="' + (r.state || '') + '">View Logs</button>';
      html += '<tr>'
        + '<td>' + r.jobId + '</td>'
        + '<td>' + entityEsc + '</td>'
        + '<td>' + (r.mode || '') + '</td>'
        + '<td>' + stateBadge + '</td>'
        + '<td class="cell-num">' + (r.recordCount ?? 0).toLocaleString() + '</td>'
        + '<td class="cell-mono cell-muted">' + (r.startedAt || '') + '</td>'
        + '<td class="cell-mono cell-muted">' + (r.completedAt || '') + '</td>'
        + '<td class="cell-num">' + durationCell + '</td>'
        + '<td class="cell-mono cell-truncate" title="' + tokenEscQ + '">' + escapeHtml(tokenStr) + '</td>'
        + '<td>' + logsBtn + '</td>'
        + '</tr>';
      cards += '<div class="data-card">'
        + '<div class="data-card-row"><span class="dc-key">Job #' + r.jobId + '</span>' + stateBadge + '</div>'
        + '<div class="data-card-row"><span class="dc-key">Entity</span><span class="dc-val">' + entityEsc + '</span></div>'
        + '<div class="data-card-row"><span class="dc-key">Mode</span><span class="dc-val">' + (r.mode || '') + '</span></div>'
        + '<div class="data-card-row"><span class="dc-key">Records</span><span class="dc-val">' + (r.recordCount ?? 0).toLocaleString() + '</span></div>'
        + '<div class="data-card-row"><span class="dc-key">Duration</span><span class="dc-val">' + durationCell + '</span></div>'
        + '<div class="data-card-row"><span class="dc-key">Started</span><span class="dc-val cell-mono">' + (r.startedAt || '') + '</span></div>'
        + (r.completedAt ? '<div class="data-card-row"><span class="dc-key">Completed</span><span class="dc-val cell-mono">' + r.completedAt + '</span></div>' : '')
        + (tokenStr ? '<div class="data-card-row"><span class="dc-key">Delta Token</span><span class="dc-val cell-mono" style="word-break:break-all">' + escapeHtml(tokenStr) + '</span></div>' : '')
        + '<div class="data-card-actions">' + logsBtn + '</div>'
        + '</div>';
    }
    html += '</tbody></table>';
    cards += '</div>';
    const pager = renderPager(total, historyPage, p => { historyPage = p; renderHistoryTable(); });
    area.innerHTML = html + cards + pager;
    wirePager(area, total, historyPage, p => { historyPage = p; renderHistoryTable(); });
  } catch (e) {
    area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Failed to render: ' + e.message + '</div>';
  }
}

async function loadEntities() {
  const tableDiv = document.getElementById('entityTable');
  tableDiv.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading entity sets...</div>';
  try {
    const svc = document.getElementById('serviceSelect').value || '';
    const url = svc ? ('/api/entities?service=' + encodeURIComponent(svc)) : '/api/entities';
    const res = await fetch(url);
    const data = await res.json();
    if (data.error) throw new Error(data.error);
    entities = data.entitySets || [];
    entityPage = 1;
    renderEntityTable(data.servicePath);
  } catch (e) {
    tableDiv.innerHTML = '<div class="empty-state" style="color:var(--danger)">Failed to load: ' +
      escapeHtml(e.message) + '</div>';
  }
}

async function loadServices() {
  try {
    const res = await fetch('/api/services');
    const data = await res.json();
    const sel = document.getElementById('serviceSelect');
    sel.innerHTML = '';
    (data.servicePaths || []).forEach(p => {
      const opt = document.createElement('option');
      opt.value = p;
      opt.textContent = p;
      if (p === data.defaultServicePath) opt.selected = true;
      sel.appendChild(opt);
    });
    setConnStatus('ok', 'connected');
    const meta = document.getElementById('serviceMeta');
    if (meta) meta.textContent = data.defaultServicePath || '';
  } catch (e) {
    setConnStatus('bad', 'offline');
    console.warn('Failed to load services:', e);
  }
}

function setConnStatus(kind, label) {
  const el = document.getElementById('connStatus');
  const txt = document.getElementById('connStatusText');
  if (!el || !txt) return;
  el.classList.remove('conn-ok', 'conn-bad', 'conn-unknown');
  el.classList.add('conn-' + kind);
  txt.textContent = label;
}

// ============== Toast notifications ==============
function showToast(message, type) {
  const host = document.getElementById('toasts');
  if (!host) return;
  const t = document.createElement('div');
  t.className = 'toast toast-' + (type || 'info');
  t.innerHTML = '<div class="toast-msg"></div><button class="toast-close" aria-label="Dismiss">&times;</button>';
  t.querySelector('.toast-msg').textContent = message;
  t.querySelector('.toast-close').addEventListener('click', () => t.remove());
  host.appendChild(t);
  setTimeout(() => { if (t.parentNode) t.remove(); }, 5000);
}

function renderEntityTable(servicePath) {
  const tableDiv = document.getElementById('entityTable');
  if (servicePath !== undefined) historyServicePathCache = servicePath;
  const svcPath = historyServicePathCache;
  if (entities.length === 0) {
    tableDiv.innerHTML = '<div class="empty-state">No entity sets found</div>';
    return;
  }
  const filteredPairs = entities
    .map((name, idx) => ({ name, idx }))
    .filter(p => !entityFilter || p.name.toLowerCase().indexOf(entityFilter) >= 0);
  document.getElementById('entityCount').textContent = (entityFilter
    ? (filteredPairs.length + ' of ' + entities.length)
    : entities.length) + ' entity sets from ' + svcPath;
  if (filteredPairs.length === 0) {
    tableDiv.innerHTML = '<div class="empty-state">No entity sets match "' + escapeHtml(entityFilter) + '"</div>';
    return;
  }
  const isV4 = (svcPath || '').toLowerCase().indexOf('/odata4/') >= 0;
  const total = filteredPairs.length;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  if (entityPage > totalPages) entityPage = totalPages;
  const startIdx = (entityPage - 1) * PAGE_SIZE;
  const pageItems = filteredPairs.slice(startIdx, startIdx + PAGE_SIZE);
  // Two variants of the mode select: desktop (with id) and mobile (no id, to avoid duplicates).
  const modeSelectHtml = (i, withId) => isV4
    ? '<select class="mode-select"' + (withId ? ' id="mode_' + i + '"' : '') + ' disabled title="OData v4: only Full (Delta Disabled) is supported"><option value="full_no_delta" selected>Full (Delta Disabled)</option></select>'
    : '<select class="mode-select"' + (withId ? ' id="mode_' + i + '"' : '') + '><option value="full_no_delta">Full (Delta Disabled)</option><option value="full">Full</option><option value="delta">Delta</option></select>';
  let html = '<table class="data-table"><thead><tr>' +
    '<th>Entity Set</th><th>Mode</th><th>Action</th></tr></thead><tbody>';
  let cards = '<div class="data-cards">';
  pageItems.forEach(p => {
    const name = p.name;
    const i = p.idx;
    const nameEsc = escapeHtml(name);
    const deltaButtons = isV4
      ? ''
      : '<button class="btn btn-secondary btn-sm" data-action="delta-status" data-entity="' + nameEsc + '" title="SubscribedTo' + nameEsc + '">Check Status</button> ' +
        '<button class="btn btn-danger btn-sm" data-action="delta-reset" data-entity="' + nameEsc + '" title="TerminateDeltasFor' + nameEsc + '">Reset Delta</button>';
    const runBtn = '<button class="btn btn-primary btn-sm" data-action="run" data-entity="' + nameEsc + '" data-idx="' + i + '">&#x25B6; Run Extraction</button>';
    html += '<tr>' +
      '<td>' + nameEsc + '</td>' +
      '<td>' + modeSelectHtml(i, true) + '</td>' +
      '<td class="cell-actions">' +
        runBtn + ' ' +
        deltaButtons +
      '</td>' +
      '</tr>';
    cards += '<div class="data-card">'
      + '<div class="data-card-row"><span class="dc-key">Entity</span><span class="dc-val"><strong>' + nameEsc + '</strong></span></div>'
      + '<div class="data-card-row"><span class="dc-key">Mode</span><span class="dc-val">' + modeSelectHtml(i, false) + '</span></div>'
      + '<div class="data-card-actions">' + runBtn + ' ' + deltaButtons + '</div>'
      + '</div>';
  });
  html += '</tbody></table>';
  cards += '</div>';
  const pager = renderPager(total, entityPage, p => { entityPage = p; renderEntityTable(); });
  tableDiv.innerHTML = html + cards + pager;
  wirePager(tableDiv, total, entityPage, p => { entityPage = p; renderEntityTable(); });
  // Wire up per-row buttons via delegation (avoids inline-onclick quoting issues)
  tableDiv.querySelectorAll('button[data-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      const entity = btn.dataset.entity;
      if (action === 'run') {
        // Find the mode select in the same row/card. Both desktop and mobile views
        // share the id `mode_<idx>`, so we scope by the nearest container instead.
        const container = btn.closest('tr, .data-card');
        const sel = container ? container.querySelector('select.mode-select') : null;
        const mode = sel ? sel.value : 'full_no_delta';
        runExtractionFor(entity, mode);
      } else if (action === 'delta-status') checkDeltaStatus(entity);
      else if (action === 'delta-reset') resetDelta(entity);
    });
  });
  // React to mode changes to enable/disable the Parallel input
  tableDiv.querySelectorAll('select.mode-select').forEach(sel => {
    sel.addEventListener('change', updateBtn);
  });
  updateBtn();
}

function toggleAll() { /* removed: per-row Run replaces bulk selection */ }

function updateBtn() {
  // Enable Parallel/$top inputs only when at least one row's mode is full_no_delta
  let anyFullNoDelta = false;
  document.querySelectorAll('select.mode-select').forEach(sel => {
    if (sel.value === 'full_no_delta') anyFullNoDelta = true;
  });
  const pIn = document.getElementById('parallelInput');
  if (pIn) {
    pIn.disabled = !anyFullNoDelta;
    pIn.style.opacity = anyFullNoDelta ? '1' : '0.5';
    pIn.title = anyFullNoDelta
      ? 'Number of parallel workers for Full (Delta Disabled) mode'
      : 'Enabled only when a selected entity uses Full (Delta Disabled) mode';
  }
  const psIn = document.getElementById('pageSizeInput');
  if (psIn) {
    psIn.disabled = !anyFullNoDelta;
    psIn.style.opacity = anyFullNoDelta ? '1' : '0.5';
    psIn.title = anyFullNoDelta
      ? '$top page size for Full (Delta Disabled) mode'
      : 'Enabled only when a selected entity uses Full (Delta Disabled) mode';
  }
}

async function runExtractionFor(entitySet, mode) {
  const format = document.getElementById('formatSelect').value;
  const parallelCalls = parseInt(document.getElementById('parallelInput').value, 10) || 1;
  const pageSize = parseInt(document.getElementById('pageSizeInput').value, 10) || 5000;
  const service = document.getElementById('serviceSelect').value || null;
  const entitySets = [entitySet];

  appendLog('Starting ' + mode + ' extraction for ' + entitySet + '...');

  try {
    let res = await fetch('/api/extract', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ entitySets, mode, format, parallelCalls, pageSize, service })
    });
    let data = await res.json();
    if (res.status === 409 && data.conflict) {
      const proceed = confirm(data.message
        + '\n\nDo you want to terminate the running extraction(s) and start a new one?');
      if (!proceed) {
        appendLog('Skipped: ' + data.message);
        return;
      }
      res = await fetch('/api/extract', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ entitySets, mode, format, parallelCalls, pageSize, service, force: true })
      });
      data = await res.json();
    }
    if (data.error) throw new Error(data.error);
    appendLog(data.message);
    activeJobIds.push(...(data.jobIds || []));
    loadHistory();
    startPolling();
  } catch (e) {
    appendLog('ERROR: ' + e.message, 'error');
  }
}

function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(pollJobs, 30000);
  pollJobs();
}

async function pollJobs() {
  try {
    const res = await fetch('/api/jobs');
    const allJobs = await res.json();

    // Refresh persisted history while jobs are active
    loadHistory();

    // Stop polling if all active jobs are done
    const running = allJobs.filter(j => j.state === 'queued' || j.state === 'running');
    if (running.length === 0 && activeJobIds.length > 0) {
      clearInterval(pollTimer);
      pollTimer = null;
      loadHistory();
    }
  } catch (e) { /* ignore poll errors */ }
}

function appendLog(msg, type) {
  // Surface action feedback as toast notifications; also keep console trace.
  console.log('[ui]', msg);
  showToast(msg, type === 'error' ? 'error' : 'info');
}

let logSinceId = 0;
let logTimer = null;
let logsModalJobId = null;
let logsModalJobState = '';
const LOG_LEVEL_COLORS = { ERROR: 'var(--danger,#ef4444)', WARN: '#d97706', INFO: '#475569', DEBUG: '#94a3b8' };

function openLogsModal(jobId, entitySet, state) {
  logsModalJobId = jobId;
  logsModalJobState = state;
  logSinceId = 0;
  document.getElementById('logsModalTitle').textContent =
    'Job #' + jobId + ' — ' + (entitySet || '') + ' (' + (state || '') + ')';
  document.getElementById('logsModal').style.display = 'flex';
  document.getElementById('logArea').innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading...</div>';
  fetchLogs(true);
  // Auto-poll only while the job is still running
  if (state === 'running' || state === 'queued') {
    if (document.getElementById('logAuto').checked) startLogPolling();
  }
}

function closeLogsModal() {
  document.getElementById('logsModal').style.display = 'none';
  stopLogPolling();
  logsModalJobId = null;
}

function toggleAutoLogs() {
  if (!logsModalJobId) return;
  if (document.getElementById('logAuto').checked &&
      (logsModalJobState === 'running' || logsModalJobState === 'queued')) {
    startLogPolling();
  } else {
    stopLogPolling();
  }
}

function startLogPolling() {
  if (logTimer) return;
  logTimer = setInterval(() => fetchLogs(false), 30000);
}

function stopLogPolling() {
  if (logTimer) { clearInterval(logTimer); logTimer = null; }
}

async function fetchLogs(replace) {
  if (!logsModalJobId) return;
  const params = new URLSearchParams();
  params.set('jobId', String(logsModalJobId));
  if (!replace && logSinceId > 0) params.set('since', String(logSinceId));
  params.set('limit', '500');
  try {
    const res = await fetch('/api/logs?' + params.toString());
    const data = await res.json();
    const area = document.getElementById('logArea');
    if (!data.available) {
      area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Database not reachable — logs unavailable</div>';
      return;
    }
    const rows = data.rows || [];
    if (replace || !document.getElementById('logsTable')) {
      area.innerHTML = '<table id="logsTable"><thead><tr>'
        + '<th style="width:170px">Time</th>'
        + '<th style="width:70px">Level</th>'
        + '<th style="width:90px">Job ID</th>'
        + '<th>Message</th>'
        + '</tr></thead><tbody id="logsBody"></tbody></table>';
    }
    const body = document.getElementById('logsBody');
    if (replace) body.innerHTML = '';
    if (rows.length === 0 && replace) {
      area.innerHTML = '<div class="empty-state">No logs yet</div>';
      return;
    }
    let frag = '';
    for (const r of rows) {
      logSinceId = Math.max(logSinceId, r.id);
      const color = LOG_LEVEL_COLORS[r.level] || '#475569';
      frag += '<tr>'
        + '<td style="font-family:monospace;font-size:12px;color:#666">' + (r.ts || '') + '</td>'
        + '<td style="font-weight:600;color:' + color + '">' + (r.level || 'INFO') + '</td>'
        + '<td style="font-family:monospace">' + r.jobId + '</td>'
        + '<td style="font-family:monospace;font-size:12px">' + escapeHtml(r.message || '') + '</td>'
        + '</tr>';
    }
    if (frag) {
      body.insertAdjacentHTML('beforeend', frag);
      // Cap rows to last 1000 to keep DOM small
      while (body.rows.length > 1000) body.deleteRow(0);
    }
  } catch (e) {
    // ignore transient poll errors
  }
}

function escapeHtml(str) {
  const d = document.createElement('div');
  d.textContent = str;
  return d.innerHTML;
}

function formatDuration(startedAt, completedAt, state) {
  if (!startedAt) return '';
  // Server timestamps look like "2026-05-08 10:29:04.488". Treat as local time.
  const start = new Date(String(startedAt).replace(' ', 'T'));
  if (isNaN(start.getTime())) return '';
  let end;
  if (completedAt) {
    end = new Date(String(completedAt).replace(' ', 'T'));
    if (isNaN(end.getTime())) return '';
  } else if (state === 'running' || state === 'queued') {
    end = new Date();
  } else {
    return '';
  }
  let ms = end - start;
  if (ms < 0) ms = 0;
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const pad = n => n.toString().padStart(2, '0');
  return (h > 0 ? h + ':' + pad(m) : m) + ':' + pad(s);
}

// ============== Tabs / Sidenav ==============
const TAB_LABELS = { extract: 'Extract', history: 'Job History', admin: 'Admin' };

function activateTab(name) {
  document.querySelectorAll('.sidenav-item').forEach(t => t.classList.toggle('sidenav-active', t.dataset.tab === name));
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('panel-active'));
  const target = document.getElementById('panel-' + name);
  if (target) target.classList.add('panel-active');
  const crumb = document.getElementById('crumbCurrent');
  if (crumb) crumb.textContent = TAB_LABELS[name] || name;
  if (name === 'admin') loadAdminServices();
  // Close mobile drawer after navigation
  const sidenav = document.getElementById('sidenav');
  if (sidenav) sidenav.classList.remove('open');
  const navToggle = document.getElementById('navToggle');
  if (navToggle) navToggle.setAttribute('aria-expanded', 'false');
}

function wireTabs() {
  document.querySelectorAll('.sidenav-item').forEach(item => {
    item.addEventListener('click', () => activateTab(item.dataset.tab));
  });
  const toggle = document.getElementById('navToggle');
  if (toggle) {
    toggle.addEventListener('click', () => {
      const sidenav = document.getElementById('sidenav');
      const open = sidenav.classList.toggle('open');
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }
}

// ============== Admin: Service Catalog ==============
let adminServicesCache = [];

async function loadAdminServices() {
  const area = document.getElementById('adminArea');
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading services\u2026</div>';
  try {
    const res = await fetch('/api/admin/services');
    if (res.status === 503) {
      area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Database not available \u2014 service catalog requires PostgreSQL.</div>';
      return;
    }
    const data = await res.json();
    adminServicesCache = data.services || [];
    renderAdminServices();
  } catch (e) {
    area.innerHTML = '<div class="empty-state" style="color:var(--danger)">Failed to load: ' + escapeHtml(e.message) + '</div>';
  }
}

function renderAdminServices() {
  const area = document.getElementById('adminArea');
  const rows = adminServicesCache;
  if (!rows.length) {
    area.innerHTML = '<div class="empty-state">No services defined. Click <strong>+ New Service</strong> to add one.</div>';
    return;
  }
  let html = '<table class="data-table"><thead><tr>'
    + '<th>Name</th><th>Service Path</th><th>Base URL</th><th>Username</th>'
    + '<th>Client</th><th style="text-align:center">Default</th><th>Updated</th><th>Actions</th>'
    + '</tr></thead><tbody>';
  let cards = '<div class="data-cards">';
  for (const r of rows) {
    const def = r.isDefault
      ? '<span class="status-badge badge-completed">Default</span>'
      : '<span class="cell-muted">\u2014</span>';
    const editBtn = '<button class="btn btn-secondary btn-sm" data-admin-action="edit" data-id="' + r.id + '">Edit</button>';
    const delBtn = '<button class="btn btn-danger btn-sm" data-admin-action="delete" data-id="' + r.id + '">Remove</button>';
    html += '<tr>'
      + '<td><strong>' + escapeHtml(r.name || '') + '</strong></td>'
      + '<td class="cell-mono cell-truncate" title="' + escapeHtml(r.servicePath || '') + '">' + escapeHtml(r.servicePath || '') + '</td>'
      + '<td class="cell-mono cell-muted cell-truncate" title="' + escapeHtml(r.baseUrl || '') + '">' + escapeHtml(r.baseUrl || '\u2014') + '</td>'
      + '<td>' + escapeHtml(r.username || '\u2014') + '</td>'
      + '<td>' + escapeHtml(r.sapClient || '\u2014') + '</td>'
      + '<td style="text-align:center">' + def + '</td>'
      + '<td class="cell-muted" style="font-size:12px">' + escapeHtml(r.updatedAt || '') + '</td>'
      + '<td class="cell-actions">' + editBtn + ' ' + delBtn + '</td>'
      + '</tr>';
    cards += '<div class="data-card">'
      + '<div class="data-card-row"><span class="dc-key"><strong>' + escapeHtml(r.name || '') + '</strong></span>' + def + '</div>'
      + '<div class="data-card-row"><span class="dc-key">Path</span><span class="dc-val cell-mono" style="word-break:break-all">' + escapeHtml(r.servicePath || '') + '</span></div>'
      + (r.baseUrl ? '<div class="data-card-row"><span class="dc-key">Base URL</span><span class="dc-val cell-mono" style="word-break:break-all">' + escapeHtml(r.baseUrl) + '</span></div>' : '')
      + (r.username ? '<div class="data-card-row"><span class="dc-key">User</span><span class="dc-val">' + escapeHtml(r.username) + '</span></div>' : '')
      + (r.sapClient ? '<div class="data-card-row"><span class="dc-key">Client</span><span class="dc-val">' + escapeHtml(r.sapClient) + '</span></div>' : '')
      + '<div class="data-card-actions">' + editBtn + ' ' + delBtn + '</div>'
      + '</div>';
  }
  html += '</tbody></table>';
  cards += '</div>';
  area.innerHTML = html + cards;
  area.querySelectorAll('button[data-admin-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = parseInt(btn.dataset.id, 10);
      if (btn.dataset.adminAction === 'edit') openServiceForm(id);
      else if (btn.dataset.adminAction === 'delete') deleteService(id);
    });
  });
}

function openServiceForm(id) {
  document.getElementById('svcFormError').textContent = '';
  document.getElementById('serviceForm').reset();
  document.getElementById('svcId').value = '';
  if (id) {
    const row = adminServicesCache.find(r => r.id === id);
    if (row) {
      document.getElementById('serviceModalTitle').textContent = 'Edit Service';
      document.getElementById('svcId').value = row.id;
      document.getElementById('svcName').value = row.name || '';
      document.getElementById('svcPath').value = row.servicePath || '';
      document.getElementById('svcBaseUrl').value = row.baseUrl || '';
      document.getElementById('svcUser').value = row.username || '';
      document.getElementById('svcPassword').value = '';
      document.getElementById('svcClient').value = row.sapClient || '';
      document.getElementById('svcPrefer').value = row.preferHeader || '';
      document.getElementById('svcIsDefault').value = row.isDefault ? 'true' : 'false';
    }
  } else {
    document.getElementById('serviceModalTitle').textContent = 'New Service';
  }
  document.getElementById('serviceModal').style.display = 'flex';
}

function closeServiceForm() {
  document.getElementById('serviceModal').style.display = 'none';
}

async function saveService(ev) {
  ev.preventDefault();
  const id = document.getElementById('svcId').value;
  const payload = {
    name: document.getElementById('svcName').value.trim(),
    servicePath: document.getElementById('svcPath').value.trim(),
    baseUrl: document.getElementById('svcBaseUrl').value.trim() || null,
    username: document.getElementById('svcUser').value.trim() || null,
    sapClient: document.getElementById('svcClient').value.trim() || null,
    preferHeader: document.getElementById('svcPrefer').value.trim() || null,
    isDefault: document.getElementById('svcIsDefault').value === 'true'
  };
  const pw = document.getElementById('svcPassword').value;
  // Send password only when user typed one. Server preserves the existing password
  // when the field is absent from the PUT payload.
  if (pw) payload.password = pw;
  const errEl = document.getElementById('svcFormError');
  errEl.textContent = '';
  try {
    const url = id ? '/api/admin/services/' + id : '/api/admin/services';
    const method = id ? 'PUT' : 'POST';
    const res = await fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (!res.ok) {
      errEl.textContent = data.error || ('HTTP ' + res.status);
      return;
    }
    showToast(id ? 'Service updated' : 'Service created', 'success');
    closeServiceForm();
    await loadAdminServices();
    // Refresh service dropdown on Extract panel
    await loadServices();
  } catch (e) {
    errEl.textContent = e.message;
  }
}

async function deleteService(id) {
  const row = adminServicesCache.find(r => r.id === id);
  const label = row ? row.name : ('#' + id);
  if (!confirm('Remove service "' + label + '" from the catalog?\n\nIt will be marked as deleted and hidden from the UI. The record is retained for audit and can be restored from the database.')) return;
  try {
    const res = await fetch('/api/admin/services/' + id, { method: 'DELETE' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    showToast('Service removed', 'success');
    await loadAdminServices();
    await loadServices();
  } catch (e) {
    showToast('Remove failed: ' + e.message, 'error');
  }
}

// Auto-load entities and history on page open
window.addEventListener('load', async () => {
  wireTabs();
  await loadServices();
  loadEntities();
  loadHistory();
  // Delegated handler for per-row "View Logs" buttons
  document.getElementById('historyArea').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-action="view-logs"]');
    if (!btn) return;
    openLogsModal(parseInt(btn.dataset.jobId, 10), btn.dataset.entity, btn.dataset.state);
  });
});
// Close logs modal with Escape
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    closeLogsModal();
    closeServiceForm();
  }
});
