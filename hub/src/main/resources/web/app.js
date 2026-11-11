// Battery Hub dashboard. Plain JS, no dependencies: it must work with no internet.
"use strict";

const $ = (sel) => document.querySelector(sel);
let overview = null;
let openMac = null;

// --- Formatting (mirrors the Android app's) ------------------------------------------------

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const volts = (mv) => (mv / 1000).toFixed(2) + " V";
const amps = (ma) => (ma >= 0 ? "+" : "") + (ma / 1000).toFixed(1) + " A";
const watts = (mw) => (mw >= 0 ? "+" : "") + Math.round(mw / 1000) + " W";
const ah = (mah) => (mah < 100000 ? (mah / 1000).toFixed(1) : Math.round(mah / 1000)) + " Ah";
const energy = (mwh) => (mwh < 1e6 ? Math.round(mwh / 1000) + " Wh" : (mwh / 1e6).toFixed(2) + " kWh");
const celsius = (d) => (d / 10).toFixed(1) + " °C";
const capacity = (r) => r.fullChargeCapacityMah ?? r.nominalCapacityMah;
const nominalMv = (r) => (r.cellVoltagesMv.length ? r.cellVoltagesMv.length * 3200 : r.packVoltageMv);
const wiringLabel = (w) => ({ PARALLEL: "Parallel", SERIES: "Series", NONE: "Not wired together" }[w]);
const INTERVALS = [0, 1, 2, 5, 10, 15, 30, 60, 120]; // keep in sync with core POLL_INTERVAL_CHOICES
const intervalLabel = (m) =>
  m === 0 ? "Manual only" : m < 60 ? `Every ${m} min` : m === 60 ? "Every hour" : m % 60 === 0 ? `Every ${m / 60} hours` : `Every ${m} min`;
const label = (name) => name.toLowerCase().replaceAll("_", " ").replace(/^./, (c) => c.toUpperCase());

