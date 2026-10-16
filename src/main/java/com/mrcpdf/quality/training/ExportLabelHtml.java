package com.mrcpdf.quality.training;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emits the static HTML labeling tool for the synthetic corpus.
 *
 * Reads {@code build/synthetic-variant-map.csv} (page,doc,variant,label) and
 * writes a self-contained {@code index.html} into {@code build/label/} that
 * renders the original and MRC pages side by side with PDF.js (vendored by the
 * {@code downloadPdfjs} task into {@code build/label/vendor/pdfjs/}) using the
 * two PDFs the user picks via file inputs.  For every page the annotator ticks
 * "input readable" and "MRC readable"; every tick is written straight to
 * {@code localStorage} so nothing is lost on reload.  Buttons export the full
 * page-level judgment sheet as
 * {@code labels-mrc-detail.csv} (doc,page,input_readable,mrc_readable — both
 * judgments, no collapsing) plus a JSON state snapshot, and import either one
 * to resume.
 *
 * The page must be served/opened such that PDF.js can run over {@code file://}
 * (classic UMD script, not ES modules) and can fetch the blob: URLs created
 * from the picked files.
 *
 * Usage: ExportLabelHtml --map build/synthetic-variant-map.csv
 *                         [--out build/label/index.html]
 */
public final class ExportLabelHtml {

    public static void main(String[] args) throws IOException {
        Path map = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> map = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: ExportLabelHtml --map <csv> [--out <html>]");
                    return;
                }
            }
        }
        if (map == null) {
            System.err.println("--map is required");
            return;
        }
        if (out == null) {
            out = Path.of("build/label/index.html");
        }

        List<Row> rows = readMap(map, out);
        String rowsJson = rowsJson(rows);
        String html = TEMPLATE.replace("__ROWS_JSON__", rowsJson);
        Files.writeString(out, html, StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + rows.size() + " rows)");
        System.out.println("Open with: open " + out.toAbsolutePath());
    }

    private record Row(int page, String doc, String variant, int inputLabel,
                       double score, String tier, int pageInDoc) {
    }

    private static List<Row> readMap(Path map, Path base) throws IOException {
        List<Row> rows = new ArrayList<>();
        String currentDoc = null;
        int pageInDoc = 0;
        for (String line : Files.readAllLines(map, StandardCharsets.US_ASCII)) {
            if (line.isBlank() || line.startsWith("page,")) continue;
            String[] c = line.split(",", 6);
            if (c.length < 4) throw new IllegalArgumentException("Bad map row: " + line);
            if (!c[1].equals(currentDoc)) {
                currentDoc = c[1];
                pageInDoc = 0;
            }
            double score = c.length >= 6 ? Double.parseDouble(c[5]) : -1;
            String tier = c.length >= 5 ? c[4] : "";
            pageInDoc++;
            rows.add(new Row(Integer.parseInt(c[0]), c[1], c[2], Integer.parseInt(c[3]),
                    score, tier, pageInDoc));
        }
        Path dir = base.getParent();
        if (dir != null) Files.createDirectories(dir);
        return rows;
    }

    private static String rowsJson(List<Row> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"p\":").append(r.page)
                    .append(",\"d\":\"").append(jsonEsc(r.doc))
                    .append("\",\"v\":\"").append(jsonEsc(r.variant))
                    .append("\",\"l\":").append(r.inputLabel)
                    .append(",\"t\":\"").append(jsonEsc(r.tier))
                    .append("\",\"s\":").append(String.format(Locale.ROOT, "%.3f", r.score))
                    .append(",\"pi\":").append(r.pageInDoc)
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // NOTE: no literal backslashes in the JS below (Java text-block escaping);
    // the $ { sequences are intentional JS template literals.
    private static final String TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MRC labeling — synthetic corpus</title>
