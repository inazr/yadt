// Layout-order checks for the ALPHA-SORT block in resources/js/lineage.js.
//
//   node src/test/js/lineage-order-test.js     (exit 0 = pass)
//
// Not wired into ./gradlew test: it needs a Node runtime, which the Gradle build does not
// require. It has no dependencies of its own - it drives the vendored elk.bundled.js and
// cytoscape.min.js straight out of src/main/resources/js, and it pulls the pass under test
// out of lineage.js *verbatim* between the ALPHA-SORT markers, so it cannot drift into
// testing a copy. Run it after touching lineage.js's layout code; the rendered result still
// needs a real IDE.

const fs = require('fs');
const path = require('path');
const SRC = path.join(__dirname, '..', '..', 'main', 'resources', 'js') + path.sep;
const ELK = require(SRC + 'elk.bundled.js');
const cytoscape = require(SRC + 'cytoscape.min.js');
const elk = new ELK();
const src = fs.readFileSync(SRC + 'lineage.js', 'utf8');

// Den ausgelieferten Block woertlich uebernehmen - kein Nachbau.
const block = src.substring(src.indexOf('// --- ALPHA-SORT BEGIN'), src.indexOf('// --- ALPHA-SORT END'));
eval(block);
const BASE = eval('(' + src.match(/const ELK_LAYOUT_OPTIONS = (\{[\s\S]*?\n    \});/)[1] + ')');
console.log('aus lineage.js:  Toleranz', CROSSING_TOLERANCE, '| Pauschale +' + CROSSING_SLACK + ' | Kantenlimit', CROSSING_EDGE_LIMIT);

async function layoutToCy(spec) {              // spec: {boxes:{box:[{id,w,h}]}, loose:[...], edges:[[a,b]]}
  const children = [];
  Object.keys(spec.boxes).forEach(b => children.push({ id: b, children: spec.boxes[b].map(n =>
      ({ id: n.id, width: n.w, height: n.h, layoutOptions: { 'elk.alignment': 'LEFT' } })) }));
  (spec.loose || []).forEach(n => children.push({ id: n.id, width: n.w, height: n.h, layoutOptions: { 'elk.alignment': 'LEFT' } }));
  const out = await elk.layout({ id: 'root', layoutOptions: BASE, children,
    edges: spec.edges.map(([a, b], i) => ({ id: 'e' + i, sources: [a], targets: [b] })) });

  const els = [];
  const meta = {};
  Object.keys(spec.boxes).forEach(b => els.push({ data: { id: b, isParent: true } }));
  const walk = (n, parent, ox, oy) => (n.children || []).forEach(c => {
    if (c.children) walk(c, c.id, ox + (c.x||0), oy + (c.y||0));
    else {
      const src2 = [].concat(...Object.values(spec.boxes), spec.loose || []).find(k => k.id === c.id);
      meta[c.id] = { w: src2.w, h: src2.h };
      els.push({ data: { id: c.id, name: src2.name || c.id, w: src2.w, h: src2.h,
                         resourceType: src2.type || 'model', parent: parent || undefined } });
      meta[c.id].pos = { x: ox + c.x + c.width / 2, y: oy + c.y + c.height / 2 };
    }
  });
  walk(out, null, 0, 0);
  spec.edges.forEach(([a, b], i) => els.push({ data: { id: 'e' + i, source: a, target: b } }));
  // Positionen aus der Element-Definition werden headless verworfen - nachtraeglich setzen.
  const cy = cytoscape({ headless: true, styleEnabled: false, elements: els });
  Object.keys(meta).forEach(id => cy.getElementById(id).position(meta[id].pos));
  return { cy, meta };
}

const yOf = (cy, id) => cy.getElementById(id).position().y;
const span = (cy, ids, meta) => {
  const iv = ids.map(id => ({ id, a: yOf(cy, id) - meta[id].h / 2, b: yOf(cy, id) + meta[id].h / 2 }))
                .sort((p, q) => p.a - q.a);
  const overlap = iv.some((v, i) => i > 0 && v.a < iv[i - 1].b - 0.001);
  return { top: iv[0].a, bottom: iv[iv.length - 1].b, overlap, order: iv.map(v => v.id) };
};