function age(now, then) {
  const s = Math.max(0, Math.floor((now - then) / 1000));
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

function signal(rssi) {
  const bars = rssi == null ? 0 : rssi >= -65 ? 4 : rssi >= -75 ? 3 : rssi >= -85 ? 2 : rssi >= -95 ? 1 : 0;
  const is = [1, 2, 3, 4].map((i) => `<i class="${i <= bars ? "on" : ""}" style="height:${4 + 3 * i}px"></i>`).join("");
  return `<span class="signal" title="Signal at the hub">${is}</span> <span class="small muted">${rssi == null ? "—" : rssi + " dBm"}</span>`;
}

function flow(r) {
  if (!r.chargeFetOn && !r.dischargeFetOn) return "Charge & discharge off";
  if (!r.chargeFetOn) return "Charge off";
  if (!r.dischargeFetOn) return "Discharge off";
  if (Math.abs(r.currentMa) < 100) return "Idle";
  return r.currentMa > 0 ? "Charging" : "Discharging";
}

// --- Time estimates (mirrors core Estimates.kt: formatDuration, describe, basis) ---------------

function duration(ms) {
  const min = Math.floor(ms / 60000);
  if (min < 1) return "under a minute";
  if (min < 60) return `${min} min`;
  if (min < 1440) return min % 60 === 0 ? `${min / 60} h` : `${Math.floor(min / 60)} h ${min % 60} min`;
  if (min < 144000) { const h = Math.floor(min / 60) % 24; return h === 0 ? `${Math.floor(min / 1440)} d` : `${Math.floor(min / 1440)} d ${h} h`; }
  return "over 100 days";
}

function describeEstimate(e, now) {
  if (e == null || e.targetPct == null || e.durationMs == null) return null;
  const charging = e.flow === "CHARGING", t = e.targetPct;
  const name = charging && t === 100 ? "Full" : !charging && t === 0 ? "Empty" : charging ? `Up to ${t}%` : `Down to ${t}%`;
  if (e.durationMs === 0) return charging && t === 100 ? "Full" : !charging && t === 0 ? "Empty" : charging ? `At or above ${t}%` : `At or below ${t}%`;
  const left = e.asOfMs + e.durationMs - now;
  return left <= 0 ? `${name} by now, if the rate held` : `${name} in ${duration(left)}`;
}

const basis = (e) => (e.spanMs === 0 ? "Based on the latest reading only" : `Average over the last ${duration(e.spanMs)}`);

/** "around 9:40 PM", with the weekday when it isn't today. */
function clockTime(ms, now) {
  const d = new Date(ms);
  const sameDay = d.toDateString() === new Date(now).toDateString();
  return "around " + d.toLocaleString([], sameDay ? { hour: "numeric", minute: "2-digit" } : { weekday: "short", hour: "numeric", minute: "2-digit" });
}

function estimateLine(e, now) {
  const text = describeEstimate(e, now);
  if (!text) return "";
  const eta = e.asOfMs + e.durationMs;
  const parts = [text];
  if (e.durationMs > 0 && eta > now) parts.push(clockTime(eta, now));
  if (!e.confident) parts.push("rough");
  return `<div class="estimate${e.confident ? "" : " rough"}" title="${esc(basis(e))}">⏱ ${esc(parts.join(" · "))}</div>`;
}

const thresholdsLabel = (t) => `Discharge to ${t?.dischargeToPct ?? 0}% · charge to ${t?.chargeToPct ?? 100}%`;

const bar = (pct) => `<div class="bar"><span style="width:${Math.max(0, Math.min(100, pct))}%"></span></div>`;

// --- Rendering ------------------------------------------------------------------------------

function aggregateBody(s, now, est) {
  const extra = [`${energy(s.energyRemainingMwh)} of ${energy(s.energyCapacityMwh)}`];
  if (s.voltageMv != null) extra.push(volts(s.voltageMv));
  if (s.currentMa != null) extra.push(amps(s.currentMa));
  extra.push(watts(s.powerMw));
  const count = s.batteryCount === 1 ? "1 battery" : `${s.batteryCount} batteries`;
  const partial = s.reportingCount < s.batteryCount;
  return `
    <div class="row"><span class="big">${s.stateOfChargePct}%</span><span>${ah(s.remainingMah)} of ${ah(s.capacityMah)}</span></div>
    ${bar(s.stateOfChargePct)}
    <div>${extra.join(" · ")}</div>
    ${estimateLine(est, now)}
    <div class="small ${partial ? "error" : "muted"}">${count} · ${partial ? `only ${s.reportingCount} have data · ` : ""}oldest ${age(now, s.oldestReadingMs)}</div>`;
}

function statusLine(b, now) {
  const parts = [];
  const r = b.reading;
  if (r) {
    parts.push(flow(r));
    if (r.protections.length) parts.push("⚠ " + r.protections.map(label).join(", "));
    parts.push(age(now, r.timestampMs));
  }
  if (b.status.state === "REFRESHING") parts.push("refreshing…");
  if (b.status.state === "FAILED") parts.push("last try failed");
  const warn = b.status.state === "FAILED" || (r && r.protections.length);
  return `<div class="small ${warn ? "error" : "muted"}">${esc(parts.join(" · "))}</div>`;
}

function batteryCard(b, now) {
  const r = b.reading;
  const body = r
    ? `<div class="row"><span class="big">${r.stateOfChargePct}%</span><span>${volts(r.packVoltageMv)}</span><span>${amps(r.currentMa)}</span>${
        r.temperaturesDeciC.length ? `<span>${celsius(r.temperaturesDeciC[0])}</span>` : ""}</div>
       ${bar(r.stateOfChargePct)}
       <div>${ah(r.remainingCapacityMah)} of ${ah(capacity(r))} · ${energy((r.remainingCapacityMah * nominalMv(r)) / 1000)} of ${energy((capacity(r) * nominalMv(r)) / 1000)}</div>
       ${estimateLine(b.estimate, now)}`
    : `<div class="muted">No data yet</div>`;
  return `<article class="card battery" data-mac="${esc(b.favorite.mac)}" tabindex="0">
    <div class="card-head"><span class="name">${esc(b.favorite.nickname)}</span>${signal(r?.rssiDbm)}
      <button type="button" class="more" data-mac="${esc(b.favorite.mac)}" aria-label="Actions for ${esc(b.favorite.nickname)}">⋮</button></div>
    ${body}${statusLine(b, now)}</article>`;
}

function render() {
  const o = overview;
  const now = o.nowMs;
  const macs = Object.keys(o.batteries);
  let html = "";
  if (!macs.length) {
    html = `<p class="muted">No favorites yet. Click <strong>Search</strong> to find batteries nearby, or run <code>battery-hub import-phone</code> to copy them from the phone.</p>`;
  }
  if (macs.length >= 2 && o.total) {
    html += `<section class="card total"><div class="card-head"><span class="name">All batteries</span>
      <button type="button" class="more" data-total="1" aria-label="Actions for all batteries">⋮</button></div>${aggregateBody(o.total, now, o.totalEstimate)}</section>`;
  }
  for (const g of o.groups) {
    html += `<section class="card group"><div class="card-head"><span class="name">${esc(g.group.name)}</span><span class="small">${wiringLabel(g.group.wiring)}</span>
      <button type="button" class="more" data-group="${esc(g.group.id)}" aria-label="Actions for group ${esc(g.group.name)}">⋮</button></div>
      ${g.members.length === 0 ? `<div class="small muted">No batteries yet</div>` : g.stats ? aggregateBody(g.stats, now, g.estimate) : `<div class="small muted">No data yet</div>`}</section>`;
    html += `<div class="grid">${g.members.map((m) => batteryCard(o.batteries[m], now)).join("")}</div>`;
  }
  if (o.groups.length && o.ungrouped.length) html += `<h3 class="section-title">Not in a group</h3>`;
  html += `<div class="grid">${o.ungrouped.map((m) => batteryCard(o.batteries[m], now)).join("")}</div>`;
  $("#overview").innerHTML = html;
  $("#updated").textContent = `Updated ${new Date(now).toLocaleTimeString()} · ${intervalLabel(o.settings.pollIntervalMinutes)}`;
  if (openMac) renderDetail();
}

function chart(points, value, fmt, alt) {
  if (points.length < 2) return `<p class="small muted">Not enough history yet.</p>`;
  const W = 680, H = 140, P = 28;
  const t0 = points[0].t, t1 = points[points.length - 1].t;
  const vs = points.map(value);
  let lo = Math.min(...vs), hi = Math.max(...vs);
  if (lo === hi) { lo -= 1; hi += 1; }
  const x = (t) => P + ((t - t0) / (t1 - t0 || 1)) * (W - P - 4);
  const y = (v) => 4 + (1 - (v - lo) / (hi - lo)) * (H - 24);
  const d = points.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)},${y(value(p)).toFixed(1)}`).join("");
  return `<svg class="chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img">
    <line class="axis" x1="${P}" y1="${H - 20}" x2="${W}" y2="${H - 20}"/>
    <text x="0" y="12">${fmt(hi)}</text><text x="0" y="${H - 22}">${fmt(lo)}</text>
    <text x="${P}" y="${H - 4}">${new Date(t0).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</text>
    <text x="${W - 2}" y="${H - 4}" text-anchor="end">${new Date(t1).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</text>
    <path class="line${alt ? " alt" : ""}" d="${d}"/></svg>`;
}

function estimateSection(e, t, now) {
  const field = (k, v) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`;
  let body;
  if (!e || e.flow === "IDLE") body = `<p class="small muted">Idle: no estimate while no current is flowing.</p>`;
  else {
    body = `${estimateLine(e, now)}<dl class="fields">${field("Average current", amps(e.rateMa))}${field("Rate", basis(e))}</dl>`;
    if (!e.confident) body += `<p class="small muted">Rough: ${e.spanMs === 0 ? "not enough history yet" : "the load or charger changed recently"}.</p>`;
  }
  return `<section class="card"><div class="card-head"><strong class="name">Time estimate</strong>
      <button type="button" id="detail-thresholds">Thresholds…</button></div>
    ${body}<div class="small muted">${esc(thresholdsLabel(t))}</div></section>`;
}

let historyCache = { mac: null, at: 0, points: [] };

async function loadHistory(mac) {
  if (historyCache.mac === mac && Date.now() - historyCache.at < 60000) return historyCache.points;
  const res = await fetch(`/api/batteries/${encodeURIComponent(mac)}/history?hours=24`);
  const points = res.ok ? await res.json() : [];
  historyCache = { mac, at: Date.now(), points };
  return points;
}

async function renderDetail() {
  const b = overview.batteries[openMac];
  if (!b) { $("#detail").close(); return; }
  $("#detail-title").textContent = b.favorite.nickname;
  const r = b.reading;
  const now = overview.nowMs;
  const field = (k, v, cls = "") => `<dt>${esc(k)}</dt><dd class="${cls}">${esc(v)}</dd>`;
  let html = `<div class="row">${signal(r?.rssiDbm)}</div>${statusLine(b, now)}`;
  if (b.status.state === "FAILED") html += `<div class="small error">Last refresh failed: ${esc(b.status.message ?? "")}</div>`;
  if (r) {
    const cells = r.cellVoltagesMv;
    const max = Math.max(...cells), min = Math.min(...cells);
    html += `
      <section class="card"><strong>Now</strong><div class="big">${r.stateOfChargePct}%</div><dl class="fields">
        ${field("Voltage", volts(r.packVoltageMv))}${field("Current", amps(r.currentMa))}
        ${field("Power", watts((r.packVoltageMv * r.currentMa) / 1000))}
        ${field("Remaining", `${ah(r.remainingCapacityMah)} of ${ah(capacity(r))}`)}
        ${r.temperaturesDeciC.map((t, i) => field(r.temperaturesDeciC.length === 1 ? "Temperature" : `Temperature ${i + 1}`, celsius(t))).join("")}
      </dl></section>
      ${estimateSection(b.estimate, b.favorite.thresholds, now)}
      <section class="card"><strong>Last 24 hours: charge</strong><div id="chart-soc"></div>
        <strong>Current</strong><div id="chart-current"></div></section>
      <section class="card"><strong>Cells</strong><dl class="fields">
        ${cells.map((mv, i) => field(`Cell ${i + 1}${(r.balancingMask >> i) & 1 ? " (balancing)" : ""}`, (mv / 1000).toFixed(3) + " V",
          max !== min && mv === max ? "hi" : max !== min && mv === min ? "lo" : "")).join("")}
        ${field("Spread (max − min)", `${max - min} mV`)}</dl></section>
      <section class="card"><strong>Status</strong><dl class="fields">
        ${field("Charging switch", r.chargeFetOn ? "On" : "Off")}${field("Discharge switch", r.dischargeFetOn ? "On" : "Off")}
        ${r.protections.length ? r.protections.map((p) => field("Protection", label(p), "error")).join("") : field("Protections", "None active")}
      </dl></section>
      <section class="card"><strong>Battery</strong><dl class="fields">
        ${field("Cycles", r.cycles)}${field("Rated capacity", ah(r.nominalCapacityMah))}
        ${r.fullChargeCapacityMah != null ? field("Full-charge capacity", ah(r.fullChargeCapacityMah)) : ""}
        ${field("Manufactured", r.productionDate)}${field("Model", r.model)}${field("BMS firmware", r.firmwareVersion)}
        ${field("Bluetooth address", b.favorite.mac)}</dl></section>`;
  } else {
    html += `<p>No data yet. Click Refresh now.</p>`;
  }
  $("#detail-body").innerHTML = html;
  $("#detail-thresholds")?.addEventListener("click", () => editThresholds(`/api/favorites/${encodeURIComponent(openMac)}/thresholds`, b.favorite.nickname, b.favorite.thresholds));
  if (r) {
    const points = await loadHistory(openMac);
    const soc = $("#chart-soc"), cur = $("#chart-current");
    if (soc) soc.innerHTML = chart(points, (p) => p.soc, (v) => Math.round(v) + "%");
    if (cur) cur.innerHTML = chart(points, (p) => p.currentMa / 1000, (v) => v.toFixed(1) + " A", true);
  }
}

// --- API (every change carries the header the hub requires) -----------------------------------

async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json", "X-Battery-Hub": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let msg = res.status + "";
    try { msg = (await res.json()).error ?? msg; } catch (_) {}
    throw new Error(msg);
  }
  return res.status === 204 ? null : res.json();
}

