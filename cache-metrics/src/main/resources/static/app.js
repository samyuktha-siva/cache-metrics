const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let toastTimer;

function toast(msg, isError) {
  const t = $('toast');
  t.textContent = msg;
  t.className = isError ? 'error' : '';
  t.style.display = 'block';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.style.display = 'none', 3000);
}

async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: {'Content-Type': 'application/json'},
    body: body !== undefined ? JSON.stringify(body) : undefined
  });
  let data = null;
  try { data = await res.json(); } catch (e) { /* empty body */ }
  return {ok: res.ok, status: res.status, data};
}

function renderMetrics(m) {
  $('m-size').textContent = m.currentSize;
  $('m-capacity').textContent = m.capacity;
  $('m-hitRate').textContent = m.hitRate + '%';
  $('m-missRate').textContent = m.missRate + '%';
  $('m-total').textContent = m.totalGets;
  $('m-evictions').textContent = m.evictions;
  $('m-expirations').textContent = m.expirations;
  $('m-hits').textContent = m.hits;
  $('m-misses').textContent = m.misses;
  $('m-puts').textContent = m.puts;
  $('hitLbl').textContent = m.hitRate + '%';
  $('missLbl').textContent = m.missRate + '%';
  $('hitBar').style.width = m.hitRate + '%';
  $('missBar').style.width = m.missRate + '%';
  $('policyBadge').textContent = m.evictionPolicy;
}

function renderEntries(list) {
  $('entries').innerHTML = list.length ? list.map(e => {
    const ttl = e.remainingTtlMs < 0 ? '∞' : (e.remainingTtlMs / 1000).toFixed(1) + 's';
    return `<tr><td>${esc(e.key)}</td><td>${esc(e.value)}</td><td>${e.frequency}</td><td>${ttl}</td>` +
           `<td><span class="badge">${e.status}</span></td></tr>`;
  }).join('') : '<tr><td colspan="5" class="muted">Cache is empty</td></tr>';
}

function renderLog(list) {
  $('log').innerHTML = list.length ? list.map(l =>
    `<li><span class="t">${l.time}</span><span class="tag ${l.type.replace(' ', '-')}">${esc(l.type)}</span><span>${esc(l.message)}</span></li>`
  ).join('') : '<li class="muted">No activity yet</li>';
}

async function refresh() {
  try {
    const [m, e, l] = await Promise.all([api('GET', '/api/cache/metrics'), api('GET', '/api/cache/entries'), api('GET', '/api/cache/log')]);
    renderMetrics(m.data);
    renderEntries(e.data);
    renderLog(l.data);
    $('status').textContent = 'Cache Online';
    $('status').className = 'badge online';
  } catch (err) {
    $('status').textContent = 'Cache Offline';
    $('status').className = 'badge offline';
  }
}

async function run(fn) {
  try { await fn(); } catch (err) { toast('Request failed: ' + err.message, true); }
  refresh();
}

$('btnApply').onclick = () => run(async () => {
  const r = await api('PUT', '/api/cache/config', {capacity: parseInt($('cfgCapacity').value, 10), policy: $('cfgPolicy').value});
 if (r.ok) {
  toast('Configuration applied');
} else {
  console.log('CONFIG ERROR:', r.status, r.data);
  toast(
    'Config failed: ' +
    (r.data?.message || 'HTTP ' + r.status),
    true
  );
}
});

$('btnPut').onclick = () => run(async () => {
  const ttl = $('putTtl').value;
  const r = await api('POST', '/api/cache', {key: $('putKey').value, value: $('putValue').value, ttlSeconds: ttl === '' ? null : parseInt(ttl, 10)});
  r.ok ? toast('Stored "' + r.data.key + '"') : toast(r.data.message, true);
});

$('btnGet').onclick = () => run(async () => {
  const key = $('getKey').value.trim();
  if (!key) { toast('Key is required', true); return; }
  const r = await api('GET', '/api/cache/' + encodeURIComponent(key));
  $('getResult').textContent = r.data && r.data.hit ? 'HIT  →  ' + r.data.value : 'MISS';
});

$('btnRemove').onclick = () => run(async () => {
  const key = $('rmKey').value.trim();
  if (!key) { toast('Key is required', true); return; }
  const r = await api('DELETE', '/api/cache/' + encodeURIComponent(key));
  r.ok ? toast('Removed "' + key + '"') : toast(r.data.message, true);
});

$('btnClear').onclick = () => run(async () => {
  if (!confirm('Clear the entire cache?')) return;
  await api('DELETE', '/api/cache');
  toast('Cache cleared');
});

$('btnSample').onclick = () => run(async () => {
  $('btnSample').disabled = true;
  $('demoResult').textContent = 'Running sample workload (~2s)…';
  try {
    const r = await api('POST', '/api/cache/sample-workload');
    const m = r.data.metrics;
    $('demoResult').textContent = `Done. Hits=${m.hits}, Misses=${m.misses}, Puts=${m.puts}, Evictions=${m.evictions}, Expirations=${m.expirations}`;
    toast('Sample workload complete');
  } finally { $('btnSample').disabled = false; }
});

$('btnConc').onclick = () => run(async () => {
  $('btnConc').disabled = true;
  $('demoResult').textContent = 'Running concurrency test…';
  try {
    const r = await api('POST', '/api/cache/concurrency-test');
    const d = r.data;
    $('demoResult').textContent = `Threads=${d.threads}, Completed=${d.completed}/${d.totalOperations}, Errors=${d.errors}, Time=${d.durationMs}ms`;
    toast('Concurrency test complete');
  } finally { $('btnConc').disabled = false; }
});

refresh();
setInterval(refresh, 1500);