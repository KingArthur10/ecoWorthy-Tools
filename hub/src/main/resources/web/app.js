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

const bar = (pct) => `<div class="bar"><span style="width:${Math.max(0, Math.min(100, pct))}%"></span></div>`;

// --- Rendering ------------------------------------------------------------------------------

function aggregateBody(s, now) {
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
       <div>${ah(r.remainingCapacityMah)} of ${ah(capacity(r))} · ${energy((r.remainingCapacityMah * nominalMv(r)) / 1000)} of ${energy((capacity(r) * nominalMv(r)) / 1000)}</div>`
    : `<div class="muted">No data yet</div>`;
  return `<article class="card battery" data-mac="${esc(b.favorite.mac)}" tabindex="0">
    <div class="card-head"><span class="name">${esc(b.favorite.nickname)}</span>${signal(r?.rssiDbm)}</div>
    ${body}${statusLine(b, now)}</article>`;
}

function render() {
  const o = overview;
  const now = o.nowMs;
  const macs = Object.keys(o.batteries);
  let html = "";
  if (!macs.length) {
    html = `<p class="muted">No favorites yet. Add them in the Android app, then run <code>battery-hub import-phone</code>.</p>`;
  }
  if (macs.length >= 2 && o.total) {
    html += `<section class="card total"><div class="card-head"><span class="name">All batteries</span></div>${aggregateBody(o.total, now)}</section>`;
  }
  for (const g of o.groups) {
    html += `<section class="card group"><div class="card-head"><span class="name">${esc(g.group.name)}</span><span class="small">${wiringLabel(g.group.wiring)}</span></div>
      ${g.members.length === 0 ? `<div class="small muted">No batteries yet</div>` : g.stats ? aggregateBody(g.stats, now) : `<div class="small muted">No data yet</div>`}</section>`;
    html += `<div class="grid">${g.members.map((m) => batteryCard(o.batteries[m], now)).join("")}</div>`;
  }
  if (o.groups.length && o.ungrouped.length) html += `<h3 class="section-title">Not in a group</h3>`;
  html += `<div class="grid">${o.ungrouped.map((m) => batteryCard(o.batteries[m], now)).join("")}</div>`;
  $("#overview").innerHTML = html;
  $("#updated").textContent = "Updated " + new Date(now).toLocaleTimeString();
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
  if (r) {
    const points = await loadHistory(openMac);
    const soc = $("#chart-soc"), cur = $("#chart-current");
    if (soc) soc.innerHTML = chart(points, (p) => p.soc, (v) => Math.round(v) + "%");
    if (cur) cur.innerHTML = chart(points, (p) => p.currentMa / 1000, (v) => v.toFixed(1) + " A", true);
  }
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
$("#detail").addEventListener("close", () => { openMac = null; });
$("#detail-refresh").addEventListener("click", async () => {
  if (!openMac) return;
  await fetch(`/api/batteries/${encodeURIComponent(openMac)}/refresh`, { method: "POST" });
  historyCache.mac = null;
  poll();
});
$("#refresh-all").addEventListener("click", async () => {
  await fetch("/api/refresh", { method: "POST" });
  poll();
});

poll();
setInterval(poll, 5000);