let toastTimer;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 4000);
}

/** Runs an action, shows errors, and refreshes the overview. */
async function act(fn) {
  try { await fn(); } catch (e) { toast(e.message); }
  poll();
}

// --- Modal dialogs -----------------------------------------------------------------------------

/** Shows the modal; resolves with { value, data } where value is the clicked button's value (or null). */
function modal(title, bodyHtml, buttons) {
  const dlg = $("#modal");
  $("#modal-title").textContent = title;
  $("#modal-body").innerHTML = bodyHtml;
  $("#modal-buttons").innerHTML = buttons
    .map((b) => `<button value="${esc(b.value)}" class="${b.cls ?? ""}" ${b.value === "cancel" ? 'formnovalidate' : ""}>${esc(b.label)}</button>`)
    .join("");
  return new Promise((resolve) => {
    dlg.addEventListener("close", () => {
      const v = dlg.returnValue;
      resolve({ value: v && v !== "cancel" ? v : null, data: new FormData($("#modal-form")) });
    }, { once: true });
    dlg.returnValue = "";
    dlg.showModal();
    dlg.querySelector("input[type=text]")?.focus();
  });
}

async function askName(title, initial, label = "Name") {
  const { value, data } = await modal(title,
    `<label>${esc(label)}<input type="text" name="name" maxlength="40" required value="${esc(initial)}"></label>`,
    [{ label: "Cancel", value: "cancel" }, { label: "Save", value: "ok", cls: "primary" }]);
  const name = (data.get("name") ?? "").trim();
  return value && name ? name : null;
}

