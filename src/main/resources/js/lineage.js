// dbt Lineage Graph — Cytoscape.js + Dagre
(function () {
    'use strict';

    // Color is applied as the left side bar on each card.
    const NODE_BAR_COLORS = {
        model_view:   '#3F7BD9',
        model_table:  '#7C5CE6',
        model:        '#3F7BD9',
        source:       '#3FB950',
        seed:         '#FF9800',
        snapshot:     '#9C27B0',
        exposure:     '#E91E63',
        test:         '#B39DDB',
        stub:         '#9E9E9E'
    };
    // Categorical palette for schema-based coloring (stable, well-separated hues).
    const SCHEMA_PALETTE = [
        '#4E79A7', '#F28E2B', '#59A14F', '#E15759', '#B07AA1', '#76B7B2',
        '#EDC948', '#FF9DA7', '#9C755F', '#499894', '#D37295', '#8CD17D'
    ];
    // Fallback for nodes without a schema (e.g. exposures) in schema mode.
    const NEUTRAL_BAR_COLOR = '#9E9E9E';

    // Run-status colors (used when nodeColorMode === 'status'). Set live from Kotlin.
    const STATUS_BAR_COLORS = {
        queued:  '#3F7BD9',
        running: '#5AC8FA',
        success: '#3FB950',
        warn:    '#E3B341',
        error:   '#F85149',
        skipped: '#C9CED6'
    };

    const ELK_LAYOUT_OPTIONS = {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.edgeRouting': 'ORTHOGONAL',
        'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
        'elk.layered.spacing.nodeNodeBetweenLayers': '80',
        'elk.spacing.nodeNode': '40',
        'elk.layered.crossingMinimization.semiInteractive': 'true',
        'elk.hierarchyHandling': 'INCLUDE_CHILDREN'
    };

    function elkDirectionFor(layoutDir) {
        return layoutDir === 'TB' ? 'DOWN' : 'RIGHT';
    }

    // A layer is as wide as its widest card, and ELK centers narrower cards inside it.
    // Card widths come from measureCardWidth(), i.e. from the model name, so a column of
    // cards ends up ragged on both sides. Aligning every card to the start of its layer
    // instead makes the column read as a list.
    //
    // Two things to know before touching this: 'elk.alignment' is a *node* option — putting
    // it in ELK_LAYOUT_OPTIONS (graph level) has no effect at all — and the axis it refers
    // to is the one the layers advance along, so LR needs LEFT while TB needs TOP.
    function nodeAlignmentFor(layoutDir) {
        return layoutDir === 'TB' ? 'TOP' : 'LEFT';
    }

    // Cluster boxes are sized by their content and carry no width of their own, so aligning
    // them would only shift a whole group; they keep ELK's default.
    function elkNodeOptionsFor(layoutDir) {
        var aligned = { 'elk.alignment': nodeAlignmentFor(layoutDir) };
        return function (node) { return node.isParent() ? {} : aligned; };
    }

    var KNOWN_PREFIXES = new Set(['col', 'tag', 'mat', 'schema', 'type', 'pkg']);

    function tokenize(query) {
        if (!query || !query.trim()) return [];
        return query.trim().split(/\s+/).map(function (raw) {
            var colon = raw.indexOf(':');
            if (colon <= 0 || colon === raw.length - 1) return { kind: 'bare', value: raw };
            var key = raw.substring(0, colon).toLowerCase();
            var value = raw.substring(colon + 1);
            if (KNOWN_PREFIXES.has(key)) return { kind: key, value: value.toLowerCase() };
            return { kind: 'bare', value: raw };
        });
    }

    // --- ALPHA-SORT BEGIN (the layout test extracts this block verbatim - keep the markers)
    // ELK's crossing minimization has many equally good optima and keeps whichever one its
    // heuristic reaches first, which is why the order inside a column looks arbitrary. This
    // pass picks the alphabetical one from among them. It runs on the *finished* layout -
    // ELK is never reconfigured - and only permutes nodes that share a column AND a cluster
    // box. Each node's exclusive neighbours ("+ N more" stubs) travel with it, because
    // tearing a neighbourhood apart is what actually costs crossings. A permutation is kept
    // only while the total crossing count stays within CROSSING_TOLERANCE of ELK's own.
    // Whichever of the two is more generous. The percentage alone is nearly meaningless on
    // small graphs - at 16 crossings it permits 16.8, i.e. not a single extra one - so the
    // flat slack is what actually opens up short columns, while the percentage carries the
    // large graphs where 2 crossings would be noise.
    var CROSSING_TOLERANCE = 1.05;  // at most 5% more crossings than the engine's own order...
    var CROSSING_SLACK = 2;         // ...or 2 more, whichever allows more
    var CROSSING_EDGE_LIMIT = 2000; // counting is pairwise, so skip the pass on huge graphs

    // Cytoscape positions are centres. Along the layer axis we need the *leading* edge:
    // nodes of one layer share it exactly (that is what elkNodeOptionsFor aligns them to),
    // while their centres drift apart as soon as the cards differ in size.
    function layerKey(n, dir) {
        var p = n.position();
        return String(Math.round(dir === 'TB' ? p.y - nodeSize(n, 'TB_layer') / 2 : p.x - nodeSize(n, 'LR_layer') / 2));
    }

    // Size along an axis, taken from the data the cards were built with.
    function nodeSize(n, what) {
        var w = +n.data('w') || 0, h = +n.data('h') || 0;
        if (what === 'TB_layer') return h;   // TB: layers advance along y
        if (what === 'LR_layer') return w;   // LR: layers advance along x
        return what === 'TB' ? w : h;        // in-layer extent
    }

    function inLayerPos(n, dir) { var p = n.position(); return dir === 'TB' ? p.x : p.y; }

    function setInLayerPos(n, v, dir) {
        var p = n.position();
        n.position(dir === 'TB' ? { x: v, y: p.y } : { x: p.x, y: v });
    }

    // Re-stack `order` into the slots the nodes already occupy, keeping the original
    // edge-to-edge gaps. Because the sizes are only permuted, the block starts and ends
    // exactly where it did - no overlaps, and every cluster box keeps its bounds.
    function repackInOrder(current, order, dir) {
        var sizes = current.map(function (n) { return nodeSize(n, dir); });
        var lead = inLayerPos(current[0], dir) - sizes[0] / 2;
        var gaps = [];
        for (var i = 1; i < current.length; i++) {
            gaps.push((inLayerPos(current[i], dir) - sizes[i] / 2) -
                      (inLayerPos(current[i - 1], dir) + sizes[i - 1] / 2));
        }
        var cursor = lead;
        order.forEach(function (n, i) {
            var s = nodeSize(n, dir);
            setInLayerPos(n, cursor + s / 2, dir);
            cursor += s + (gaps[i] || 0);
        });
    }

    // Crossings among edge pairs where at least one edge moved. Pairs of untouched edges
    // cannot change, so skipping them keeps the delta exact and the cost low.
    function crossingsTouching(edges, movedIds, dir) {
        var c = 0;
        for (var i = 0; i < edges.length; i++) {
            var a = edges[i];
            var aMoved = !!movedIds[a.id()];
            var as = inLayerPos(a.source(), dir), at = inLayerPos(a.target(), dir);
            for (var j = i + 1; j < edges.length; j++) {
                var b = edges[j];
                if (!aMoved && !movedIds[b.id()]) continue;
                if ((as - inLayerPos(b.source(), dir)) * (at - inLayerPos(b.target(), dir)) < 0) c++;
            }
        }
        return c;
    }

    function totalCrossings(edges, dir) {
        var all = {};
        edges.forEach(function (e) { all[e.id()] = true; });
        return crossingsTouching(edges, all, dir);
    }

    // A neighbour hanging off exactly this one node is part of it for ordering purposes.
    // Satellites inside a *different* box are left alone - moving them would resize it.
    function exclusiveSatellites(node) {
        var out = [];
        node.connectedEdges().forEach(function (e) {
            var other = e.source().id() === node.id() ? e.target() : e.source();
            if (other.isParent() || other.degree(false) !== 1) return;
            var op = other.data('parent') || '';
            if (op && op !== (node.data('parent') || '')) return;
            out.push(other);
        });
        return out;
    }

    function alphabetizeInterchangeableGroups(cyRef, dir, tolerance, slack) {
        if (!cyRef) return 0;
        var edges = cyRef.edges().toArray();
        if (!edges.length || edges.length > CROSSING_EDGE_LIMIT) return 0;

        // Stubs are satellites, not content: they follow their owner instead of being
        // sorted among themselves as "+ 1 more", "+ 2 more", ...
        var groups = {};
        cyRef.nodes().forEach(function (n) {
            if (n.isParent() || n.data('resourceType') === 'stub') return;
            var key = (n.data('parent') || '') + '#' + layerKey(n, dir);
            (groups[key] = groups[key] || []).push(n);
        });

        var current = totalCrossings(edges, dir);
        var budget = Math.max(current * tolerance, current + slack);
        var sorted = 0;

        Object.keys(groups).forEach(function (key) {
            var members = groups[key];
            if (members.length < 2) return;

            var inPlace = members.slice().sort(function (a, b) { return inLayerPos(a, dir) - inLayerPos(b, dir); });
            var byName = members.slice().sort(function (a, b) {
                var an = String(a.data('name') || a.id()).toLowerCase();
                var bn = String(b.data('name') || b.id()).toLowerCase();
                return an < bn ? -1 : an > bn ? 1 : 0;
            });
            if (inPlace.every(function (n, i) { return n.id() === byName[i].id(); })) {
                sorted++;
                return;
            }

            // Rank of each member in the target order, so its satellites can follow.
            var rankOf = {};
            byName.forEach(function (n, i) { rankOf[n.id()] = i; });

            var satsByLayer = {};
            members.forEach(function (n) {
                exclusiveSatellites(n).forEach(function (s) {
                    var k = layerKey(s, dir);
                    (satsByLayer[k] = satsByLayer[k] || []).push({ node: s, rank: rankOf[n.id()] });
                });
            });

            var touched = members.slice();
            Object.keys(satsByLayer).forEach(function (k) {
                satsByLayer[k].forEach(function (s) { touched.push(s.node); });
            });
            var movedIds = {};
            touched.forEach(function (n) {
                n.connectedEdges().forEach(function (e) { movedIds[e.id()] = true; });
            });
            var restore = touched.map(function (n) {
                var p = n.position();
                return { node: n, x: p.x, y: p.y };
            });

            var before = crossingsTouching(edges, movedIds, dir);

            repackInOrder(inPlace, byName, dir);
            Object.keys(satsByLayer).forEach(function (k) {
                var sats = satsByLayer[k];
                var order = sats.slice().sort(function (a, b) {
                    return a.rank - b.rank || inLayerPos(a.node, dir) - inLayerPos(b.node, dir);
                }).map(function (s) { return s.node; });
                var here = sats.map(function (s) { return s.node; })
                               .sort(function (a, b) { return inLayerPos(a, dir) - inLayerPos(b, dir); });
                repackInOrder(here, order, dir);
            });

            var after = crossingsTouching(edges, movedIds, dir);

            if (current - before + after <= budget) {
                current = current - before + after;
                sorted++;
            } else {
                restore.forEach(function (r) { r.node.position({ x: r.x, y: r.y }); });
            }
        });
        return sorted;
    }
    // --- ALPHA-SORT END

    function schemaColor(schema) {
        var h = 0;
        for (var i = 0; i < schema.length; i++) {
            h = (h * 31 + schema.charCodeAt(i)) | 0;
        }
        return SCHEMA_PALETTE[Math.abs(h) % SCHEMA_PALETTE.length];
    }

    const MIN_CARD_WIDTH = 220;   // floor: short names look exactly as before
    const MAX_CARD_WIDTH = 460;   // cap: ~45 chars; longer names ellipsize (hover/docs show full)
    // Fixed non-text horizontal space in a card: borders (2) + main-row left padding (4)
    // + icon block (margin-left 14 + icon 28 + margin-right 10 = 52) + right padding (12) = 70,
    // plus ~16 reserved on the right so a fully-shown name doesn't run under the absolute
    // .card-toggle / .card-failure-badge (right: 5–6px, width 14px).
    const CARD_CHROME_PX = 86;
    const CARD_HEIGHT = 44;
    const STUB_WIDTH = 110;
    const STUB_HEIGHT = 40;

    // Card-name font is fixed (see .card-name in lineage.html) and does NOT vary with the IDE
    // theme, so a single cached canvas context measures names accurately and stably.
    const CARD_NAME_FONT = "600 13px -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif";
    var _cardMeasureCtx = null;

    /**
     * Width needed to show [name] in full, clamped to [MIN_CARD_WIDTH, MAX_CARD_WIDTH].
     * Computed at graph-build time (before cards exist) so it can feed the cytoscape node's
     * data(w), which ELK uses for layout and which syncNodeCards reads back for the HTML card.
     */
    function measureCardWidth(name) {
        if (!_cardMeasureCtx) {
            var canvas = document.createElement('canvas');
            _cardMeasureCtx = canvas.getContext('2d');
            if (_cardMeasureCtx) _cardMeasureCtx.font = CARD_NAME_FONT;
        }
        if (!_cardMeasureCtx) return MIN_CARD_WIDTH; // defensive: no 2D context → never break rendering
        var textW = Math.ceil(_cardMeasureCtx.measureText(name || '').width);
        return Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, textW + CARD_CHROME_PX));
    }

    function cardHeightFor(data) {
        if (!expandedIds.has(data.id)) return CARD_HEIGHT;
        var rows = (data.columns && data.columns.length) || 1;
        return CARD_HEIGHT + Math.min(rows * 22, 240);
    }

    // Inline SVG icons keyed by visual variant.
    const ICONS = {
        source: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3.5" y="3" width="9" height="10" rx="1.5"/><path d="M7 8h3.5M9 6.5L10.5 8 9 9.5"/></svg>',
        view: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="3" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11"/><text x="8" y="11.5" font-size="5" font-weight="700" fill="currentColor" stroke="none" text-anchor="middle" font-family="-apple-system, sans-serif">V</text></svg>',
        table: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="3" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M2.5 10h11M6 6.5V13M10 6.5V13"/></svg>',
        seed: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 13V7M5 9c0-1.5 1.3-3 3-3s3 1.5 3 3M4.5 13h7"/></svg>',
        snapshot: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="4.5" width="11" height="8" rx="1.5"/><circle cx="8" cy="8.5" r="2"/><path d="M6 4.5l1-1h2l1 1"/></svg>',
        exposure: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2.5 13V8.5M6 13V5.5M9.5 13V7M13 13V3.5"/></svg>',
        test: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3.5 8.5l3 3 6-7"/></svg>'
    };

    // Materializations that persist a relation (drawn with the table icon / table color).
    function isTableMaterialization(node) {
        var mat = (node.materialization || 'view').toLowerCase();
        return mat === 'table' || mat === 'incremental' || mat === 'materialized_view';
    }

    function pickIconKey(node) {
        if (node.resourceType === 'source') return 'source';
        if (node.resourceType === 'seed') return 'seed';
        if (node.resourceType === 'snapshot') return 'snapshot';
        if (node.resourceType === 'exposure') return 'exposure';
        // model: pick by materialization
        return isTableMaterialization(node) ? 'table' : 'view';
    }

    // Bar color for a node's current run status; unknown/absent statuses are neutral.
    function statusBarColor(id) {
        var st = nodeStatus[id];
        return (st && STATUS_BAR_COLORS[st]) || NEUTRAL_BAR_COLOR;
    }

    function pickBarColor(node, colorMode) {
        if (colorMode === 'status') return statusBarColor(node.id);
        if (colorMode === 'schema') {
            return node.schema ? schemaColor(node.schema) : NEUTRAL_BAR_COLOR;
        }
        if (node.resourceType !== 'model') return NODE_BAR_COLORS[node.resourceType] || '#888';
        return isTableMaterialization(node) ? NODE_BAR_COLORS.model_table : NODE_BAR_COLORS.model_view;
    }

    function parseArg(jsonOrValue) {
        return typeof jsonOrValue === 'string' ? JSON.parse(jsonOrValue) : jsonOrValue;
    }

    let cy = null;
    let lastCurrentNodeId = null;
    let currentLayoutDir = 'LR';
    let previousNodeIds = new Set();
    let nodeCards = {};
    var nodeSearchHints = {};
    let currentColorMode = 'resource';
    let nodeStatus = {}; // uniqueId -> status string (see STATUS_BAR_COLORS keys)
    var nodeFailureMessages = {}; // uniqueId -> failure message string
    // uniqueId -> { status:'error'|'warn', failed:int, warned:int } from the last run's
    // tests, rolled onto the nodes they validate. Drives the "!" triangle overlay.
    var nodeTestStatus = {};
    // Warning triangle with "!", tinted via the .sev-error / .sev-warn card classes.
    var TEST_BADGE_ICON =
        '<svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">'
        + '<path d="M8 1.3 L15.2 14.2 H0.8 Z" fill="currentColor"/>'
        + '<rect x="7.2" y="5.4" width="1.6" height="4.6" rx="0.8" fill="#fff"/>'
        + '<circle cx="8" cy="11.7" r="0.95" fill="#fff"/></svg>';
    var selectedIds = new Set();
    let activeDrag = null;
    var themeEdgeColor = null; // last themed edge color, re-applied on every render

    var expandedIds = new Set();

    function projectStorageKey(suffix) {
        return 'dbtHelper.' + suffix + '.' + (window.__projectRootHash || 'default');
    }

    function loadExpandedFromStorage() {
        try {
            var raw = localStorage.getItem(projectStorageKey('expandedNodes'));
            if (!raw) return;
            var arr = JSON.parse(raw);
            if (Array.isArray(arr)) expandedIds = new Set(arr);
        } catch (e) {}
    }
    function saveExpandedToStorage() {
        try {
            localStorage.setItem(projectStorageKey('expandedNodes'), JSON.stringify(Array.from(expandedIds)));
        } catch (e) {}
    }

    window.addEventListener('mousemove', function (e) {
        if (!activeDrag || !cy) return;
        var dx = e.clientX - activeDrag.startX;
        var dy = e.clientY - activeDrag.startY;
        if (!activeDrag.moved && Math.hypot(dx, dy) < 4) return;
        activeDrag.moved = true;
        var node = cy.getElementById(activeDrag.id);
        if (!node || !node.length) return;
        var rect = cy.container().getBoundingClientRect();
        var pan = cy.pan();
        var zoom = cy.zoom();
        var modelX = (e.clientX - rect.left - pan.x) / zoom;
        var modelY = (e.clientY - rect.top - pan.y) / zoom;
        node.position({ x: modelX, y: modelY });
    });
    var pendingClickTimer = null;
    var lastClickedId = null;

    var hoverActive = false;
    function applyHoverHighlight(nodeId) {
        if (!cy) return;
        // Disable hover-highlight when other state is active.
        if (lastClickedId === nodeId) return; // single-click neighborhood dim
        var hasSearchDim = false;
        cy.edges().some(function (e) {
            if (e.hasClass('dimmed')) { hasSearchDim = true; return true; }
            return false;
        });
        if (hasSearchDim) return;
        var node = cy.getElementById(nodeId);
        if (!node.length) return;
        var incident = node.connectedEdges();
        incident.addClass('hot');
        cy.edges().not(incident).addClass('cold');
        hoverActive = true;
    }
    function clearHoverHighlight() {
        if (!cy || !hoverActive) return;
        cy.edges().removeClass('hot').removeClass('cold');
        hoverActive = false;
    }

    // Mark exactly the cards whose id is in `ids` (anything with has()) as selected.
    function selectCards(ids) {
        Object.keys(nodeCards).forEach(function (cid) {
            nodeCards[cid].classList.toggle('selected', ids.has(cid));
        });
    }

    function toggleMultiSelect(id) {
        if (selectedIds.has(id)) selectedIds.delete(id);
        else selectedIds.add(id);
        selectCards(selectedIds);
        notifyMultiSelectChanged();
    }
    function clearMultiSelect() {
        if (selectedIds.size === 0) return;
        selectedIds.clear();
        selectCards(selectedIds);
        notifyMultiSelectChanged();
    }
    function notifyMultiSelectChanged() {
        sendToKotlin('multiSelectChanged', { count: selectedIds.size });
    }

    // Dim or undim a graph element together with its HTML card (edges have no card).
    function setDimmed(el, on) {
        if (on) el.addClass('dimmed');
        else el.removeClass('dimmed');
        var card = nodeCards[el.id()];
        if (card) card.classList.toggle('dimmed', on);
    }

    function dimToNeighborhood(nodeId) {
        if (!cy) return;
        var center = cy.getElementById(nodeId);
        if (!center.length) return;
        var keep = center.union(center.predecessors()).union(center.successors());
        cy.elements().forEach(function (el) {
            setDimmed(el, !keep.contains(el));
        });
        // Highlight the clicked card
        selectCards(new Set([nodeId]));
    }

    // Also the search box's reset (window.resetFilter): both just undim everything.
    function clearNeighborhoodDim() {
        if (!cy) return;
        cy.elements().removeClass('dimmed');
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('dimmed'); });
    }

    // Click on empty graph area clears dim
    document.getElementById('cy').addEventListener('click', function () {
        clearNeighborhoodDim();
        clearMultiSelect();
    });

    // A stub asks Kotlin to reveal the hidden hops behind it; any other node is focused.
    function activateNode(data) {
        if (data.resourceType === 'stub') {
            sendToKotlin('expandRequest', { direction: data.stubDirection, boundaryNodeId: data.boundaryNodeId });
        } else {
            sendToKotlin('nodeClick', { nodeId: data.id });
        }
    }

    window.addEventListener('mouseup', function () {
        if (!activeDrag) return;
        var d = activeDrag;
        activeDrag = null;
        if (d.card) d.card.style.cursor = '';
        if (d.moved) return;

        // A stub expands on the first click; it has no preview to wait for.
        if (d.data.resourceType === 'stub') {
            activateNode(d.data);
            return;
        }

        // Distinguish single vs double click manually
        if (pendingClickTimer && lastClickedId === d.data.id) {
            // Double click — focus (refresh graph + open file)
            clearTimeout(pendingClickTimer);
            pendingClickTimer = null;
            lastClickedId = null;
            clearNeighborhoodDim();
            activateNode(d.data);
            return;
        }

        // Single click — dim everything except this node's ancestors/descendants,
        // and push docs for it without re-focusing the graph
        if (pendingClickTimer) clearTimeout(pendingClickTimer);
        lastClickedId = d.data.id;
        var nodeId = d.data.id;
        pendingClickTimer = setTimeout(function () {
            pendingClickTimer = null;
            lastClickedId = null;
            dimToNeighborhood(nodeId);
            sendToKotlin('previewNode', { nodeId: nodeId });
        }, 260);
    });
    const tooltipEl = document.getElementById('tooltip');
    const loadingEl = document.getElementById('loading');
    const overlayEl = document.getElementById('node-overlay');

    function renderColumnsInto(card, data) {
        if (!data.columns || !data.columns.length) {
            var banner = document.createElement('div');
            banner.className = 'card-banner';
            banner.textContent = 'Run `dbt docs generate` for column types';
            card.appendChild(banner);
            return;
        }
        var wrap = document.createElement('div');
        wrap.className = 'card-columns';
        wrap.addEventListener('wheel', function (e) {
            e.stopPropagation();
        }, { passive: true });
        data.columns.forEach(function (c) {
            var row = document.createElement('div');
            row.className = 'col-row';
            var name = document.createElement('span');
            name.className = 'col-name';
            if (c.isPrimaryKey) {
                var pk = document.createElement('span');
                pk.className = 'pk-badge';
                pk.textContent = 'PK';
                name.appendChild(pk);
            }
            name.appendChild(document.createTextNode(c.name));
            var type = document.createElement('span');
            type.className = 'col-type';
            type.textContent = c.type || '';
            row.appendChild(name);
            row.appendChild(type);
            wrap.appendChild(row);
        });
        card.appendChild(wrap);
    }

    function toggleExpand(nodeId) {
        if (expandedIds.has(nodeId)) expandedIds.delete(nodeId);
        else expandedIds.add(nodeId);
        saveExpandedToStorage();
        rerenderCardAndRelayout(nodeId);
    }

    function rerenderCardAndRelayout(nodeId) {
        if (!cy) return;
        var node = cy.getElementById(nodeId);
        if (!node.length) return;
        var oldRendered = node.renderedPosition();
        var data = node.data();
        var newHeight = cardHeightFor(data);
        node.data('h', newHeight);

        runElkLayout(currentLayoutDir).then(function () {
            // Anchor: pan by difference so the toggled node stays under the cursor
            var newRendered = node.renderedPosition();
            var pan = cy.pan();
            cy.pan({ x: pan.x + (oldRendered.x - newRendered.x), y: pan.y + (oldRendered.y - newRendered.y) });
            buildNodeCards();
        });
    }

    // ELK layout of the live graph, then the alphabetical column pass on its result.
    function runElkLayout(layoutDir) {
        var elkOpts = Object.assign({}, ELK_LAYOUT_OPTIONS, {
            'elk.direction': elkDirectionFor(layoutDir)
        });
        return cy.layout({ name: 'elk', fit: false, elk: elkOpts, nodeLayoutOptions: elkNodeOptionsFor(layoutDir) })
            .run()
            .promiseOn('layoutstop').then(function () {
                alphabetizeInterchangeableGroups(cy, layoutDir, CROSSING_TOLERANCE, CROSSING_SLACK);
            });
    }

    function buildNodeCards() {
        // Clear existing cards
        Object.keys(nodeCards).forEach(function (id) {
            var el = nodeCards[id];
            if (el && el.parentNode) el.parentNode.removeChild(el);
        });
        nodeCards = {};

        cy.nodes().forEach(function (node) {
            var data = node.data();
            if (data.isParent) return;

            var card = createCardElement(data);
            wireCardEvents(card, data);
            overlayEl.appendChild(card);
            nodeCards[data.id] = card;
        });
        syncNodeCards();
    }

    // The card's DOM (stub or full card). Only its inner controls get listeners here;
    // the card-level interaction is wired by wireCardEvents.
    function createCardElement(data) {
        var card = document.createElement('div');
        card.className = 'card-node';
        card.dataset.id = data.id;

        if (data.resourceType === 'stub') {
            card.classList.add('stub');
            var name = document.createElement('div');
            name.className = 'card-name';
            name.textContent = data.name || '+ more';
            card.appendChild(name);
        } else {
            card.style.setProperty('--card-bar-color', data.barColor);
            if (currentColorMode === 'status' && nodeStatus[data.id] === 'running') {
                card.classList.add('running');
            }

            // Wrap main row content
            var mainRow = document.createElement('div');
            mainRow.className = 'card-main-row';

            var bar = document.createElement('div');
            bar.className = 'card-bar';
            card.appendChild(bar);

            var icon = document.createElement('div');
            icon.className = 'card-icon';
            icon.innerHTML = ICONS[data.iconKey] || ICONS.view;
            mainRow.appendChild(icon);

            var text = document.createElement('div');
            text.className = 'card-text';
            var name2 = document.createElement('div');
            name2.className = 'card-name';
            name2.textContent = data.name;
            text.appendChild(name2);
            if (data.resourceType === 'source' && data.freshness && data.freshness.status !== 'pass') {
                var fresh = document.createElement('div');
                fresh.className = 'card-freshness fresh-' + data.freshness.status;
                fresh.textContent = data.freshness.status === 'error' ? '⬤' : '●';
                fresh.title = 'Freshness: ' + data.freshness.status + (data.freshness.message ? ' — ' + data.freshness.message : '');
                fresh.addEventListener('click', function (e) {
                    e.stopPropagation();
                    sendToKotlin('openFreshnessDetail', { nodeId: data.id });
                });
                text.appendChild(fresh);
            }
            mainRow.appendChild(text);

            var badge = document.createElement('div');
            badge.className = 'card-failure-badge';
            card.appendChild(badge);
            card.classList.add('no-failure-badge');

            var canExpand = !!(data.columns && data.columns.length) || data.resourceType === 'model' || data.resourceType === 'source' || data.resourceType === 'seed' || data.resourceType === 'snapshot';
            if (canExpand) {
                var toggle = document.createElement('div');
                toggle.className = 'card-toggle';
                toggle.textContent = expandedIds.has(data.id) ? '▾' : '▸';
                toggle.addEventListener('mousedown', function (ev) { ev.stopPropagation(); });
                toggle.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    toggleExpand(data.id);
                });
                mainRow.appendChild(toggle);
            }

            card.appendChild(mainRow);

            if (canExpand && expandedIds.has(data.id)) {
                renderColumnsInto(card, data);
            }
        }

        if (data.isCurrent) card.classList.add('selected');
        return card;
    }

    function wireCardEvents(card, data) {
        // Drag + click handling — actual drag/up listeners are global (see below).
        card.addEventListener('mousedown', function (e) {
            if (e.button !== 0) {
                // right-click (button 2) → context menu handled by separate listener
                return;
            }
            e.preventDefault();
            e.stopPropagation();
            hideTooltip();
            if (e.shiftKey || e.metaKey || e.ctrlKey) {
                // Toggle multi-select; do NOT begin drag
                toggleMultiSelect(data.id);
                return;
            }
            activeDrag = { id: data.id, data: data, card: card, startX: e.clientX, startY: e.clientY, moved: false };
            card.style.cursor = 'grabbing';
        });
        card.addEventListener('mouseenter', function (e) {
            if (activeDrag) return;
            applyHoverHighlight(data.id);
            showTooltip({ x: e.clientX, y: e.clientY }, data);
        });
        card.addEventListener('mousemove', function (e) {
            if (activeDrag) return;
            moveTooltip({ x: e.clientX, y: e.clientY });
        });
        card.addEventListener('mouseleave', function () {
            clearHoverHighlight();
            hideTooltip();
        });
        card.addEventListener('contextmenu', function (e) {
            onCardContextMenu(e, data);
        });
    }

    function onCardContextMenu(e, data) {
        e.preventDefault();
        e.stopPropagation();
        var ids;
        if (selectedIds.size > 0 && selectedIds.has(data.id)) {
            ids = Array.from(selectedIds);
        } else {
            // Right-click on an unselected card: act on that card only
            clearMultiSelect();
            selectedIds.add(data.id);
            var c = nodeCards[data.id];
            if (c) c.classList.add('selected');
            ids = [data.id];
        }
        var types = ids.map(function (id) {
            var n = cy.getElementById(id);
            return n.length ? n.data('resourceType') : null;
        });
        var names = ids.map(function (id) {
            var n = cy.getElementById(id);
            return n.length ? n.data('name') : id;
        });
        sendToKotlin('contextMenuRequest', {
            nodeIds: ids,
            names: names,
            resourceTypes: types,
            // clientX/Y are viewport-relative; since JCEF fills its Swing component,
            // they map 1:1 to coordinates inside browser.component on the Kotlin side.
            clientX: Math.round(e.clientX),
            clientY: Math.round(e.clientY)
        });
    }

    var syncRaf = null;
    function syncNodeCards() {
        if (syncRaf) return;
        syncRaf = requestAnimationFrame(function () {
            syncRaf = null;
            if (!cy) return;
            var zoom = cy.zoom();
            cy.nodes().forEach(function (node) {
                var card = nodeCards[node.id()];
                if (!card) return;
                var pos = node.renderedPosition();
                var w = node.width();
                var h = node.height();
                var renderedW = w * zoom;
                var renderedH = h * zoom;
                var left = pos.x - renderedW / 2;
                var top = pos.y - renderedH / 2;
                card.style.width = w + 'px';
                card.style.height = h + 'px';
                card.style.transform = 'translate(' + left + 'px, ' + top + 'px) scale(' + zoom + ')';
            });
        });
    }

    // Registered once: #cy, the zoom buttons and document outlive every re-render
    // (only the cytoscape instance is recreated), so wiring these per render would
    // stack another listener each time and multiply every zoom step.
    function initZoomControls() {
        // Manual wheel/pinch zoom for JCEF trackpad compatibility
        var cyContainer = document.getElementById('cy');

        function applyZoom(factor, x, y) {
            var zoom = cy.zoom() * factor;
            zoom = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), zoom));
            cy.zoom({ level: zoom, renderedPosition: { x: x, y: y } });
        }

        // Wheel event — handles scroll and ctrl+scroll (pinch on some systems)
        cyContainer.addEventListener('wheel', function (e) {
            if (!cy) return;
            e.preventDefault();
            var delta = e.deltaY;
            var sensitivity = e.ctrlKey ? 0.01 : 0.001;
            applyZoom(1 - delta * sensitivity, e.offsetX, e.offsetY);
        }, { passive: false });

        // One zoom step around the viewport center: dir > 0 zooms in, otherwise out.
        function zoomStep(dir) {
            applyZoom(dir > 0 ? 1.02 : 1 / 1.02, cy.width() / 2, cy.height() / 2);
        }

        // Zoom control buttons — 5% step
        document.getElementById('zoom-in').addEventListener('click', function () {
            if (!cy) return;
            zoomStep(1);
        });
        document.getElementById('zoom-out').addEventListener('click', function () {
            if (!cy) return;
            zoomStep(-1);
        });
        document.getElementById('zoom-fit').addEventListener('click', function () {
            if (!cy) return;
            fitGraph();
        });

        // Keyboard zoom: +/- and =/- keys (skip when typing in search)
        document.addEventListener('keydown', function (e) {
            if (!cy) return;
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            if (e.key === '+' || e.key === '=') {
                e.preventDefault();
                zoomStep(1);
            } else if (e.key === '-' || e.key === '_') {
                e.preventDefault();
                zoomStep(-1);
            } else if (e.key === '0') {
                e.preventDefault();
                fitGraph();
            }
        });
    }
    initZoomControls();

    function fitGraph() {
        cy.fit(undefined, 30);
    }

    // Native node rendering is hidden (the HTML overlay draws the cards); only cluster
    // boxes and edges are drawn by cytoscape itself.
    function cyStylesheet(edgeCurveStyle) {
        return [
            {
                selector: 'node',
                style: {
                    // Native rendering hidden — HTML overlay does the drawing.
                    'label': '',
                    'background-opacity': 0,
                    'border-width': 0,
                    'width': 'data(w)',
                    'height': 'data(h)',
                    'shape': 'round-rectangle'
                }
            },
            {
                selector: 'node[?isParent]',
                style: {
                    'background-opacity': 0.10,
                    'background-color': '#888',
                    'border-width': 1.5,
                    'border-color': '#888',
                    'border-opacity': 0.85,
                    'shape': 'round-rectangle',
                    'label': 'data(name)',
                    'text-valign': 'top',
                    'text-halign': 'center',
                    'text-margin-y': -6,
                    'font-size': 12,
                    'font-weight': 600,
                    'color': '#bbb',
                    'padding': 18
                }
            },
            {
                selector: 'edge',
                style: {
                    'width': 1.5,
                    'line-color': '#999',
                    'target-arrow-color': '#999',
                    'target-arrow-shape': 'triangle',
                    'curve-style': edgeCurveStyle || 'bezier',
                    'arrow-scale': 0.8
                }
            },
            {
                selector: 'edge.hot',
                style: {
                    'opacity': 1,
                    'width': 2.5,
                    'line-color': '#4E79A7',
                    'target-arrow-color': '#4E79A7'
                }
            },
            {
                selector: 'edge.cold',
                style: {
                    'opacity': 0.1
                }
            },
            {
                selector: 'node.dimmed',
                style: { 'opacity': 0.25 }
            },
            {
                selector: 'edge.dimmed',
                style: { 'opacity': 0.15 }
            },
            {
                selector: 'edge.stub-edge',
                style: {
                    'width': 1,
                    'line-style': 'dashed',
                    'line-color': '#aaa',
                    'target-arrow-color': '#aaa'
                }
            }
        ];
    }

    // Restore the saved viewport on a re-render, otherwise fit and center on the current node.
    function restoreViewport(savedZoom, savedPan, currentNodeId) {
        if (savedZoom && savedPan) {
            cy.zoom(savedZoom);
            cy.pan(savedPan);
        } else {
            // First render — fit graph, then center on current node
            fitGraph();
            var currentNode = cy.getElementById(currentNodeId);
            if (currentNode.length) {
                cy.center(currentNode);
            }
        }
    }

    function fadeIn(ele) {
        ele.style('opacity', 0);
        ele.animate({ style: { opacity: 1 } }, { duration: 300, complete: function () {
            ele.removeStyle('opacity');
        }});
    }

    // Fade in the nodes that were not in the previous render, and every edge touching one.
    function fadeInNewElements() {
        cy.nodes().forEach(function (node) {
            if (!previousNodeIds.has(node.id())) fadeIn(node);
        });
        cy.edges().forEach(function (edge) {
            var srcNew = !previousNodeIds.has(edge.source().id());
            var tgtNew = !previousNodeIds.has(edge.target().id());
            if (srcNew || tgtNew) fadeIn(edge);
        });
    }

    function initCytoscape(elements, currentNodeId, edgeCurveStyle, layoutDirection) {
        currentLayoutDir = layoutDirection || 'LR';
        // Save viewport if re-rendering
        var savedZoom = null;
        var savedPan = null;
        var isRerender = cy && lastCurrentNodeId === currentNodeId;
        if (isRerender) {
            savedZoom = cy.zoom();
            savedPan = cy.pan();
        }
        if (cy) {
            previousNodeIds = new Set();
            cy.nodes().forEach(function (n) { previousNodeIds.add(n.id()); });
            cy.destroy();
        }
        lastCurrentNodeId = currentNodeId;

        cy = cytoscape({
            container: document.getElementById('cy'),
            elements: elements,
            layout: { name: 'preset', fit: false },
            style: cyStylesheet(edgeCurveStyle),
            minZoom: 0.2,
            maxZoom: 3,
            zoomingEnabled: true,
            userZoomingEnabled: false
        });

        // Cytoscape edge colors are imperative (can't read CSS vars), so re-apply the
        // themed color here — the stylesheet default only covers first paint pre-theme.
        if (themeEdgeColor) {
            cy.edges().style({ 'line-color': themeEdgeColor, 'target-arrow-color': themeEdgeColor });
        }

        // Node click
        cy.on('tap', 'node', function (evt) {
            activateNode(evt.target.data());
        });

        // Hover tooltip
        cy.on('mouseover', 'node', function (evt) {
            const data = evt.target.data();
            showTooltip(evt.renderedPosition, data);
        });

        cy.on('mouseout', 'node', function () {
            hideTooltip();
        });

        cy.on('mousemove', 'node', function (evt) {
            moveTooltip(evt.renderedPosition);
        });

        function finalizeLayout() {
            // Keep the overlay visible when there are no nodes, so an empty-selection
            // "No nodes match" hint set via showGraphMessage isn't erased on layout-complete.
            if (cy.nodes().length > 0) loadingEl.style.display = 'none';

            // Restore viewport or center on current node (savedZoom/Pan are only set on a re-render)
            restoreViewport(savedZoom, savedPan, currentNodeId);

            // Fade in new nodes and edges
            if (isRerender && previousNodeIds.size > 0) fadeInNewElements();

            // Build HTML cards over cytoscape
            buildNodeCards();
            // Newly built cards start without a test triangle; re-apply from the
            // retained nodeTestStatus so triangles survive a re-render (selector change).
            repaintAllFailureBadges();
            cy.on('pan zoom position layoutstop', syncNodeCards);
            cy.on('pan zoom layoutstop', drawMinimap);
        }

        runElkLayout(currentLayoutDir).then(finalizeLayout);
    }


    function showTooltip(pos, data) {
        var html = '<div class="tt-name">' + escapeHtml(data.name) + '</div>';
        html += '<div class="tt-detail">';
        html += 'Type: ' + data.resourceType;
        if (data.schema) html += '<br>Schema: ' + escapeHtml(data.schema);
        if (data.database) html += '<br>Database: ' + escapeHtml(data.database);
        if (data.materialization) html += '<br>Materialization: ' + data.materialization;
        if (data.description) html += '<br>' + escapeHtml(data.description.substring(0, 150));
        var ts = nodeTestStatus[data.id];
        if (ts) {
            var parts = [];
            if (ts.failed) parts.push(ts.failed + ' test' + (ts.failed === 1 ? '' : 's') + ' failed');
            if (ts.warned) parts.push(ts.warned + ' warning' + (ts.warned === 1 ? '' : 's'));
            if (parts.length) {
                var cls = ts.status === 'error' ? 'tt-test-error' : 'tt-test-warn';
                html += '<br><span class="' + cls + '">⚠ ' + parts.join(', ') + '</span>';
            }
        }
        html += '</div>';
        tooltipEl.innerHTML = html;
        tooltipEl.style.display = 'block';
        moveTooltip(pos);
    }

    function moveTooltip(pos) {
        tooltipEl.style.left = (pos.x + 15) + 'px';
        tooltipEl.style.top = (pos.y + 15) + 'px';
    }

    function hideTooltip() {
        tooltipEl.style.display = 'none';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // === Public API (called from Kotlin) ===

    // Recolor a single card from its current nodeStatus entry (status mode only).
    function applyStatusToCard(id) {
        var card = nodeCards[id];
        if (!card || card.classList.contains('stub')) return;
        card.style.setProperty('--card-bar-color', statusBarColor(id));
        card.classList.toggle('running', nodeStatus[id] === 'running');
    }

    // Repaint every card from nodeStatus (status mode only). Not a graph re-render.
    function repaintAllStatusCards() {
        Object.keys(nodeCards).forEach(applyStatusToCard);
    }

    function repaintAllFailureBadges() {
        var showBadges = window.__showFailureBadges !== false; // default true
        Object.keys(nodeCards).forEach(function (id) {
            var card = nodeCards[id];
            if (!card || card.classList.contains('stub')) return;
            var ts = nodeTestStatus[id];
            var badge = card.querySelector('.card-failure-badge');
            var show = showBadges && !!ts;
            if (badge) {
                badge.innerHTML = show ? TEST_BADGE_ICON : '';
                badge.classList.toggle('sev-error', show && ts.status === 'error');
                badge.classList.toggle('sev-warn', show && ts.status === 'warn');
            }
            card.classList.toggle('no-failure-badge', !show);
        });
    }
    window.repaintAllFailureBadges = repaintAllFailureBadges;

    // Receive { nodeId: { status, failed, warned } } and repaint the triangle overlay.
    window.setTestStatuses = function (jsonStr) {
        try {
            nodeTestStatus = parseArg(jsonStr) || {};
            repaintAllFailureBadges();
        } catch (e) { console.error('setTestStatuses error:', e); }
    };

    // Merge {uniqueId: status} into the store; live-update cards if in status mode.
    window.setNodeStatuses = function (jsonStr) {
        try {
            var map = parseArg(jsonStr);
            Object.keys(map).forEach(function (id) { nodeStatus[id] = map[id]; });
            if (currentColorMode === 'status') {
                Object.keys(map).forEach(applyStatusToCard);
            }
        } catch (e) { console.error('setNodeStatuses error:', e); }
    };

    // Replace the store wholesale (authoritative final state). Absent ids -> neutral.
    window.applyRunResults = function (jsonStr) {
        try {
            nodeStatus = parseArg(jsonStr);
            if (currentColorMode === 'status') repaintAllStatusCards();
        } catch (e) { console.error('applyRunResults error:', e); }
    };

    window.seedQueuedStatuses = function (idsJson) {
        try {
            var ids = parseArg(idsJson);
            ids.forEach(function (id) { nodeStatus[id] = 'queued'; });
            // A new run is starting: drop the previous run's test triangles so they
            // don't linger as stale until fresh run_results.json arrives.
            nodeTestStatus = {};
            repaintAllFailureBadges();
            // Status is always recorded, but only painted in status color mode.
            if (currentColorMode === 'status') repaintAllStatusCards();
        } catch (e) { console.error('seedQueuedStatuses error:', e); }
    };

    window.setRunResults = function (payloadOrJson) {
        try {
            var map = parseArg(payloadOrJson);
            nodeStatus = {};
            nodeFailureMessages = {};
            Object.keys(map).forEach(function (id) {
                nodeStatus[id] = map[id].status;
                if (map[id].failures > 0 && map[id].message) {
                    nodeFailureMessages[id] = map[id].message;
                }
            });
            // Status colors are only painted in status mode; the failure badges and
            // "last run" hint are independent overlays and always update.
            if (currentColorMode === 'status') repaintAllStatusCards();
            repaintAllFailureBadges();
            renderRunResultsHint(map);
        } catch (e) { console.error('setRunResults error:', e); }
    };

    function renderRunResultsHint(map) {
        var hintEl = document.getElementById('run-results-hint');
        if (!hintEl) return;
        var count = map ? Object.keys(map).length : 0;
        if (count === 0) {
            hintEl.style.display = 'none';
            return;
        }
        var startedSeconds = 0;
        var startedAtIso = null;
        Object.keys(map).forEach(function (id) {
            if (map[id].startedAt) {
                var t = Date.parse(map[id].startedAt);
                if (t > startedSeconds) { startedSeconds = t; startedAtIso = map[id].startedAt; }
            }
        });
        var label = startedAtIso
            ? new Date(startedAtIso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
            : 'recent run';
        var noun = count === 1 ? 'model' : 'models';
        hintEl.textContent = 'Showing results from last run at ' + label + ' (' + count + ' ' + noun + ').';
        hintEl.style.display = 'block';
    }

    window.renderGraph = function (jsonStr) {
        loadExpandedFromStorage();
        loadMinimapPref();
        try {
            const graph = parseArg(jsonStr);
            window.__catalogAvailable = !!graph.catalogAvailable;
            currentColorMode = graph.nodeColorMode || 'resource';

            // Prune expanded ids that are no longer in the graph
            var liveIds = new Set();
            graph.nodes.forEach(function (n) { liveIds.add(n.id); });
            Array.from(expandedIds).forEach(function (id) {
                if (!liveIds.has(id)) expandedIds.delete(id);
            });
            saveExpandedToStorage();

            const elements = graph.nodes.map(function (node) {
                return toCyNodeElement(node, graph.nodeColorMode);
            });

            nodeSearchHints = {};
            for (const node of graph.nodes) {
                if (node.searchHints) nodeSearchHints[node.id] = node.searchHints;
            }

            for (const edge of graph.edges) {
                elements.push(toCyEdgeElement(edge));
            }

            if (loadingEl && elements.length > 0) { loadingEl.style.display = 'none'; }
            initCytoscape(elements, graph.currentNodeId, graph.edgeCurveStyle, graph.layoutDirection);
        } catch (e) {
            console.error('renderGraph error:', e);
        }
    };

    // Kotlin LineageGraph node -> cytoscape element; data(w)/data(h) feed ELK and the card size.
    function toCyNodeElement(node, colorMode) {
        if (node.isParent) {
            return {
                data: {
                    id: node.id,
                    name: node.name,
                    isParent: true,
                    resourceType: 'cluster'
                }
            };
        }

        var name = node.name;
        var w = measureCardWidth(name);
        var h = (node.resourceType === 'stub') ? STUB_HEIGHT : cardHeightFor(node);
        if (node.resourceType === 'stub') { w = STUB_WIDTH; }

        return {
            data: {
                id: node.id,
                name: name,
                resourceType: node.resourceType,
                schema: node.schema,
                database: node.database,
                materialization: node.materialization,
                description: node.description,
                filePath: node.filePath,
                depth: node.depth,
                isCurrent: node.isCurrent,
                stubDirection: node.stubDirection,
                boundaryNodeId: node.boundaryNodeId,
                columns: node.columns || [],
                iconKey: pickIconKey(node),
                barColor: pickBarColor(node, colorMode),
                parent: node.parent || undefined,
                w: w,
                h: h
            }
        };
    }

    function toCyEdgeElement(edge) {
        var isStub = edge.fromNodeId.indexOf('__stub_') === 0 || edge.toNodeId.indexOf('__stub_') === 0;
        return {
            data: {
                id: edge.fromNodeId + '->' + edge.toNodeId,
                source: edge.fromNodeId,
                target: edge.toNodeId
            },
            classes: isStub ? 'stub-edge' : ''
        };
    }

    // Show a centered message in the graph area by reusing the loading overlay.
    // Called by LineageTab when a selection resolves to zero nodes.
    window.showGraphMessage = function (msg) {
        if (!loadingEl) return;
        loadingEl.textContent = msg;
        loadingEl.style.display = '';
    };

    window.highlightNode = function (nodeId) {
        if (!cy) return;
        const node = cy.getElementById(nodeId);
        selectCards(new Set(node.length ? [nodeId] : []));
        if (node.length) {
            cy.animate({ center: { eles: node }, duration: 300 });
        }
    };

    function matchesQuery(nodeId, tokens) {
        if (!tokens.length) return true;
        var h = nodeSearchHints[nodeId];
        if (!h) return false;

        var bareTokens = [];
        var byKind = {};
        tokens.forEach(function (t) {
            if (t.kind === 'bare') bareTokens.push(t.value.toLowerCase());
            else { byKind[t.kind] = byKind[t.kind] || []; byKind[t.kind].push(t.value); }
        });

        // bare tokens AND across; each must match name or id
        for (var i = 0; i < bareTokens.length; i++) {
            var v = bareTokens[i];
            if ((h.lowerName || '').indexOf(v) < 0 && (h.lowerId || '').indexOf(v) < 0) return false;
        }
        // for each prefix kind, ANY token in that kind must match (OR within kind, AND across kinds)
        for (var key in byKind) {
            var vals = byKind[key];
            var anyHit = vals.some(function (v) { return matchKind(h, key, v); });
            if (!anyHit) return false;
        }
        return true;
    }

    function matchKind(h, kind, value) {
        switch (kind) {
            case 'col':    return (h.columnNamesLower || []).some(function (c) { return c.indexOf(value) >= 0; });
            case 'tag':    return (h.tagsLower || []).indexOf(value) >= 0;
            case 'schema': return (h.schemaLower || '').indexOf(value) >= 0;
            case 'mat':    return (h.materialization || '') === value;
            case 'type':   return (h.resourceType || '') === value;
            case 'pkg':    return (h.packageLower || '').indexOf(value) >= 0;
            default:       return false;
        }
    }

    var catalogToastShown = false;
    function maybeShowCatalogMissingToast(tokens) {
        if (catalogToastShown) return;
        if (window.__catalogAvailable) return;
        var usesCol = tokens.some(function (t) { return t.kind === 'col'; });
        if (!usesCol) return;
        catalogToastShown = true;
        var toast = document.createElement('div');
        toast.textContent = 'Column search uses schema.yml columns. For complete results, run `dbt docs generate`.';
        toast.style.cssText =
            'position: absolute; top: 8px; left: 50%; transform: translateX(-50%); ' +
            'background: var(--tooltip-bg); color: var(--tooltip-text); ' +
            'border: 1px solid var(--tooltip-border); padding: 6px 12px; ' +
            'border-radius: 6px; z-index: 2000; font-size: 11px;';
        document.body.appendChild(toast);
        setTimeout(function () { toast.remove(); }, 6000);
    }

    window.filterNodes = function (query) {
        if (!cy) return;
        if (!query || query.trim() === '') {
            resetFilter();
            return;
        }
        var tokens = tokenize(query);

        cy.nodes().forEach(function (node) {
            setDimmed(node, !matchesQuery(node.id(), tokens));
        });
        cy.edges().forEach(function (edge) {
            var src = edge.source();
            var tgt = edge.target();
            if (src.hasClass('dimmed') && tgt.hasClass('dimmed')) edge.addClass('dimmed');
            else edge.removeClass('dimmed');
        });

        maybeShowCatalogMissingToast(tokens);
    };

    window.resetFilter = clearNeighborhoodDim;

    // === Docs sidebar ===

    var sidebarEl = document.getElementById('docs-sidebar');
    var sidebarToggleBtn = document.getElementById('toggle-sidebar');
    var sidebarCloseBtn = document.getElementById('docs-close');

    function setSidebarOpen(open) {
        if (!sidebarEl) return;
        sidebarEl.classList.toggle('open', open);
        if (sidebarToggleBtn) sidebarToggleBtn.classList.toggle('active', open);
    }

    if (sidebarToggleBtn) {
        sidebarToggleBtn.addEventListener('click', function () {
            setSidebarOpen(!sidebarEl.classList.contains('open'));
        });
    }

    var regenerateBtn = document.getElementById('regenerate-docs');
    if (regenerateBtn) {
        regenerateBtn.addEventListener('click', function () {
            if (regenerateBtn.classList.contains('running')) return;
            regenerateBtn.classList.add('running');
            regenerateBtn.classList.remove('needs-attention');
            sendToKotlin('regenerateDocs', {});
        });
    }

    // Screenshot: hide the floating chrome, let it paint, then ask Kotlin to grab the
    // JCEF component's on-screen pixels. Kotlin calls restoreScreenshotChrome() when
    // done (success or failure); the timeout is a safety net if it never replies.
    var SCREENSHOT_CHROME_IDS = ['controls', 'minimap-wrap', 'search-box', 'docs-sidebar', 'tooltip', 'run-results-hint'];
    var screenshotRestoreTimer = null;
    var TRIM_MARGIN_PX = 150;

    // On-screen crop window (CSS px) so the screenshot is trimmed to the graph plus a
    // 150px margin on each side. Bounds every visible non-cluster node (stubs included);
    // clamps to the viewport so we never crop past what was captured. Empty graph → full width.
    function computeScreenshotCrop() {
        var containerWidth = cy ? Math.round(cy.width()) : 0;
        if (!cy) return { cropLeft: 0, cropRight: containerWidth };
        var zoom = cy.zoom();
        var minLeft = Infinity, maxRight = -Infinity;
        cy.nodes().forEach(function (n) {
            if (n.isParent()) return; // skip cluster/compound parents
            var pos = n.renderedPosition();
            var halfW = (n.width() * zoom) / 2;
            if (pos.x - halfW < minLeft) minLeft = pos.x - halfW;
            if (pos.x + halfW > maxRight) maxRight = pos.x + halfW;
        });
        if (!isFinite(minLeft) || !isFinite(maxRight)) {
            return { cropLeft: 0, cropRight: containerWidth };
        }
        return {
            cropLeft: Math.round(Math.max(0, minLeft - TRIM_MARGIN_PX)),
            cropRight: Math.round(Math.min(containerWidth, maxRight + TRIM_MARGIN_PX))
        };
    }

    var screenshotBtn = document.getElementById('copy-screenshot');

    // Hide (or show again) the floating chrome and lock the button while a capture runs.
    function setScreenshotChromeHidden(hidden) {
        if (screenshotBtn) screenshotBtn.disabled = hidden;
        SCREENSHOT_CHROME_IDS.forEach(function (id) {
            var el = document.getElementById(id);
            if (el) el.classList.toggle('screenshot-hidden', hidden);
        });
    }

    function beginScreenshot() {
        setScreenshotChromeHidden(true);
        if (screenshotRestoreTimer) clearTimeout(screenshotRestoreTimer);
        // 1.5s: generous upper bound for the native capture; on success Kotlin calls
        // restoreScreenshotChrome() well before this, which clears the timer.
        screenshotRestoreTimer = setTimeout(window.restoreScreenshotChrome, 1500);
        // Double rAF guarantees the hide has actually painted before Kotlin grabs.
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                sendToKotlin('captureViewport', computeScreenshotCrop());
            });
        });
    }

    window.restoreScreenshotChrome = function () {
        if (screenshotRestoreTimer) {
            clearTimeout(screenshotRestoreTimer);
            screenshotRestoreTimer = null;
        }
        setScreenshotChromeHidden(false);
    };

    if (screenshotBtn) {
        screenshotBtn.addEventListener('click', beginScreenshot);
    }

    window.setRegenerateNeedsAttention = function (needs) {
        if (regenerateBtn) regenerateBtn.classList.toggle('needs-attention', !!needs);
    };
    window.setRegenerateRunning = function (running) {
        if (regenerateBtn) regenerateBtn.classList.toggle('running', !!running);
    };
    if (sidebarCloseBtn) {
        sidebarCloseBtn.addEventListener('click', function () { setSidebarOpen(false); });
    }

    // Restore persisted width
    try {
        var savedW = localStorage.getItem('dbtHelper.sidebarWidth');
        if (savedW && sidebarEl) sidebarEl.style.width = savedW + 'px';
    } catch (e) {}

    // Apply wheel deltas directly + stop bubbling so the cytoscape canvas
    // beneath the sidebar doesn't waste compositing time on each event.
    (function () {
        var contentEl = document.querySelector('.docs-content');
        if (!contentEl || !sidebarEl) return;

        // Stop wheel propagation at the sidebar boundary
        sidebarEl.addEventListener('wheel', function (e) {
            e.stopPropagation();
        }, { capture: true });

        contentEl.addEventListener('wheel', function (e) {
            if (e.ctrlKey) return;
            var dy = e.deltaY;
            if (e.deltaMode === 1) dy *= 16;
            else if (e.deltaMode === 2) dy *= contentEl.clientHeight;
            contentEl.scrollTop += dy;
            e.preventDefault();
        }, { passive: false });
    })();



    // Resize handle drag
    var resizeHandle = document.getElementById('docs-resize');
    var resizing = null;
    if (resizeHandle) {
        resizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            var rect = sidebarEl.getBoundingClientRect();
            resizing = { startX: e.clientX, startW: rect.width };
            document.body.style.cursor = 'ew-resize';
            document.body.style.userSelect = 'none';
        });
    }
    window.addEventListener('mousemove', function (e) {
        if (!resizing) return;
        var dx = resizing.startX - e.clientX;
        var newW = resizing.startW + dx;
        var minW = 280;
        var maxW = window.innerWidth * 0.8;
        newW = Math.max(minW, Math.min(maxW, newW));
        sidebarEl.style.width = newW + 'px';
        if (cy) cy.resize();
    });
    window.addEventListener('mouseup', function () {
        if (!resizing) return;
        try {
            var w = sidebarEl.getBoundingClientRect().width;
            localStorage.setItem('dbtHelper.sidebarWidth', String(Math.round(w)));
        } catch (e) {}
        resizing = null;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
    });

    Array.prototype.forEach.call(document.querySelectorAll('.docs-tab'), function (tab) {
        tab.addEventListener('click', function () {
            var which = tab.dataset.tab;
            Array.prototype.forEach.call(document.querySelectorAll('.docs-tab'), function (t) {
                t.classList.toggle('active', t === tab);
            });
            Array.prototype.forEach.call(document.querySelectorAll('.docs-section'), function (s) {
                s.classList.toggle('active', s.id === 'docs-section-' + which);
            });
        });
    });

    function renderDocsHeader(p) {
        var iconEl = document.getElementById('docs-icon');
        var iconKey = pickIconKey({
            resourceType: p.kind,
            materialization: p.materialization
        });
        iconEl.innerHTML = ICONS[iconKey] || ICONS.view;
        setDocsHeader(p.name || '', p.schema || '');
    }

    function setDocsHeader(name, schema) {
        var nameEl = document.getElementById('docs-name');
        var schemaEl = document.getElementById('docs-schema');
        if (nameEl) nameEl.textContent = name;
        if (schemaEl) schemaEl.textContent = schema;
    }

    // <tr> rows of a .meta-table from [key, value] pairs.
    function metaTableRowsHtml(rows) {
        var html = '';
        for (var i = 0; i < rows.length; i++) {
            html += '<tr><td class="k">' + escapeHtml(rows[i][0]) + '</td><td class="v">' + escapeHtml(String(rows[i][1])) + '</td></tr>';
        }
        return html;
    }

    function renderDocsPills(p) {
        var pillsEl = document.getElementById('docs-pills');
        pillsEl.innerHTML = '';
        function add(text, cls) {
            var el = document.createElement('span');
            el.className = 'docs-pill' + (cls ? ' ' + cls : '');
            el.textContent = text;
            pillsEl.appendChild(el);
        }
        if (p.materialization) add(p.materialization);
        // Skip the incremental flag pill when materialization already says "incremental"
        if (p.kind === 'model' && p.materialization !== 'incremental') {
            add('incremental: ' + (p.incremental ? 'true' : 'false'));
        }
        var total = (p.tests && p.tests.total) || 0;
        if (total > 0) add(total + '/' + total + ' tests', 'tests-ok');
        var tags = (p.metadata && p.metadata.tags) || [];
        for (var i = 0; i < tags.length; i++) add('#' + tags[i], 'tag');
    }

    function renderDocsDescription(p) {
        var wrap = document.getElementById('docs-description');
        var textEl = document.getElementById('docs-desc-text');
        var desc = (p.metadata && p.metadata.description) || '';
        wrap.classList.toggle('empty', !desc);
        textEl.textContent = desc;
        // Default: expanded
        wrap.classList.add('expanded');
        textEl.classList.remove('collapsed');
        if (!desc) return;
        // Determine whether the toggle button is needed (multi-line / overflows when collapsed)
        requestAnimationFrame(function () {
            var hasMultiline = desc.indexOf('\n') >= 0;
            // Probe collapsed width by temporarily measuring
            textEl.classList.add('collapsed');
            var hasOverflow = textEl.scrollWidth > textEl.clientWidth + 1;
            textEl.classList.remove('collapsed');
            wrap.classList.toggle('has-overflow', hasMultiline || hasOverflow);
        });
    }

    var descToggleBtn = document.getElementById('docs-desc-toggle');
    if (descToggleBtn) {
        descToggleBtn.addEventListener('click', function () {
            var wrap = document.getElementById('docs-description');
            var textEl = document.getElementById('docs-desc-text');
            var expanded = wrap.classList.toggle('expanded');
            textEl.classList.toggle('collapsed', !expanded);
            descToggleBtn.title = expanded ? 'Collapse description' : 'Expand description';
        });
    }

    function renderDocsColumns(p) {
        var el = document.getElementById('docs-section-columns');
        var cols = (p.columns || []);
        if (!cols.length) { el.innerHTML = '<div class="empty">No columns documented.</div>'; return; }
        var html = '<div class="col-header"><span>Column</span><span>Type</span></div>';
        for (var i = 0; i < cols.length; i++) {
            var c = cols[i];
            html += '<div class="col-row">';
            html += '<div class="col-main">';
            html += '<div class="col-name">';
            if (c.isPrimaryKey) html += '<span class="col-pk-badge">PK</span>';
            html += escapeHtml(c.name);
            html += '</div>';
            if (c.fk) html += '<div class="col-fk">FK → ' + escapeHtml(c.fk) + '</div>';
            if (c.description) html += '<div class="col-desc">' + escapeHtml(c.description) + '</div>';
            html += '</div>';
            html += '<div class="col-type">' + escapeHtml(c.type || '') + '</div>';
            html += '</div>';
        }
        el.innerHTML = html;
    }

    function renderDocsTests(p) {
        var el = document.getElementById('docs-section-tests');
        var list = (p.tests && p.tests.list) || [];
        if (!list.length) { el.innerHTML = '<div class="empty">No tests defined.</div>'; return; }
        var html = '<ul class="docs-list">';
        for (var i = 0; i < list.length; i++) {
            var t = list[i];
            html += '<li>' + escapeHtml(t.shortName);
            if (t.column) html += ' <span style="color: var(--card-schema)">on ' + escapeHtml(t.column) + '</span>';
            var msg = t.uniqueId ? nodeFailureMessages[t.uniqueId] : null;
            if (msg) {
                html += '<div style="color: #F85149; margin-top: 2px; font-size: 11px;">' + escapeHtml(msg) + '</div>';
            }
            html += '</li>';
        }
        html += '</ul>';
        el.innerHTML = html;
    }

    function renderDocsSql(p) {
        var el = document.getElementById('docs-section-sql');
        var sql = (p.sql && (p.sql.raw || p.sql.compiled)) || '';
        if (!sql) { el.innerHTML = '<div class="empty">No SQL available.</div>'; return; }
        el.innerHTML = '<pre class="code-block">' + escapeHtml(sql) + '</pre>';
    }

    function renderDocsMetadata(p) {
        var el = document.getElementById('docs-section-metadata');
        var m = p.metadata || {};
        var rows = [];
        if (m.fullName) rows.push(['Relation', m.fullName]);
        if (m.filePath) rows.push(['File', m.filePath]);
        if (m.patchPath) rows.push(['Docs', m.patchPath]);
        if (m.packageName) rows.push(['Package', m.packageName]);
        if (m.fqn && m.fqn.length) rows.push(['FQN', m.fqn.join(' → ')]);
        if (m.tags && m.tags.length) rows.push(['Tags', m.tags.join(', ')]);
        if (m.description) rows.push(['Description', m.description]);
        if (m.dependsOn && m.dependsOn.length) rows.push(['Depends on', m.dependsOn.join(', ')]);
        if (m.referencedBy && m.referencedBy.length) rows.push(['Referenced by', m.referencedBy.join(', ')]);
        if (m.config) {
            Object.keys(m.config).forEach(function (k) { rows.push([k, m.config[k]]); });
        }
        if (m.loader) rows.push(['Loader', m.loader]);
        if (m.loadedAtField) rows.push(['Loaded at', m.loadedAtField]);
        if (m.freshnessWarnAfter) rows.push(['Warn after', m.freshnessWarnAfter]);
        if (m.freshnessErrorAfter) rows.push(['Error after', m.freshnessErrorAfter]);
        if (!rows.length) { el.innerHTML = '<div class="empty">No metadata.</div>'; return; }
        el.innerHTML = '<table class="meta-table">' + metaTableRowsHtml(rows) + '</table>';
    }

    window.showDocs = function (jsonStr) {
        try {
            var p = parseArg(jsonStr);
            if (sidebarEl) sidebarEl.classList.remove('freshness-mode');
            renderDocsHeader(p);
            renderDocsDescription(p);
            renderDocsPills(p);
            renderDocsColumns(p);
            renderDocsTests(p);
            renderDocsSql(p);
            renderDocsMetadata(p);
        } catch (e) {
            console.error('showDocs error:', e);
        }
    };

    var FRESHNESS_STATUS_META = {
        pass:      { label: 'Fresh',                                  cls: 'fresh-pass',      dot: '●' },
        warn:      { label: 'Stale (warn)',                           cls: 'fresh-warn',      dot: '●' },
        error:     { label: 'Stale (error)',                          cls: 'fresh-error',     dot: '⬤' },
        no_result: { label: 'No freshness result for this source',    cls: 'fresh-no_result', dot: '○' },
        no_data:   { label: 'sources.json not found',                 cls: 'fresh-no_data',   dot: '○' }
    };

    window.showFreshnessDetail = function (jsonStr) {
        try {
            var p = parseArg(jsonStr);
            setDocsHeader(p.name || '—', p.relation || '');

            var panel = document.getElementById('docs-freshness-panel');
            if (!panel) return;

            var meta = FRESHNESS_STATUS_META[p.status] || FRESHNESS_STATUS_META.no_result;

            var html = '';
            html += '<div class="freshness-banner ' + meta.cls + '">';
            html += '<span class="dot">' + meta.dot + '</span><span>' + escapeHtml(meta.label) + '</span>';
            html += '</div>';

            if (p.message) {
                html += '<div class="freshness-message">' + escapeHtml(p.message) + '</div>';
            }

            if (p.status === 'no_data') {
                html += '<div class="freshness-hint">Run <code>dbt source freshness</code> to populate <code>target/sources.json</code>.</div>';
            } else if (p.status === 'no_result') {
                html += '<div class="freshness-hint">No freshness criteria configured for this source, or it was excluded from the last freshness run.</div>';
            }

            var rows = [];
            if (p.loadedAtField) rows.push(['Loaded at', p.loadedAtField]);
            if (p.warnAfter) rows.push(['Warn after', p.warnAfter]);
            if (p.errorAfter) rows.push(['Error after', p.errorAfter]);
            if (p.filePath) rows.push(['Defined in', p.filePath]);
            if (rows.length) {
                html += '<table class="meta-table" style="margin-top: 8px;">' + metaTableRowsHtml(rows) + '</table>';
            }

            html += '<div class="freshness-back-row"><button class="freshness-back-btn" id="freshness-back-btn">View source docs</button></div>';
            panel.innerHTML = html;

            var backBtn = document.getElementById('freshness-back-btn');
            if (backBtn) {
                backBtn.addEventListener('click', function () {
                    sendToKotlin('previewNode', { nodeId: p.id });
                });
            }

            if (sidebarEl) {
                sidebarEl.classList.add('freshness-mode');
                setSidebarOpen(true);
            }
        } catch (e) {
            console.error('showFreshnessDetail error:', e);
        }
    };

    window.showMultiSelectPlaceholder = function (count) {
        // Update sidebar header
        var iconEl = document.getElementById('docs-icon');
        var descEl = document.getElementById('docs-description');
        var pillsEl = document.getElementById('docs-pills');
        if (iconEl) iconEl.textContent = '';
        setDocsHeader(count + ' nodes selected', '');
        if (descEl) { descEl.classList.add('empty'); document.getElementById('docs-desc-text').textContent = ''; }
        if (pillsEl) pillsEl.textContent = '';
        // Clear all section content and show a message in the columns section
        var sections = document.querySelectorAll('.docs-section');
        sections.forEach(function (s) { s.textContent = ''; s.classList.remove('active'); });
        var colSection = document.getElementById('docs-section-columns');
        if (colSection) {
            colSection.classList.add('active');
            var hint = document.createElement('p');
            hint.className = 'empty';
            hint.textContent = 'Click one node to see its details.';
            colSection.appendChild(hint);
        }
        // Ensure sidebar is open
        setSidebarOpen(true);
    };

    // Apply a palette resolved from the live IDE theme (see LineageTab.buildThemeVars).
    // Payload: { isDark: bool, vars: { '--css-var': '#rrggbb', ... } }.
    window.applyThemeColors = function (jsonStr) {
        try {
            var data = parseArg(jsonStr);
            var root = document.documentElement;
            document.body.classList.toggle('theme-light', !data.isDark);
            Object.keys(data.vars).forEach(function (k) {
                root.style.setProperty(k, data.vars[k]);
            });
            // Cytoscape edges are styled imperatively, not via CSS vars — remember the
            // color so every render re-applies it, and push it to the live graph now.
            themeEdgeColor = data.vars['--edge-color'] || themeEdgeColor;
            if (cy && themeEdgeColor) {
                cy.edges().style({ 'line-color': themeEdgeColor, 'target-arrow-color': themeEdgeColor });
            }
        } catch (e) { console.error('applyThemeColors error:', e); }
    };

    // === Bridge to Kotlin ===

    function sendToKotlin(type, payload) {
        const request = JSON.stringify({ type: type, payload: payload });
        if (window.__cefQueryBridge) {
            window.__cefQueryBridge(request);
        }
    }

    // Cluster mode dropdown — custom (not a native <select>), because JCEF's
    // off-screen renderer mis-positions native popups (they open upward, off-screen).
    var clusterMode = document.getElementById('cluster-mode');
    var clusterMenu = document.getElementById('cluster-mode-menu');
    var clusterLabel = document.getElementById('cluster-mode-label');
    var clusterValue = 'none';

    function refreshClusterMode() {
        var options = clusterMenu.querySelectorAll('.custom-select-option');
        options.forEach(function (opt) {
            var selected = opt.getAttribute('data-value') === clusterValue;
            opt.classList.toggle('selected', selected);
            if (selected) clusterLabel.textContent = opt.textContent;
        });
    }

    if (clusterMode) {
        clusterMode.addEventListener('click', function (e) {
            e.stopPropagation();
            clusterMenu.classList.toggle('open');
        });
        clusterMenu.addEventListener('click', function (e) {
            var opt = e.target.closest('.custom-select-option');
            if (!opt) return;
            // Don't let this bubble to the #cluster-mode toggle handler, which would
            // re-open the menu we're about to close.
            e.stopPropagation();
            clusterValue = opt.getAttribute('data-value');
            refreshClusterMode();
            clusterMenu.classList.remove('open');
            sendToKotlin('clusterModeChanged', { mode: clusterValue });
        });
        document.addEventListener('click', function () {
            clusterMenu.classList.remove('open');
        });
        refreshClusterMode();
    }
    window.setClusterMode = function (mode) {
        clusterValue = mode || 'none';
        refreshClusterMode();
    };

    // Search input
    var searchInput = document.getElementById('search-input');
    var searchClear = document.getElementById('search-clear');
    var searchDebounce = null;

    function updateClearButton() {
        searchClear.style.display = searchInput.value.length > 0 ? 'block' : 'none';
    }

    searchInput.addEventListener('input', function (e) {
        updateClearButton();
        clearTimeout(searchDebounce);
        searchDebounce = setTimeout(function () {
            window.filterNodes(e.target.value);
        }, 150);
    });

    searchClear.addEventListener('click', function () {
        searchInput.value = '';
        updateClearButton();
        window.resetFilter();
        searchInput.focus();
    });

    (function () {
        var btn = document.getElementById('search-help');
        if (!btn) return;
        var popup = null;
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            if (popup) { popup.remove(); popup = null; return; }
            popup = document.createElement('div');
            popup.className = 'help-popup';
            // Note: the HTML here is built from constant strings (no user input).
            popup.innerHTML = '' +
                '<div style="margin-bottom: 4px;"><b>Search syntax</b></div>' +
                '<table>' +
                '<tr><td class="k">name</td><td>Match name/id (default)</td></tr>' +
                '<tr><td class="k">col:name</td><td>Models with column matching name</td></tr>' +
                '<tr><td class="k">tag:value</td><td>Models with tag</td></tr>' +
                '<tr><td class="k">schema:name</td><td>Models in schema</td></tr>' +
                '<tr><td class="k">mat:type</td><td>Materialization (table, view, incremental, …)</td></tr>' +
                '<tr><td class="k">type:kind</td><td>Resource type (model, source, seed, …)</td></tr>' +
                '<tr><td class="k">pkg:name</td><td>Package name</td></tr>' +
                '</table>';
            document.body.appendChild(popup);
            var r = btn.getBoundingClientRect();
            popup.style.left = (r.left) + 'px';
            popup.style.top = (r.bottom + 4) + 'px';
        });
        document.addEventListener('click', function () {
            if (popup) { popup.remove(); popup = null; }
        });
    })();

    // Signal ready
    window.addEventListener('DOMContentLoaded', function () {
        setTimeout(function () {
            sendToKotlin('ready', {});
        }, 100);
    });

    // === Mini-map ===

    var minimapVisible = false;
    var minimapBtn = document.getElementById('minimap-toggle');
    var minimapWrap = document.getElementById('minimap-wrap');
    var minimapCanvas = document.getElementById('minimap');
    var minimapCtx = minimapCanvas ? minimapCanvas.getContext('2d') : null;
    var minimapTransform = null;

    function loadMinimapPref() {
        try {
            var saved = localStorage.getItem(projectStorageKey('minimap'));
            if (saved === '1') setMinimapVisible(true);
        } catch (e) {}
    }
    function setMinimapVisible(v) {
        minimapVisible = !!v;
        if (minimapWrap) minimapWrap.style.display = v ? 'block' : 'none';
        if (minimapBtn) minimapBtn.classList.toggle('active', v);
        try { localStorage.setItem(projectStorageKey('minimap'), v ? '1' : '0'); } catch (e) {}
        if (v) drawMinimap();
    }
    if (minimapBtn) minimapBtn.addEventListener('click', function () { setMinimapVisible(!minimapVisible); });

    function drawMinimap() {
        if (!minimapVisible || !cy || !minimapCtx) return;
        var ctx = minimapCtx;
        var w = minimapCanvas.width;
        var h = minimapCanvas.height;
        ctx.clearRect(0, 0, w, h);

        var nodes = cy.nodes();
        if (nodes.length === 0) return;
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        nodes.forEach(function (n) {
            var p = n.position();
            var nw = n.width(), nh = n.height();
            minX = Math.min(minX, p.x - nw/2);
            minY = Math.min(minY, p.y - nh/2);
            maxX = Math.max(maxX, p.x + nw/2);
            maxY = Math.max(maxY, p.y + nh/2);
        });
        var pad = 20;
        var bw = (maxX - minX) || 1;
        var bh = (maxY - minY) || 1;
        var scale = Math.min((w - pad*2) / bw, (h - pad*2) / bh);
        function tx(x) { return pad + (x - minX) * scale; }
        function ty(y) { return pad + (y - minY) * scale; }

        ctx.fillStyle = '#666';
        nodes.forEach(function (n) {
            var p = n.position();
            var nw = n.width() * scale;
            var nh = n.height() * scale;
            ctx.fillRect(tx(p.x) - nw/2, ty(p.y) - nh/2, Math.max(2, nw), Math.max(2, nh));
        });

        var pan = cy.pan();
        var zoom = cy.zoom();
        var vp = cy.container().getBoundingClientRect();
        var vpModelW = vp.width / zoom;
        var vpModelH = vp.height / zoom;
        var vpModelX = -pan.x / zoom;
        var vpModelY = -pan.y / zoom;
        ctx.strokeStyle = '#4E79A7';
        ctx.lineWidth = 1;
        ctx.strokeRect(tx(vpModelX), ty(vpModelY), vpModelW * scale, vpModelH * scale);

        minimapTransform = { scale: scale, minX: minX, minY: minY, pad: pad };
    }

    if (minimapCanvas) {
        var miniDragging = false;
        function modelFromMinimap(e) {
            var rect = minimapCanvas.getBoundingClientRect();
            var x = e.clientX - rect.left;
            var y = e.clientY - rect.top;
            if (!minimapTransform) return null;
            var t = minimapTransform;
            var modelX = t.minX + (x - t.pad) / t.scale;
            var modelY = t.minY + (y - t.pad) / t.scale;
            return { x: modelX, y: modelY };
        }
        minimapCanvas.addEventListener('mousedown', function (e) {
            e.preventDefault();
            miniDragging = true;
            if (minimapWrap) minimapWrap.classList.add('cy-minimap-pan');
            var p = modelFromMinimap(e);
            if (p && cy) centerCyOn(p);
        });
        window.addEventListener('mousemove', function (e) {
            if (!miniDragging) return;
            var p = modelFromMinimap(e);
            if (p && cy) centerCyOn(p);
        });
        window.addEventListener('mouseup', function () {
            if (!miniDragging) return;
            miniDragging = false;
            if (minimapWrap) minimapWrap.classList.remove('cy-minimap-pan');
        });
    }
    function centerCyOn(model) {
        if (!cy) return;
        var zoom = cy.zoom();
        var w = cy.container().clientWidth;
        var h = cy.container().clientHeight;
        cy.pan({ x: -model.x * zoom + w / 2, y: -model.y * zoom + h / 2 });
    }
})();