(async () => {
  let pass = true;
  const check = (ok, msg) => { console.log((ok ? '  OK   ' : '  FAIL ') + msg); pass = pass && ok; };

  // === FALL 1: Muster aus dem Screenshot ===
  const INTS = ['int_automation__order','int_automation__generic_transports','int_automation__accounting_documents',
    'int_superset__projects','int_automation__generic_order','int_automation__transports',
    'int_bonus_budget_performance_allocation','int_superset__organizations','int_cee_bonus_2025_automation'];
  const spec1 = {
    boxes: { transformation: INTS.map(n => ({ id: n, w: 130, h: 22 })),
             datawarehouse: [{ id: 'fct_automation', w: 130, h: 22 }] },
    loose: INTS.map((n, i) => ({ id: 'stub_' + i, w: 60, h: 16, type: 'stub' })),
    edges: [...INTS.map((n, i) => ['stub_' + i, n]), ...INTS.map(n => [n, 'fct_automation'])],
  };
  const { cy: cy1, meta: m1 } = await layoutToCy(spec1);
  const edges1 = cy1.edges().toArray();
  const before1 = totalCrossings(edges1, 'LR');
  const b1 = span(cy1, INTS, m1);
  console.log('\n### FALL 1: 9 Karten im Kasten, alle -> fct_automation');
  console.log('  vorher:  ' + b1.order.map(s => s.replace('int_', '')).join(' , '));
  const n1 = alphabetizeInterchangeableGroups(cy1, 'LR', CROSSING_TOLERANCE, CROSSING_SLACK);
  const a1 = span(cy1, INTS, m1);
  const after1 = totalCrossings(edges1, 'LR');
  console.log('  nachher: ' + a1.order.map(s => s.replace('int_', '')).join(' , '));
  console.log(`  Kreuzungen ${before1} -> ${after1} | sortierte Gruppen: ${n1}`);
  check(JSON.stringify(a1.order) === JSON.stringify([...INTS].sort()), 'Kasten ist alphabetisch');
  check(after1 <= before1 * CROSSING_TOLERANCE, `Kreuzungen innerhalb der ${Math.round((CROSSING_TOLERANCE-1)*100)}%-Toleranz`);
  check(!a1.overlap, 'keine ueberlappenden Karten');
  check(Math.abs(a1.top - b1.top) < 0.001 && Math.abs(a1.bottom - b1.bottom) < 0.001, 'Kasten behaelt exakt seine Grenzen');

  // === FALL 2: Sortieren waere teuer -> muss unveraendert bleiben ===
  // Vier Karten zeigen ueberkreuz auf vier Ziele; alphabetisch waere maximal schlecht.
  const CARDS = ['m_alpha','m_bravo','m_charlie','m_delta'];
  const spec2 = {
    boxes: { quelle: CARDS.map(n => ({ id: n, w: 130, h: 22 })),
             ziel: [3,2,1,0].map(i => ({ id: 't' + i, w: 130, h: 22 })) },
    edges: CARDS.map((n, i) => [n, 't' + (3 - i)]).concat([['m_alpha','t0'],['m_delta','t3']]),
  };
  const { cy: cy2, meta: m2 } = await layoutToCy(spec2);
  const edges2 = cy2.edges().toArray();
  const before2 = totalCrossings(edges2, 'LR');
  const b2 = span(cy2, CARDS, m2);
  const n2 = alphabetizeInterchangeableGroups(cy2, 'LR', CROSSING_TOLERANCE, CROSSING_SLACK);
  const after2 = totalCrossings(edges2, 'LR');
  const a2 = span(cy2, CARDS, m2);
  console.log('\n### FALL 2: Sortieren wuerde Kreuzungen kosten');
  console.log('  vorher:  ' + b2.order.join(' , '));
  console.log('  nachher: ' + a2.order.join(' , '));
  console.log(`  Kreuzungen ${before2} -> ${after2}`);
  check(after2 <= Math.max(before2 * CROSSING_TOLERANCE, before2 + CROSSING_SLACK), 'Kreuzungen nicht ueber dem Budget gestiegen');
  check(!a2.overlap, 'keine ueberlappenden Karten');

  // === FALL 3: Gegenprobe - mit riesiger Toleranz MUSS derselbe Fall sortiert werden.
  // Nur so ist belegt, dass Fall 2 an der Toleranz scheitert und nicht uebersehen wurde.
  const { cy: cy3, meta: m3 } = await layoutToCy(spec2);
  const before3 = totalCrossings(cy3.edges().toArray(), 'LR');
  alphabetizeInterchangeableGroups(cy3, 'LR', 100, CROSSING_SLACK);
  const a3 = span(cy3, CARDS, m3);
  const after3 = totalCrossings(cy3.edges().toArray(), 'LR');
  console.log('\n### FALL 3: derselbe Graph mit Toleranz 100 (Gegenprobe)');
  console.log('  nachher: ' + a3.order.join(' , ') + `  | Kreuzungen ${before3} -> ${after3}`);
  check(JSON.stringify(a3.order) === JSON.stringify([...CARDS].sort()),
        'wird bei hoher Toleranz sortiert -> Fall 2 scheiterte wirklich an den 5%');
  check(after3 > before3, 'und kostet dort tatsaechlich Kreuzungen');

  // === FALL 4: Top-Bottom-Layout ===
  const { cy: cy4, meta: m4 } = await layoutToCy(spec1);
  // TB simulieren: Achsen tauschen, dann mit dir='TB' sortieren
  Object.keys(m4).forEach(id => { const q = cy4.getElementById(id).position(); cy4.getElementById(id).position({ x: q.y, y: q.x }); });
  Object.keys(m4).forEach(id => { const s = m4[id]; const w = s.w; s.w = s.h; s.h = w;
                                  cy4.getElementById(id).data('w', s.w); cy4.getElementById(id).data('h', s.h); });
  const n4 = alphabetizeInterchangeableGroups(cy4, 'TB', CROSSING_TOLERANCE, CROSSING_SLACK);
  const orderTB = INTS.map(id => ({ id, x: cy4.getElementById(id).position().x })).sort((a, b) => a.x - b.x).map(v => v.id);
  console.log('\n### FALL 4: Top-Bottom (Achsen getauscht)');
  console.log('  nachher: ' + orderTB.map(s => s.replace('int_', '')).join(' , '));
  check(JSON.stringify(orderTB) === JSON.stringify([...INTS].sort()), 'TB-Layout wird ebenfalls sortiert');

  // === FALL 5: die Pauschale muss etwas oeffnen, das die Prozentregel ablehnt ===
  // Handgebaut, damit die Kosten exakt bekannt sind: Sortieren kostet genau +1 Kreuzung
  // bei Grundwert 1. Prozentregel erlaubt 1.05 -> abgelehnt; mit +2 Pauschale -> erlaubt.
  // t_* sind bereits alphabetisch und haben Grad 2, werden also weder sortiert noch
  // als exklusive Nachbarn mitgezogen.
  function slackCase() {
    const els = [
      { data: { id: 'q', isParent: true } }, { data: { id: 'z', isParent: true } },
      { data: { id: 'm_bravo', name: 'm_bravo', parent: 'q', w: 100, h: 20 } },
      { data: { id: 'm_alpha', name: 'm_alpha', parent: 'q', w: 100, h: 20 } },
      { data: { id: 't_alpha', name: 't_alpha', parent: 'z', w: 100, h: 20 } },
      { data: { id: 't_bravo', name: 't_bravo', parent: 'z', w: 100, h: 20 } },
      { data: { id: 'k_alpha', name: 'k_alpha', w: 100, h: 20 } },
      { data: { id: 'k_bravo', name: 'k_bravo', w: 100, h: 20 } },
      { data: { id: 'y1', source: 'm_bravo', target: 't_alpha' } },
      { data: { id: 'y2', source: 'm_alpha', target: 't_bravo' } },
      { data: { id: 'y3', source: 'k_alpha', target: 't_alpha' } },
      { data: { id: 'y4', source: 'k_bravo', target: 't_bravo' } },
    ];
    const cy = cytoscape({ headless: true, styleEnabled: false, elements: els });
    const pos = { m_bravo: [0, 0], m_alpha: [0, 60], k_alpha: [0, 120], k_bravo: [0, 180],
                  t_alpha: [200, 0], t_bravo: [200, 60] };
    Object.keys(pos).forEach(id => cy.getElementById(id).position({ x: pos[id][0], y: pos[id][1] }));
    return cy;
  }
  const topTwo = cy => ['m_alpha', 'm_bravo'].map(id => ({ id, y: cy.getElementById(id).position().y }))
                        .sort((a, b) => a.y - b.y).map(v => v.id);

  const cyStrict = slackCase();
  const baseStrict = totalCrossings(cyStrict.edges().toArray(), 'LR');
  alphabetizeInterchangeableGroups(cyStrict, 'LR', CROSSING_TOLERANCE, 0);   // nur Prozentregel
  const strictOrder = topTwo(cyStrict);

  const cySlack = slackCase();
  alphabetizeInterchangeableGroups(cySlack, 'LR', CROSSING_TOLERANCE, CROSSING_SLACK);
  const slackOrder = topTwo(cySlack);
  const afterSlack = totalCrossings(cySlack.edges().toArray(), 'LR');

  console.log('\n### FALL 5: Pauschale +' + CROSSING_SLACK + ' gegen reine Prozentregel');
  console.log(`  Grundwert ${baseStrict} Kreuzungen`);
  console.log('  nur 5%:        ' + strictOrder.join(' , '));
  console.log(`  5% oder +${CROSSING_SLACK}:   ` + slackOrder.join(' , ') + `  (${baseStrict} -> ${afterSlack})`);
  check(JSON.stringify(strictOrder) === JSON.stringify(['m_bravo', 'm_alpha']),
        'reine Prozentregel laesst diese Spalte unsortiert');
  check(JSON.stringify(slackOrder) === JSON.stringify(['m_alpha', 'm_bravo']),
        'mit Pauschale wird sie sortiert');
  check(afterSlack <= baseStrict + CROSSING_SLACK, 'und bleibt im Budget');

  console.log('\n' + (pass ? 'ALLE PRUEFUNGEN BESTANDEN' : 'FEHLGESCHLAGEN'));
  process.exit(pass ? 0 : 1);
})().catch(e => { console.error('FEHLER:', e.stack); process.exit(1); });