/** Asks for estimate thresholds; blank = default. PUTs them to [url]. */
async function editThresholds(url, name, t) {
  const input = (field, label, placeholder, help) => `<label>${label}
      <input type="number" name="${field}" min="0" max="100" step="1" placeholder="${placeholder}" value="${t?.[field] ?? ""}">
      <span class="small muted">${help}</span></label>`;
  const { value, data } = await modal(`Thresholds for ${name}`,
    `<div class="thresholds">
       ${input("dischargeToPct", "Discharge to (%)", "0", "Blank = 0%")}
       ${input("chargeToPct", "Charge to (%)", "100", "Blank = 100%")}
     </div>
     <p class="small muted">Time estimates count toward these levels. Nothing is sent to the battery.</p>`,
    [{ label: "Cancel", value: "cancel" }, { label: "Reset", value: "reset" }, { label: "Save", value: "ok", cls: "primary" }]);
  if (!value) return;
  const num = (k) => { const v = (data.get(k) ?? "").trim(); return v === "" ? null : Number(v); };
  const body = value === "reset" ? {} : { dischargeToPct: num("dischargeToPct"), chargeToPct: num("chargeToPct") };
  return act(() => api("PUT", url, body));
}

const WIRINGS = ["PARALLEL", "SERIES", "NONE"];