<script src="vendor/pdfjs/pdf.min.js"></script>
<style>
  :root { --accent:#2f6fed; --warn:#c0392b; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.35 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif; background:#f6f7f9; color:#222; }
  header { position:sticky; top:0; z-index:20; background:#fff; border-bottom:1px solid #d4d8de; padding:10px 16px; }
  header h1 { font-size:16px; margin:0 0 6px; }
  .bar { display:flex; flex-wrap:wrap; gap:8px 14px; align-items:center; }
  label.ctl { display:inline-flex; align-items:center; gap:5px; font-size:13px; }
  input[type=file] { max-width:280px; }
  .progress { flex:1 1 220px; }
  .progress > div { background:#e6e9ef; border-radius:8px; height:14px; overflow:hidden; }
  .progress > div > i { display:block; height:100%; width:0%; background:var(--accent); transition:width .15s; }
  .progress > span { font-size:12px; color:#555; }
  button { font-size:13px; padding:4px 10px; border-radius:6px; border:1px solid #b9c1cc; background:#fff; cursor:pointer; }
  button:hover { background:#f0f2f6; }
  .hint { font-size:12px; color:#666; }
  table { border-collapse:collapse; width:100%; background:#fff; }
  th { position:sticky; top:86px; z-index:10; background:#eef1f5; text-align:left; font-size:13px;
       border-bottom:1px solid #c7ced7; padding:6px 8px; }
  td { vertical-align:top; border-bottom:1px solid #e2e6ec; padding:6px 8px; }
  td.meta { width:110px; white-space:nowrap; font-size:12px; color:#444; }
  td.meta .tag { color:#888; }
  td.cell { width:42%; }
  .cell { min-height:90px; background:#fbfbfc; position:relative; }
  .cell canvas { display:block; width:100%; cursor:zoom-in; box-shadow:0 1px 2px rgba(0,0,0,.15); }
  td.checks { width:140px; }
  td.checks label { display:flex; gap:6px; align-items:center; margin:2px 0; cursor:pointer; font-size:13px; }
  td.checks .chip { margin-top:6px; font-size:11px; }
  .dot { display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:3px; vertical-align:1px; }
  .dot.on { background:#1f9d55; } .dot.off { background:var(--warn); } .dot.blank { background:#c3c9d2; }
  .legend { font-size:11px; margin-top:4px; color:#777; }
  .wait { color:#777; font-size:12px; padding:40px; text-align:center; background:#fff; }
  #modal { display:none; position:fixed; inset:0; z-index:50; background:rgba(20,24,30,.86); overflow:auto; padding:24px; }
  #modal img { max-width:none; }
  #modal canvas { background:#fff; box-shadow:0 4px 24px rgba(0,0,0,.5); }
  #modal .close { position:fixed; top:10px; right:14px; font-size:22px; color:#fff; cursor:pointer; }
  .cell.done::after { content:""; position:absolute; top:0; right:0; border:0 solid transparent;
      border-top:14px solid var(--accent); border-left:14px solid transparent; }
</style>
</head>
<body>
<header>
  <h1>Synthetic corpus — original vs MRC readability</h1>
  <div class="bar">
    <label class="ctl">Original PDF <input type="file" id="srcFile" accept="application/pdf,.pdf"></label>
    <label class="ctl">MRC PDF <input type="file" id="mrcFile" accept="application/pdf,.pdf"></label>
    <button id="exportCsv">Export labels CSV</button>
    <button id="exportJson">Export state JSON</button>
    <button id="importBtn">Import…</button>
    <input type="file" id="importFile" accept=".csv,.json,application/json,text/csv" hidden>
    <span id="errors" style="color:var(--warn);"></span>
    <div class="progress"><div><i id="progFill"></i></div><span id="progText">0/240</span></div>
    <label class="ctl">Jump to doc <select id="jump"></select></label>
  </div>
  <div class="hint">
    Check <b>input readable</b> and <b>MRC readable</b> per page — each tick auto-saves.
    Keys: <b>1</b> input readable · <b>2</b> MRC readable · <b>0</b> clear · <b>ArrowUp/Down</b> move.
    Click a page image to zoom.
  </div>
</header>
<table>
  <thead><tr>
    <th>Page</th>
    <th>Original</th>
    <th>MRC</th>
    <th>Readability</th>
  </tr></thead>
  <tbody id="rows"></tbody>
</table>
<div id="modal"><canvas id="zoomCanvas"></canvas><span class="close" id="zoomClose">&times;</span></div>

<script>
"use strict";
pdfjsLib.GlobalWorkerOptions.workerSrc = "vendor/pdfjs/pdf.worker.min.js";

const ROWS = __ROWS_JSON__;
const STORE_KEY = "mrc-label-state-v1";
const state = load();
let srcDoc = null, mrcDoc = null;
let curRow = 0;
let rendered = new Set();

function load() {
  let v = {};
  try { v = JSON.parse(localStorage.getItem(STORE_KEY)) || {}; } catch (e) {}
  return v;
}
function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch (e) {
    document.getElementById("errors").textContent = "localStorage unavailable — export manually.";
  }
}

const rowsTbody = document.getElementById("rows");
ROWS.forEach((r, idx) => {
  const tr = document.createElement("tr");
  tr.dataset.page = r.p;
  tr.innerHTML =
    '<td class="meta">p' + r.p + '<br><span class="tag">' + r.d + '<br>' + r.v +
      (r.t ? '<br>' + r.t + (r.s >= 0 ? ' (score ' + r.s.toFixed(3) + ')' : '') : '') + '</span></td>' +
    '<td class="cell orig"><canvas data-pdf="src" data-page="' + r.p + '"></canvas></td>' +
    '<td class="cell mrc"><canvas data-pdf="mrc" data-page="' + r.p + '"></canvas></td>' +
    '<td class="checks">' +
      '<label><input type="checkbox" data-field="in"> input readable</label>' +
      '<label><input type="checkbox" data-field="mrc"> MRC readable</label>' +
      '<div class="chip"></div>' +
      '<div class="legend">' + fully(r.p) + '</div>' +
    '</td>';
  tr.querySelectorAll('input[type=checkbox]').forEach(cb => cb.addEventListener("change", () => {
    const page = +tr.dataset.page;
    const field = cb.dataset.field;
    const v = state[page] || (state[page] = {});
    v[field] = cb.checked;
    refreshRow(tr, page);
    save();
    updateProgress();
  }));
  rowsTbody.appendChild(tr);
});
function fully(p) { const v = state[p]; return "in:" + cell(v && v.in) + " mrc:" + cell(v && v.mrc); }
function cell(x) { return x === undefined || x === null ? "·" : (x ? "✓" : "✗"); }

// Callback invoked by both the IntersectionObserver and the window scroll
// handler; the rendered set guards against duplicate renders.
function renderVisible(rows) {
  if (!srcDoc || !mrcDoc) return;
  const vh = document.documentElement.clientHeight;
  rows.forEach((tr, idx) => {
    const rect = tr.getBoundingClientRect();
    if (rect.bottom < -600 || rect.top > vh + 600) return;
    tr.querySelectorAll("canvas").forEach(cv => { renderCanvas(cv); });
  });
}

function refreshRow(tr, page) {
  const v = state[page] || {};
  tr.querySelectorAll('input[type=checkbox]').forEach(cb => {
    const cval = v[cb.dataset.field];
    if ((cb.checked ? true : false) !== (cval === true)) cb.checked = cval === true;
  });
  tr.querySelector('.legend').textContent = fully(page);
  const both = v.in !== undefined && v.mrc !== undefined;
  tr.classList.toggle("done", both);
}

function updateProgress() {
  let n = 0;
  ROWS.forEach(r => { const v = state[r.p]; if (v && v.in !== undefined && v.mrc !== undefined) n++; });
  document.getElementById("progFill").style.width = (100 * n / ROWS.length) + "%";
  document.getElementById("progText").textContent = n + "/" + ROWS.length + " fully labeled";
}

const jump = document.getElementById("jump");
ROWS.forEach((r, idx) => {
  const o = document.createElement("option");
  o.value = idx;
  o.textContent = r.d + " p" + r.pi + " " + r.v;
  jump.appendChild(o);
});
jump.addEventListener("change", () => {
  const idx = +jump.value;
  const tr = rowsTbody.children[idx];
  tr.scrollIntoView({ block: "start" });
  curRow = idx;
});

// --- PDFs ---------------------------------------------------------------
function setErr(m) { document.getElementById("errors").textContent = m || ""; }
function setFile(input, which) {
  const f = input.files[0];
  if (!f) return;
  const url = URL.createObjectURL(f);
  pdfjsLib.getDocument({ url }).promise.then(pdf => {
    if (which === "src") srcDoc = pdf; else mrcDoc = pdf;
    setErr("");
    if (srcDoc && mrcDoc) { initRender(); }
  }).catch(err => {
    setErr("Failed to load " + which + " PDF: " + err.message +
      ". On Safari via file://, if the worker fails, run `python3 -m http.server` in build/label and open http://localhost:8000/.");
  });
}
document.getElementById("srcFile").addEventListener("change", e => setFile(e.target, "src"));
document.getElementById("mrcFile").addEventListener("change", e => setFile(e.target, "mrc"));

// --- rendering ----------------------------------------------------------
function initRender() {
  const rows = Array.from(rowsTbody.children);
  renderVisible(rows);
  const io = new IntersectionObserver(() => renderVisible(rows), { rootMargin: "600px 0px" });
  rows.forEach(r => io.observe(r));
  window.addEventListener("scroll", () => renderVisible(rows));
}

async function renderCanvas(cv) {
  const key = cv.dataset.pdf + ":" + cv.dataset.page;
  if (rendered.has(key)) return;
  rendered.add(key);
  const pdf = cv.dataset.pdf === "src" ? srcDoc : mrcDoc;
  const page = +cv.dataset.page;
  const dpr = window.devicePixelRatio || 1;
  const cssW = cv.clientWidth || 400;
  const scale = (cssW / 612) * dpr;
  try {
    const p = await pdf.getPage(page - 1);
    const viewport = p.getViewport({ scale });
    cv.width = Math.floor(viewport.width);
    cv.height = Math.floor(viewport.height);
    await p.render({ canvasContext: cv.getContext("2d"), viewport }).promise;
  } catch (err) {
    cv.outerHTML = '<div class="wait">render failed: ' + err.message + '</div>';
    rendered.delete(key);
  }
}

// --- zoom ---------------------------------------------------------------
function addZoom() {
  rowsTbody.querySelectorAll("canvas").forEach(cv => {
    cv.addEventListener("click", async () => {
      const pdf = cv.dataset.pdf === "src" ? srcDoc : mrcDoc;
      const page = +cv.dataset.page;
      const zc = document.getElementById("zoomCanvas");
      const dpr = window.devicePixelRatio || 1;
      const scale = (Math.min(1400, (document.documentElement.clientWidth - 80) / 612)) * dpr;
      const p = await pdf.getPage(page - 1);
      const viewport = p.getViewport({ scale });
      zc.width = Math.floor(viewport.width);
      zc.height = Math.floor(viewport.height);
      await p.render({ canvasContext: zc.getContext("2d"), viewport }).promise;
      document.title = cv.dataset.pdf.toUpperCase() + " p" + page;
      document.getElementById("modal").style.display = "block";
    });
  });
}
document.getElementById("zoomClose").addEventListener("click", () => { document.getElementById("modal").style.display = "none"; });
document.getElementById("modal").addEventListener("click", e => { if (e.target.id === "modal") document.getElementById("modal").style.display = "none"; });

// --- keyboard -----------------------------------------------------------
window.addEventListener("keydown", e => {
  if (document.getElementById("modal").style.display === "block") { if (e.key === "Escape") document.getElementById("modal").style.display = "none"; return; }
  if (e.key !== "1" && e.key !== "2" && e.key !== "0") return;
  const tr = rowsTbody.children[curRow];
  if (!tr) return;
  const page = +tr.dataset.page;
  if (e.key === "0") {
    delete state[page];
  } else {
    const field = e.key === "1" ? "in" : "mrc";
    const v = state[page] || (state[page] = {});
    v[field] = !(v[field] === true);
  }
  refreshRow(tr, page);
  save();
  updateProgress();
  e.preventDefault();
});
window.addEventListener("keydown", e => {
  if (e.key === "ArrowDown") { curRow = Math.min(curRow + 1, rowsTbody.children.length - 1); rowsTbody.children[curRow].scrollIntoView({ block: "center" }); e.preventDefault(); }
  if (e.key === "ArrowUp")  { curRow = Math.max(curRow - 1, 0); rowsTbody.children[curRow].scrollIntoView({ block: "center" }); e.preventDefault(); }
});

// --- export / import ----------------------------------------------------
function download(name, text, mime) {
  const blob = new Blob([text], { type: mime || "text/plain" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}
function detailCsv() {
  let s = "doc,page,input_readable,mrc_readable\\n";
  ROWS.forEach(r => {
    const v = state[r.p] || {};
    s += "synthetic-src," + r.p + "," + csvBool(v.in) + "," + csvBool(v.mrc) + "\\n";
  });
  return s;
}
function csvBool(x) { return x === true ? "1" : x === false ? "0" : ""; }
document.getElementById("exportCsv").addEventListener("click", () => download("labels-mrc-detail.csv", detailCsv(), "text/csv"));
document.getElementById("exportJson").addEventListener("click", () => download("mrc-label-state.json", JSON.stringify(state, null, 2), "application/json"));

document.getElementById("importBtn").addEventListener("click", () => document.getElementById("importFile").click());
document.getElementById("importFile").addEventListener("change", e => {
  const f = e.target.files[0];
  if (!f) return;
  f.text().then(txt => {
    const t = txt.trim();
    if (t.startsWith("{")) {
      try {
        Object.assign(state, JSON.parse(t));
        applyAll();
      } catch (err) { setErr("Import JSON failed: " + err.message); }
    } else {
      const lines = t.split(/\\r?\\n/);
      lines.forEach(line => {
        if (!line || line.startsWith("doc,")) return;
        const c = line.split(",");
        if (c.length < 3) return;
        const page = +c[1];
        if (!page) return;
        const v = state[page] = state[page] || {};
        v.in = parseBool(c[2]);
        if (c.length > 3 && c[3] !== "") { const b = parseBool(c[3]); if (b !== undefined) v.mrc = b; }
      });
      applyAll();
    }
  }).catch(err => setErr("Import failed: " + err.message));
  e.target.value = "";
});
function parseBool(x) { return x === "1" || x === "true" ? true : x === "0" || x === "false" ? false : undefined; }
function applyAll() {
  Array.from(rowsTbody.children).forEach(tr => refreshRow(tr, +tr.dataset.page));
  updateProgress();
  save();
}

updateProgress();
applyAll();
addZoom();
</script>
</body>
</html>
""";
}