async function askGroup(title, name, wiring) {
  const radios = WIRINGS.map((w) =>
    `<label class="choice"><input type="radio" name="wiring" value="${w}" ${w === wiring ? "checked" : ""}> ${wiringLabel(w)}</label>`).join("");
  const { value, data } = await modal(title,
    `<label>Group name<input type="text" name="name" maxlength="40" required value="${esc(name)}"></label>
     <div><div class="small muted">How are these batteries wired?</div>${radios}</div>
     <p class="small muted">Parallel adds amp-hours. Series adds voltage, and the weakest battery sets the amp-hours. Energy (kWh) adds up either way.</p>`,
    [{ label: "Cancel", value: "cancel" }, { label: "Save", value: "ok", cls: "primary" }]);
  const n = (data.get("name") ?? "").trim();
  return value && n ? { name: n, wiring: data.get("wiring") ?? "PARALLEL" } : null;
}

async function chooseAction(title, actions) {
  const { value } = await modal(title,
    `<div class="actions">${actions.map((a) => `<button value="${a.value}" class="${a.cls ?? ""}">${esc(a.label)}</button>`).join("")}</div>`,
    [{ label: "Cancel", value: "cancel" }]);
  return value;
}

async function confirmAction(title, message, label) {
  const { value } = await modal(title, `<p>${esc(message)}</p>`,
    [{ label: "Cancel", value: "cancel" }, { label, value: "ok", cls: "danger" }]);
  return value === "ok";
}

// --- Battery and group actions ----------------------------------------------------------------

async function batteryActions(mac) {
  const b = overview.batteries[mac];
  if (!b) return;
  const name = b.favorite.nickname;
  const choice = await chooseAction(name, [
    { label: "Refresh now", value: "refresh" },
    { label: "Rename…", value: "rename" },
    { label: "Group…", value: "group" },
    { label: "Thresholds…", value: "thresholds" },
    { label: "Move up", value: "up" },
    { label: "Move down", value: "down" },
    { label: "Remove from favorites…", value: "remove", cls: "danger" },
  ]);
  const enc = encodeURIComponent(mac);
  switch (choice) {
    case "refresh": return act(() => api("POST", `/api/batteries/${enc}/refresh`));
    case "rename": {
      const n = await askName("Rename", name, "Nickname");
      if (n) return act(() => api("PUT", `/api/favorites/${enc}/nickname`, { nickname: n }));
      return;
    }
    case "group": return pickGroup(b);
    case "thresholds": return editThresholds(`/api/favorites/${enc}/thresholds`, name, b.favorite.thresholds);
    case "up": return act(() => api("POST", `/api/favorites/${enc}/move`, { delta: -1 }));
    case "down": return act(() => api("POST", `/api/favorites/${enc}/move`, { delta: 1 }));
    case "remove":
      if (await confirmAction("Remove favorite", `Stop tracking "${name}"? Its history stays on disk.`, "Remove")) {
        return act(() => api("DELETE", `/api/favorites/${enc}`));
      }
  }
}

async function pickGroup(b) {
  const current = b.favorite.groupId ?? "";
  const options = overview.groups.map((g) => ({ id: g.group.id, label: `${g.group.name} (${wiringLabel(g.group.wiring).toLowerCase()})` }));
  options.push({ id: "", label: "No group" });
  const radios = options.map((o) =>
    `<label class="choice"><input type="radio" name="group" value="${esc(o.id)}" ${o.id === current ? "checked" : ""}> ${esc(o.label)}</label>`).join("");
  const { value, data } = await modal(`Group for ${b.favorite.nickname}`, radios,
    [{ label: "Cancel", value: "cancel" }, { label: "New group…", value: "new" }, { label: "Save", value: "ok", cls: "primary" }]);
  const enc = encodeURIComponent(b.favorite.mac);
  if (value === "new") {
    const g = await askGroup("New group", "", "PARALLEL");
    if (!g) return;
    return act(async () => {
      const { id } = await api("POST", "/api/groups", g);
      await api("PUT", `/api/favorites/${enc}/group`, { groupId: id });
    });
  }
  if (value === "ok") {
    const id = data.get("group") || null;
    return act(() => api("PUT", `/api/favorites/${enc}/group`, { groupId: id }));
  }
}

async function groupActions(id) {
  const g = overview.groups.find((x) => x.group.id === id)?.group;
  if (!g) return;
  const choice = await chooseAction(g.name, [
    { label: "Rename or change wiring…", value: "edit" },
    { label: "Thresholds…", value: "thresholds" },
    { label: "Delete group…", value: "delete", cls: "danger" },
  ]);
  if (choice === "thresholds") return editThresholds(`/api/groups/${encodeURIComponent(id)}/thresholds`, g.name, g.thresholds);
  if (choice === "edit") {
    const r = await askGroup("Edit group", g.name, g.wiring);
    if (r) return act(() => api("PATCH", `/api/groups/${encodeURIComponent(id)}`, r));
  } else if (choice === "delete") {
    if (await confirmAction("Delete group", `Delete "${g.name}"? Its batteries stay as favorites, ungrouped.`, "Delete")) {
      return act(() => api("DELETE", `/api/groups/${encodeURIComponent(id)}`));
    }
  }
}

// --- Settings ---------------------------------------------------------------------------------

async function openSettings() {
  const current = overview?.settings.pollIntervalMinutes ?? 10;
  const choices = INTERVALS.includes(current) ? INTERVALS : [...INTERVALS, current].sort((a, b) => a - b);
  const radios = choices.map((m) =>
    `<label class="choice"><input type="radio" name="interval" value="${m}" ${m === current ? "checked" : ""}> ${intervalLabel(m)}</label>`).join("");
  const { value, data } = await modal("Settings",
    `<div><strong>Automatic refresh</strong>
       <p class="small muted">How often the hub reads each battery over Bluetooth. Longer intervals leave the radio idle
       (and the batteries free for the phone) more of the time. Manual only reads just when you click Refresh or Search.</p>
       ${radios}</div>`,
    [{ label: "Cancel", value: "cancel" }, { label: "Save", value: "ok", cls: "primary" }]);
  if (value !== "ok") return;
  const minutes = Number(data.get("interval"));
  act(() => api("PUT", "/api/settings", { pollIntervalMinutes: minutes }));
}

// --- Search -----------------------------------------------------------------------------------

let scanTimer = null;

function renderScan(s) {
  const el = $("#discovered");
  if (!s.scanning && !s.devices.length && !s.error && !s.finishedAtMs) { el.hidden = true; return; }
  el.hidden = false;
  const head = `<h3 class="section-title">Discovered ${s.scanning ? '<span class="spinner" aria-label="Searching"></span>' : ""}</h3>`;
  let body;
  if (s.error) body = `<p class="error small">Search failed: ${esc(s.error)}</p>`;
  else if (!s.devices.length) body = `<p class="muted small">${s.scanning ? "Searching…" : "No new batteries found. Batteries already in your favorites aren't listed."}</p>`;
  else body = s.devices.map((d) => `<div class="device">
      <div class="who"><div>${esc(d.name ?? "Unknown")}</div><div class="small muted">${esc(d.mac)}</div></div>
      ${signal(d.rssiDbm)}
      <button type="button" data-identify="${esc(d.mac)}">Identify</button>
      <button type="button" class="primary" data-add="${esc(d.mac)}">Add</button></div>`).join("");
  el.innerHTML = head + body;
  $("#search").disabled = s.scanning;
}

async function pollScan() {
  try {
    const s = await (await fetch("/api/scan")).json();
    renderScan(s);
    if (!s.scanning) { clearInterval(scanTimer); scanTimer = null; }
  } catch (_) {}
}

async function startScan() {
  try {
    renderScan(await api("POST", "/api/scan", { seconds: 15 }));
  } catch (e) {
    toast(e.message);
  }
  if (!scanTimer) scanTimer = setInterval(pollScan, 1000);
}

async function identify(mac) {
  const pending = modal(mac, `<p><span class="spinner"></span> Reading… (stops polling briefly)</p>`, [{ label: "Close", value: "cancel" }]);
  let text;
  try {
    const r = await api("POST", `/api/identify/${encodeURIComponent(mac)}`);
    text = `<p class="big">${r.stateOfChargePct}%</p><p>${volts(r.packVoltageMv)} · ${amps(r.currentMa)} · ${ah(r.nominalCapacityMah)} · made ${esc(r.productionDate)}</p>
      <p class="small muted">Match this to the battery's display or charge level to know which one it is.</p>`;
  } catch (e) {
    text = `<p class="error">Couldn't read it: ${esc(e.message)}</p>`;
  }
  if ($("#modal").open && $("#modal-title").textContent === mac) $("#modal-body").innerHTML = text;
  await pending;
}

async function addFavorite(mac) {
  const n = await askName("Add to favorites", "", "Nickname");
  if (n) await act(() => api("POST", "/api/favorites", { mac, nickname: n }));
  pollScan();
}

// --- Wiring up --------------------------------------------------------------------------------

async function poll() {
  try {
    const res = await fetch("/api/overview");
    if (!res.ok) throw new Error(res.status);
    overview = await res.json();
    render();
  } catch (e) {
    $("#updated").textContent = "Hub not responding";
  }
}

$("#overview").addEventListener("click", (e) => {
  const more = e.target.closest(".more");
  if (more) {
    if (more.dataset.mac) batteryActions(more.dataset.mac);
    else if (more.dataset.group) groupActions(more.dataset.group);
    else if (more.dataset.total) editThresholds("/api/thresholds", "all batteries", overview.totalThresholds);
    return;
  }
  const card = e.target.closest(".card.battery");
  if (!card) return;
  openMac = card.dataset.mac;
  historyCache.mac = null;
  renderDetail();
  $("#detail").showModal();
});
$("#overview").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.target.matches(".card.battery")) e.target.click();
});
$("#discovered").addEventListener("click", (e) => {
  const add = e.target.closest("[data-add]");
  const ident = e.target.closest("[data-identify]");
  if (add) addFavorite(add.dataset.add);
  if (ident) identify(ident.dataset.identify);
});
$("#detail").addEventListener("close", () => { openMac = null; });
$("#detail-refresh").addEventListener("click", () => {
  if (!openMac) return;
  historyCache.mac = null;
  act(() => api("POST", `/api/batteries/${encodeURIComponent(openMac)}/refresh`));
});
$("#refresh-all").addEventListener("click", () => act(() => api("POST", "/api/refresh")));
$("#search").addEventListener("click", startScan);
$("#settings").addEventListener("click", openSettings);

poll();
pollScan();
setInterval(poll, 5000);
