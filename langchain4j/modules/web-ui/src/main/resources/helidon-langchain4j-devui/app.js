const state = {
    agents: [],
    selectedAgent: null,
    selectedMethod: null,
    conversations: {},
    inspector: {
        markup: {
            state: null,
            trace: null,
            event: null
        }
    },
    tracking: {
        enabled: false,
        inspection: null,
        loading: false,
        polling: false,
        timerId: null
    }
};

const INVOCATION_POLL_INTERVAL_MS = 180;
const TRACKING_POLL_INTERVAL_MS = 240;
const AGENT_GRAPH_LAYOUT = Object.freeze({
    paddingX: 18,
    paddingY: 18,
    columnGap: 20,
    rowGap: 86,
    nameWidthCharLimit: 35,
    agentMinWidth: 162,
    agentMaxWidth: 330,
    agentHeight: 84,
    toolMinWidth: 148,
    toolMaxWidth: 312,
    toolHeight: 60
});

const basePath = (() => {
    const path = window.location.pathname;
    if (path.endsWith("/")) {
        return path.slice(0, -1);
    }
    return path.replace(/\/[^/]+$/, "");
})();

const agentList = document.getElementById("agent-list");
const appLayout = document.getElementById("app-layout");
const refreshAgentsButton = document.getElementById("refresh-agents");
const trackingToggle = document.getElementById("tracking-toggle");
const trackingToggleLabel = document.getElementById("tracking-toggle-label");
const methodSelect = document.getElementById("method-select");
const invokeForm = document.getElementById("invoke-form");
const workspacePanel = document.getElementById("workspace-panel");
const workspaceTitle = document.getElementById("workspace-title");
const conversationTitle = document.getElementById("conversation-title");
const conversationProgress = document.getElementById("conversation-progress");
const composerTitle = document.getElementById("composer-title");
const composerNote = document.getElementById("composer-note");
const invokeButton = document.getElementById("invoke-button");
const clearConversationButton = document.getElementById("clear-conversation");
const chatHistory = document.getElementById("chat-history");
const resultView = document.getElementById("result-view");
const resultSummary = document.getElementById("result-summary");
const stateView = document.getElementById("state-view");
const stateStatus = document.getElementById("state-status");
const traceView = document.getElementById("trace-view");
const eventView = document.getElementById("event-view");
const traceCount = document.getElementById("trace-count");
const eventCount = document.getElementById("event-count");
const agentChip = document.getElementById("agent-chip");
const methodChip = document.getElementById("method-chip");
const toast = document.getElementById("toast");

const CODE_LANGUAGE_ALIASES = {
    sh: "bash",
    shell: "bash",
    "shell-session": "bash",
    shellsession: "bash",
    console: "bash",
    zsh: "bash",
    yml: "yaml",
    xsd: "xml",
    xsl: "xml",
    xslt: "xml",
    wsdl: "xml",
    xhtml: "xml",
    svg: "xml",
    pom: "xml",
    "pom.xml": "xml"
};

const JAVA_KEYWORDS = new Set([
    "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default",
    "do", "else", "enum", "exports", "extends", "final", "finally", "for", "goto", "if",
    "implements", "import", "instanceof", "interface", "module", "native", "new", "open",
    "opens", "package", "permits", "private", "protected", "provides", "public", "record",
    "requires", "return", "sealed", "static", "strictfp", "super", "switch", "synchronized",
    "this", "throw", "throws", "to", "transitive", "try", "uses", "var", "volatile", "while",
    "with", "yield"
]);

const JAVA_PRIMITIVE_TYPES = new Set([
    "boolean", "byte", "char", "double", "float", "int", "long", "short", "void"
]);

const JAVA_MULTI_CHAR_OPERATORS = [
    ">>>=",
    "<<=",
    ">>=",
    ">>>",
    ">>",
    "<<",
    "::",
    "->",
    "==",
    "!=",
    ">=",
    "<=",
    "&&",
    "||",
    "++",
    "--",
    "+=",
    "-=",
    "*=",
    "/=",
    "%=",
    "&=",
    "|=",
    "^="
];

const BASH_KEYWORDS = new Set([
    "case", "coproc", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if",
    "in", "select", "then", "time", "until", "while"
]);

const BASH_COMMAND_KEYWORDS = new Set([
    "coproc", "do", "elif", "else", "if", "then", "time", "until", "while"
]);

const BASH_BUILTINS = new Set([
    "alias", "bg", "bind", "break", "builtin", "caller", "cd", "command", "compgen",
    "complete", "compopt", "continue", "declare", "dirs", "disown", "echo", "enable", "eval",
    "exec", "exit", "export", "false", "fc", "fg", "getopts", "hash", "help", "history",
    "jobs", "kill", "let", "local", "logout", "mapfile", "popd", "printf", "pushd", "pwd",
    "read", "readonly", "return", "set", "shift", "shopt", "source", "suspend", "test",
    "times", "trap", "true", "type", "typeset", "ulimit", "umask", "unalias", "unset", "wait"
]);

const BASH_MULTI_CHAR_OPERATORS = [
    "<<<",
    "<<-",
    ">>",
    "<<",
    "||",
    "&&",
    "|&",
    ";;&",
    ";;",
    ";&",
    "[[",
    "]]",
    "((",
    "))",
    "==",
    "!=",
    "=~",
    "<=",
    ">="
];

const BASH_COMMAND_SEPARATOR_OPERATORS = new Set(["&&", "||", "|", "|&", ";", ";;", ";&", ";;&", "&"]);
const YAML_BOOLEAN_LITERALS = new Set(["false", "no", "off", "on", "true", "yes"]);
const YAML_NULL_LITERALS = new Set(["null", "~"]);

if (refreshAgentsButton) {
    refreshAgentsButton.addEventListener("click", loadAgents);
}
if (trackingToggle) {
    trackingToggle.addEventListener("click", () => {
        toggleTracking().catch(showError);
    });
}
clearConversationButton.addEventListener("click", clearConversation);
invokeForm.addEventListener("submit", (event) => {
    event.preventDefault();
    invokeAgent().catch(showError);
});

methodSelect.addEventListener("change", () => {
    if (!state.selectedAgent) {
        return;
    }
    selectMethod(methodSelect.value);
});

invokeForm.addEventListener("keydown", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLTextAreaElement)) {
        return;
    }
    if (event.key !== "Enter") {
        return;
    }

    const isChatInput = target.dataset.chatInput === "true";
    const shouldSubmit = isChatInput ? !event.shiftKey : (event.metaKey || event.ctrlKey);
    if (shouldSubmit) {
        event.preventDefault();
        invokeAgent().catch(showError);
    }
});

document.querySelectorAll(".tab-button").forEach((button) => {
    button.addEventListener("click", () => switchTab(button.dataset.tab));
});

initialize().catch(showError);

async function initialize() {
    renderTrackingToggle();
    syncTrackingLayout();
    await loadAgents();
    await loadTrackingStatus();
}

async function api(path, options = {}) {
    const response = await fetch(`${basePath}${path}`, {
        headers: {
            "content-type": "application/json",
            ...(options.headers || {})
        },
        ...options
    });

    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) {
        throw new Error(data?.error || `Request failed with status ${response.status}`);
    }
    return data;
}

async function loadAgents() {
    state.agents = await api("/api/agents", {headers: {}});
    if (!state.selectedAgent && state.agents.length > 0) {
        state.selectedAgent = state.agents[0];
        state.selectedMethod = state.selectedAgent.methods[0] || null;
    } else if (state.selectedAgent) {
        const refreshedAgent = state.agents.find((agent) => agent.name === state.selectedAgent.name) || null;
        state.selectedAgent = refreshedAgent;
        state.selectedMethod = refreshedAgent
            ? refreshedAgent.methods.find((method) => method.id === state.selectedMethod?.id) || refreshedAgent.methods[0] || null
            : null;
    }

    if (!state.selectedAgent && state.agents.length > 0) {
        state.selectedAgent = state.agents[0];
        state.selectedMethod = state.selectedAgent.methods[0] || null;
    }

    renderAgents();
    renderWorkspace();
}

async function loadTrackingStatus() {
    const response = await api("/api/tracking", {headers: {}});
    applyTrackingResponse(response);
    renderInspector();
    if (state.tracking.enabled) {
        scheduleTrackingPoll();
    }
}

async function toggleTracking() {
    if (state.tracking.loading) {
        return;
    }

    state.tracking.loading = true;
    renderTrackingToggle();

    try {
        const response = await api("/api/tracking", {
            method: "POST",
            body: JSON.stringify({
                trackAllEvents: !state.tracking.enabled
            })
        });
        applyTrackingResponse(response);
        if (state.tracking.enabled) {
            scheduleTrackingPoll(0);
            toastMessage("Tracking all agent events");
        } else {
            stopTrackingPolling({clearInspection: true});
            toastMessage("Stopped tracking app-wide agent events");
        }
        renderInspector();
    } finally {
        state.tracking.loading = false;
        renderTrackingToggle();
    }
}

function applyTrackingResponse(response) {
    const wasEnabled = state.tracking.enabled;
    state.tracking.enabled = Boolean(response?.trackAllEvents);
    state.tracking.inspection = response?.inspection || null;
    renderTrackingToggle();
    syncTrackingLayout();
    if (!wasEnabled && state.tracking.enabled) {
        ensureTrackingTabVisible();
    }
}

function renderTrackingToggle() {
    if (!trackingToggle) {
        return;
    }

    trackingToggle.classList.toggle("is-active", state.tracking.enabled);
    trackingToggle.classList.toggle("is-loading", state.tracking.loading);
    trackingToggle.setAttribute("aria-pressed", state.tracking.enabled ? "true" : "false");
    trackingToggle.disabled = state.tracking.loading;
    trackingToggle.title = state.tracking.enabled
        ? "Showing app-wide agent events, trace, and state in the inspector."
        : "Track agent events triggered outside browser-started invocations.";
    trackingToggleLabel.textContent = state.tracking.loading
        ? state.tracking.enabled ? "Stopping..." : "Starting..."
        : state.tracking.enabled ? "Tracking all events" : "Track all events";
}

function syncTrackingLayout() {
    const trackingAllEvents = state.tracking.enabled;
    if (appLayout) {
        appLayout.classList.toggle("is-tracking-all", trackingAllEvents);
    }
    if (workspacePanel) {
        workspacePanel.hidden = trackingAllEvents;
        workspacePanel.setAttribute("aria-hidden", trackingAllEvents ? "true" : "false");
    }
}

function scheduleTrackingPoll(delayMs = TRACKING_POLL_INTERVAL_MS) {
    window.clearTimeout(state.tracking.timerId);
    state.tracking.timerId = null;
    if (!state.tracking.enabled) {
        return;
    }
    state.tracking.timerId = window.setTimeout(() => {
        pollTracking().catch(showError);
    }, delayMs);
}

function stopTrackingPolling({clearInspection = false} = {}) {
    window.clearTimeout(state.tracking.timerId);
    state.tracking.timerId = null;
    state.tracking.polling = false;
    if (clearInspection) {
        state.tracking.inspection = null;
    }
}

async function pollTracking() {
    if (!state.tracking.enabled || state.tracking.polling) {
        return;
    }

    state.tracking.polling = true;
    try {
        const response = await api("/api/tracking", {headers: {}});
        applyTrackingResponse(response);
        renderInspector();
        if (!state.tracking.enabled) {
            stopTrackingPolling({clearInspection: true});
            return;
        }
    } finally {
        state.tracking.polling = false;
    }

    scheduleTrackingPoll();
}

function selectAgent(agentName) {
    state.selectedAgent = state.agents.find((agent) => agent.name === agentName) || null;
    state.selectedMethod = state.selectedAgent?.methods[0] || null;
    renderAgents();
    renderWorkspace();
}

function selectMethod(methodId) {
    if (!state.selectedAgent) {
        state.selectedMethod = null;
    } else {
        state.selectedMethod = state.selectedAgent.methods.find((method) => method.id === methodId) || null;
    }
    renderWorkspace();
}

function renderAgents() {
    if (state.agents.length === 0) {
        agentList.innerHTML = `<div class="empty-state">No LangChain4j agents or services were discovered.</div>`;
        return;
    }

    const graph = buildAgentGraph(state.agents);
    agentList.innerHTML = renderAgentGraph(graph);

    agentList.querySelectorAll("button[data-agent]").forEach((button) => {
        button.addEventListener("click", () => selectAgent(button.dataset.agent));
    });

    syncAgentGraphActivity();
}

function buildAgentGraph(agents) {
    const selectedAgentKey = state.selectedAgent ? agentGraphAgentKey(state.selectedAgent.name) : "";
    const nodesByKey = new Map();
    const edgesByKey = new Map();

    (agents || []).forEach((agent) => {
        const node = createAgentGraphAgentNode(agent, selectedAgentKey);
        nodesByKey.set(node.key, node);
    });

    (agents || []).forEach((agent) => {
        const sourceKey = agentGraphAgentKey(agent.name);

        (agent.relations || []).forEach((relation) => {
            const targetName = relation.targetAgent || shortTypeName(relation.targetTypeName) || "agent";
            const targetKey = agentGraphAgentKey(targetName);
            if (!nodesByKey.has(targetKey)) {
                nodesByKey.set(targetKey, createAgentGraphPlaceholderNode(targetName,
                                                                          relation.targetTypeName || "",
                                                                          relation.kind || "agent"));
            }

            const edgeKey = `agent-edge::${sourceKey}::${relation.kind || "sequence"}::${relation.method || ""}::${targetKey}`;
            if (!edgesByKey.has(edgeKey)) {
                edgesByKey.set(edgeKey, {
                    key: edgeKey,
                    sourceKey,
                    targetKey,
                    kind: relation.kind || "sequence",
                    label: relation.method || "",
                    detail: relation.description || ""
                });
            }
        });

        (agent.tools || []).forEach((tool) => {
            const toolNode = createAgentGraphToolNode(tool);
            if (!nodesByKey.has(toolNode.key)) {
                nodesByKey.set(toolNode.key, toolNode);
            }

            const edgeKey = `tool-edge::${sourceKey}::${toolNode.key}`;
            if (!edgesByKey.has(edgeKey)) {
                edgesByKey.set(edgeKey, {
                    key: edgeKey,
                    sourceKey,
                    targetKey: toolNode.key,
                    kind: tool.kind === "guardrail" ? "guardrail" : "tool",
                    label: tool.owner || tool.kind || "tool",
                    detail: tool.description || ""
                });
            }
        });
    });

    return layoutAgentGraph({
        nodes: Array.from(nodesByKey.values()),
        edges: Array.from(edgesByKey.values()),
        selectedAgentKey
    });
}

function createAgentGraphAgentNode(agent, selectedAgentKey) {
    const width = agentGraphNodeWidth("agent", agent.name);
    const meta = agentGraphAgentMeta(agent);
    return {
        key: agentGraphAgentKey(agent.name),
        nodeKind: "agent",
        category: agent.kind || "agent",
        name: agent.name,
        meta,
        detail: agent.description || agent.interfaceName || "",
        width,
        height: agentGraphNodeHeight("agent", width, agent.name, meta),
        selectable: true,
        active: agentGraphAgentKey(agent.name) === selectedAgentKey
    };
}

function createAgentGraphPlaceholderNode(name, typeName, relationKind) {
    const width = agentGraphNodeWidth("agent", name);
    const meta = "Referenced agent";
    return {
        key: agentGraphAgentKey(name),
        nodeKind: "agent",
        category: relationKind === "conditional" ? "router" : "agent",
        name,
        meta,
        detail: typeName || name,
        width,
        height: agentGraphNodeHeight("agent", width, name, meta),
        selectable: false,
        active: false
    };
}

function createAgentGraphToolNode(tool) {
    const kind = tool.kind || "tool";
    const name = tool.name || "tool";
    const width = agentGraphNodeWidth("tool", name);
    const meta = agentGraphToolMeta(tool);
    return {
        key: tool.key || `${kind}::${name}`,
        nodeKind: "tool",
        category: kind,
        name,
        meta,
        detail: tool.description || tool.typeName || tool.owner || "",
        width,
        height: agentGraphNodeHeight("tool", width, name, meta),
        selectable: false,
        active: false
    };
}

function agentGraphAgentKey(name) {
    return `agent::${name}`;
}

function agentGraphAgentMeta(agent) {
    const tools = agent.tools || [];
    const guardrailCount = tools.filter((tool) => tool.kind === "guardrail").length;
    const toolCount = tools.filter((tool) => tool.kind !== "guardrail").length;
    const summary = [`${agent.methods?.length || 0} method${(agent.methods?.length || 0) === 1 ? "" : "s"}`];
    if ((agent.relations || []).length > 0) {
        summary.push(`${agent.relations.length} link${agent.relations.length === 1 ? "" : "s"}`);
    }
    if (toolCount > 0) {
        summary.push(`${toolCount} tool${toolCount === 1 ? "" : "s"}`);
    }
    if (guardrailCount > 0) {
        summary.push(`${guardrailCount} guardrail${guardrailCount === 1 ? "" : "s"}`);
    }
    return summary.join(" • ");
}

function agentGraphKindLabel(kind) {
    if (kind === "service") {
        return "AI service";
    }
    if (kind === "router") {
        return "Conditional agent";
    }
    if (kind === "workflow") {
        return "Workflow agent";
    }
    return "Agent";
}

function agentGraphToolMeta(tool) {
    if (tool.kind === "guardrail") {
        return tool.owner || "Guardrail";
    }
    if (tool.kind === "mcp-client") {
        return "MCP client";
    }
    if (tool.kind === "tool-provider") {
        return "Tool provider";
    }
    return tool.owner || "Tool";
}

function agentGraphNodeHeight(nodeKind, width, name, meta) {
    const labelFontSize = nodeKind === "tool" ? 13.2 : 13.8;
    const metaFontSize = 10.2;
    const lineWidth = Math.max(56, width - 22);
    const labelLines = estimateAgentGraphWrappedLines(name, lineWidth, labelFontSize, 0.57);
    const metaLines = meta ? estimateAgentGraphWrappedLines(meta, lineWidth, metaFontSize, 0.56) : 0;
    const topLineHeight = 20;
    const labelHeight = labelLines * 15.2;
    const metaHeight = metaLines * 11.8;
    const spacing = 18 + 4 + (metaLines > 0 ? 3 : 0);
    const computed = topLineHeight + labelHeight + metaHeight + spacing;
    return Math.max(nodeKind === "tool" ? AGENT_GRAPH_LAYOUT.toolHeight : AGENT_GRAPH_LAYOUT.agentHeight,
                    Math.ceil(computed));
}

function agentGraphNodeWidth(nodeKind, name) {
    const labelFontSize = nodeKind === "tool" ? 13.2 : 13.8;
    const widthFactor = 0.57;
    const paddingAllowance = nodeKind === "tool" ? 42 : 46;
    const displayChars = Math.min(agentGraphDisplayNameLength(name), AGENT_GRAPH_LAYOUT.nameWidthCharLimit);
    const computed = Math.ceil(displayChars * labelFontSize * widthFactor + paddingAllowance);
    const minWidth = nodeKind === "tool" ? AGENT_GRAPH_LAYOUT.toolMinWidth : AGENT_GRAPH_LAYOUT.agentMinWidth;
    const maxWidth = nodeKind === "tool" ? AGENT_GRAPH_LAYOUT.toolMaxWidth : AGENT_GRAPH_LAYOUT.agentMaxWidth;
    return clamp(computed, minWidth, maxWidth);
}

function agentGraphDisplayNameLength(name) {
    return Math.max(...String(name || "")
        .split(/\r?\n/)
        .map((line) => Math.max(1, line.trim().length)), 1);
}

function estimateAgentGraphWrappedLines(text, lineWidth, fontSize, charWidthFactor) {
    const normalized = String(text || "");
    if (!normalized) {
        return 0;
    }
    const averageCharWidth = Math.max(5.2, fontSize * charWidthFactor);
    const charsPerLine = Math.max(4, Math.floor(lineWidth / averageCharWidth));
    return normalized.split(/\r?\n/).reduce((lineCount, line) => {
        return lineCount + Math.max(1, Math.ceil(line.length / charsPerLine));
    }, 0);
}

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function layoutAgentGraph(graph) {
    const nodes = graph.nodes || [];
    const edges = graph.edges || [];
    const nodesByKey = new Map(nodes.map((node) => [node.key, node]));
    const workflowEdges = edges.filter((edge) => edge.kind === "sequence" || edge.kind === "conditional");
    const depthByKey = computeAgentGraphDepths(nodes, workflowEdges);

    const incomingByKey = new Map(nodes.map((node) => [node.key, []]));
    edges.forEach((edge) => {
        if (incomingByKey.has(edge.targetKey)) {
            incomingByKey.get(edge.targetKey).push(edge.sourceKey);
        }
    });

    nodes.filter((node) => node.nodeKind === "tool").forEach((node) => {
        const parents = incomingByKey.get(node.key) || [];
        const depth = parents.reduce((maxDepth, parentKey) => Math.max(maxDepth, depthByKey.get(parentKey) ?? 0), 0);
        depthByKey.set(node.key, depth + 1);
    });

    const layers = new Map();
    nodes.forEach((node) => {
        const depth = depthByKey.get(node.key) ?? 0;
        if (!layers.has(depth)) {
            layers.set(depth, []);
        }
        layers.get(depth).push(node);
    });

    const layerOrderHints = new Map();
    const orderedLayers = Array.from(layers.entries())
        .sort((left, right) => left[0] - right[0])
        .map(([depth, layerNodes]) => {
            const ordered = layerNodes.slice().sort((left, right) => {
                const leftBarycenter = agentGraphNodeBarycenter(left, incomingByKey, layerOrderHints);
                const rightBarycenter = agentGraphNodeBarycenter(right, incomingByKey, layerOrderHints);
                const leftFinite = Number.isFinite(leftBarycenter);
                const rightFinite = Number.isFinite(rightBarycenter);
                if (leftFinite && rightFinite && leftBarycenter !== rightBarycenter) {
                    return leftBarycenter - rightBarycenter;
                }
                if (leftFinite !== rightFinite) {
                    return leftFinite ? -1 : 1;
                }
                if (left.nodeKind !== right.nodeKind) {
                    return left.nodeKind === "agent" ? -1 : 1;
                }
                return left.name.localeCompare(right.name);
            });

            ordered.forEach((node, index) => {
                layerOrderHints.set(node.key, index);
            });

            const width = ordered.reduce((sum, node, index) => sum + node.width + (index > 0 ? AGENT_GRAPH_LAYOUT.columnGap : 0), 0);
            const height = ordered.reduce((maxHeight, node) => Math.max(maxHeight, node.height), 0);
            return {depth, nodes: ordered, width, height};
        });

    const widestLayer = orderedLayers.reduce((maxWidth, layer) => Math.max(maxWidth, layer.width), 0);
    const graphWidth = Math.max(AGENT_GRAPH_LAYOUT.agentMinWidth + AGENT_GRAPH_LAYOUT.paddingX * 2,
                                widestLayer + AGENT_GRAPH_LAYOUT.paddingX * 2);

    let currentY = AGENT_GRAPH_LAYOUT.paddingY;
    const positionedNodes = [];
    const positionedByKey = new Map();
    orderedLayers.forEach((layer) => {
        let currentX = AGENT_GRAPH_LAYOUT.paddingX + Math.max(0, (widestLayer - layer.width) / 2);
        layer.nodes.forEach((node) => {
            const positioned = {
                ...node,
                x: Math.round(currentX),
                y: Math.round(currentY)
            };
            positionedNodes.push(positioned);
            positionedByKey.set(node.key, positioned);
            currentX += node.width + AGENT_GRAPH_LAYOUT.columnGap;
        });
        currentY += layer.height + AGENT_GRAPH_LAYOUT.rowGap;
    });

    const graphHeight = Math.max(AGENT_GRAPH_LAYOUT.agentHeight + AGENT_GRAPH_LAYOUT.paddingY * 2,
                                 currentY - AGENT_GRAPH_LAYOUT.rowGap + AGENT_GRAPH_LAYOUT.paddingY);

    const positionedEdges = edges
        .map((edge) => {
            const source = positionedByKey.get(edge.sourceKey);
            const target = positionedByKey.get(edge.targetKey);
            if (!source || !target) {
                return null;
            }
            return {
                ...edge,
                path: agentGraphEdgePath(source, target),
                active: edge.sourceKey === graph.selectedAgentKey || edge.targetKey === graph.selectedAgentKey,
                sourceName: source.name,
                targetName: target.name
            };
        })
        .filter(Boolean);

    return {
        width: Math.round(graphWidth),
        height: Math.round(graphHeight),
        nodes: positionedNodes,
        edges: positionedEdges,
        selectedAgentKey: graph.selectedAgentKey,
        agentCount: nodes.filter((node) => node.nodeKind === "agent").length,
        toolCount: nodes.filter((node) => node.nodeKind === "tool").length
    };
}

function computeAgentGraphDepths(nodes, workflowEdges) {
    const agentNodes = nodes.filter((node) => node.nodeKind === "agent");
    const indegree = new Map(agentNodes.map((node) => [node.key, 0]));
    const outgoing = new Map(agentNodes.map((node) => [node.key, []]));
    const depthByKey = new Map(agentNodes.map((node) => [node.key, 0]));

    workflowEdges.forEach((edge) => {
        if (!indegree.has(edge.sourceKey) || !indegree.has(edge.targetKey)) {
            return;
        }
        indegree.set(edge.targetKey, (indegree.get(edge.targetKey) || 0) + 1);
        outgoing.get(edge.sourceKey).push(edge.targetKey);
    });

    let queue = agentNodes
        .filter((node) => (indegree.get(node.key) || 0) === 0)
        .sort((left, right) => left.name.localeCompare(right.name))
        .map((node) => node.key);

    if (queue.length === 0) {
        queue = agentNodes.map((node) => node.key);
    }

    const remainingIndegree = new Map(indegree);
    while (queue.length > 0) {
        const currentKey = queue.shift();
        const nextDepth = depthByKey.get(currentKey) ?? 0;
        (outgoing.get(currentKey) || []).forEach((targetKey) => {
            depthByKey.set(targetKey, Math.max(depthByKey.get(targetKey) ?? 0, nextDepth + 1));
            remainingIndegree.set(targetKey, (remainingIndegree.get(targetKey) || 1) - 1);
            if ((remainingIndegree.get(targetKey) || 0) === 0) {
                queue.push(targetKey);
            }
        });
    }

    agentNodes.forEach((node) => {
        if (!depthByKey.has(node.key)) {
            depthByKey.set(node.key, 0);
        }
    });

    return depthByKey;
}

function agentGraphNodeBarycenter(node, incomingByKey, orderHints) {
    const incoming = (incomingByKey.get(node.key) || [])
        .map((parentKey) => orderHints.get(parentKey))
        .filter((value) => Number.isFinite(value));
    if (incoming.length === 0) {
        return Number.POSITIVE_INFINITY;
    }
    return incoming.reduce((sum, value) => sum + value, 0) / incoming.length;
}

function agentGraphEdgePath(source, target) {
    const startX = source.x + source.width / 2;
    const startY = source.y + source.height;
    const endX = target.x + target.width / 2;
    const endY = target.y;
    const distanceY = Math.max(40, endY - startY);
    const controlY = Math.max(34, distanceY * 0.42);
    return `M ${startX} ${startY} C ${startX} ${startY + controlY} ${endX} ${endY - controlY} ${endX} ${endY}`;
}

function renderAgentGraph(graph) {
    return `
        <div class="agent-graph-shell">
            <div class="agent-graph-meta">
                <span class="agent-graph-pill">${graph.agentCount} agent${graph.agentCount === 1 ? "" : "s"}</span>
                <span class="agent-graph-pill">${graph.toolCount} tool node${graph.toolCount === 1 ? "" : "s"}</span>
            </div>
            <div class="agent-graph-scroll">
                <div class="agent-graph-stage" style="width:${graph.width}px; height:${graph.height}px;">
                    <svg
                        class="agent-graph-canvas"
                        viewBox="0 0 ${graph.width} ${graph.height}"
                        width="${graph.width}"
                        height="${graph.height}"
                        aria-hidden="true">
                        <defs>
                            <marker id="agent-graph-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
                                <path d="M 0 0 L 10 5 L 0 10 z" fill="context-stroke"></path>
                            </marker>
                        </defs>
                        ${graph.edges.map(renderAgentGraphEdge).join("")}
                    </svg>
                    ${graph.nodes.map(renderAgentGraphNode).join("")}
                </div>
            </div>
            <div class="agent-graph-legend">
                <span class="agent-graph-legend-item is-agent">Agent</span>
                <span class="agent-graph-legend-item is-service">Service</span>
                <span class="agent-graph-legend-item is-router">Conditional</span>
                <span class="agent-graph-legend-item is-guardrail">Guardrail</span>
                <span class="agent-graph-legend-item is-tool">Tool</span>
            </div>
        </div>
    `;
}

function renderAgentGraphEdge(edge) {
    return `
        <path
            class="agent-graph-edge is-${escapeHtml(edge.kind)}${edge.active ? " is-active" : ""}"
            d="${edge.path}"
            marker-end="url(#agent-graph-arrow)">
            <title>${escapeHtml(agentGraphEdgeTitle(edge))}</title>
        </path>
    `;
}

function renderAgentGraphNode(node) {
    const classes = [
        "agent-graph-node",
        `is-${node.nodeKind}`,
        `is-${node.category || "agent"}`
    ];
    if (node.active) {
        classes.push("is-active");
    }
    if (!node.selectable) {
        classes.push("is-static");
    }

    const content = `
        <span class="agent-graph-node-topline">
            <span class="agent-graph-node-icon" aria-hidden="true">${agentGraphNodeIcon(node)}</span>
            <span class="agent-graph-node-type">${escapeHtml(agentGraphNodeType(node))}</span>
            <span class="agent-graph-node-spinner" aria-hidden="true"></span>
        </span>
        <span class="agent-graph-node-label">${escapeHtml(node.name)}</span>
        <span class="agent-graph-node-meta">${escapeHtml(node.meta)}</span>
    `;
    const title = escapeHtml(agentGraphNodeTitle(node));
    const style = `left:${node.x}px; top:${node.y}px; width:${node.width}px; height:${node.height}px;`;

    if (node.selectable) {
        return `
            <button
                type="button"
                class="${classes.join(" ")}"
                data-agent="${escapeHtml(node.name)}"
                data-node-key="${escapeHtml(node.key)}"
                data-node-kind="${escapeHtml(node.nodeKind)}"
                data-node-category="${escapeHtml(node.category || "agent")}"
                style="${style}"
                title="${title}">
                ${content}
            </button>
        `;
    }

    return `
        <div
            class="${classes.join(" ")}"
            data-node-key="${escapeHtml(node.key)}"
            data-node-kind="${escapeHtml(node.nodeKind)}"
            data-node-category="${escapeHtml(node.category || "agent")}"
            style="${style}"
            title="${title}">
            ${content}
        </div>
    `;
}

function agentGraphNodeIcon(node) {
    if (node.nodeKind === "tool") {
        if (node.category === "guardrail") {
            return "🛡️";
        }
        if (node.category === "mcp-client") {
            return "🌐";
        }
        if (node.category === "tool-provider") {
            return "🔌";
        }
        return "🛠️";
    }
    if (node.category === "router") {
        return "🧭";
    }
    if (node.category === "service") {
        return "✨";
    }
    return "🤖";
}

function agentGraphNodeType(node) {
    if (node.nodeKind === "tool") {
        if (node.category === "guardrail") {
            return "Guardrail";
        }
        if (node.category === "mcp-client") {
            return "MCP";
        }
        if (node.category === "tool-provider") {
            return "Provider";
        }
        return "Tool";
    }
    if (node.category === "router") {
        return "Router";
    }
    if (node.category === "service") {
        return "Service";
    }
    if (node.category === "workflow") {
        return "Flow";
    }
    return "Agent";
}

function agentGraphNodeTitle(node) {
    const detail = node.detail ? `\n${node.detail}` : "";
    return `${node.name}\n${node.meta}${detail}`;
}

function agentGraphEdgeTitle(edge) {
    const relation = edge.kind === "guardrail"
        ? `${edge.sourceName} is protected by ${edge.targetName}`
        : edge.kind === "tool"
            ? `${edge.sourceName} uses ${edge.targetName}`
            : edge.kind === "conditional"
            ? `${edge.sourceName} conditionally routes to ${edge.targetName}`
            : `${edge.sourceName} invokes ${edge.targetName}`;
    const label = edge.label ? `\nVia: ${edge.label}` : "";
    const detail = edge.detail ? `\n${edge.detail}` : "";
    return `${relation}${label}${detail}`;
}

function renderWorkspace() {
    workspaceTitle.textContent = state.selectedAgent ? state.selectedAgent.name : "Select an agent";
    methodSelect.innerHTML = "";

    if (!state.selectedAgent || state.selectedAgent.methods.length === 0) {
        conversationTitle.textContent = "Select an agent method";
        composerTitle.textContent = "Message composer";
        composerNote.textContent = "Select an agent and method to start a browser conversation.";
        invokeForm.innerHTML = `<div class="empty-state">No invocable methods are available for the selected agent.</div>`;
        renderStatus();
        renderResult();
        renderInspector();
        renderChatHistory();
        syncConversationControls(null);
        return;
    }

    methodSelect.innerHTML = state.selectedAgent.methods.map((method) => `
        <option value="${escapeHtml(method.id)}">${escapeHtml(method.name)}</option>
    `).join("");

    const selectedMethodId = state.selectedMethod?.id || state.selectedAgent.methods[0].id;
    state.selectedMethod = state.selectedAgent.methods.find((method) => method.id === selectedMethodId) || state.selectedAgent.methods[0];
    methodSelect.value = state.selectedMethod.id;

    const conversation = ensureConversation();
    conversationTitle.textContent = state.selectedMethod.chatLike
        ? `${state.selectedMethod.name} conversation`
        : `${state.selectedMethod.name} invocation stream`;
    composerTitle.textContent = state.selectedMethod.chatLike ? "Message composer" : "Invocation payload";

    const noteParts = [];
    if (state.selectedAgent.description) {
        noteParts.push(state.selectedAgent.description);
    }
    if (state.selectedMethod.chatLike) {
        noteParts.push("Press Enter to send, Shift+Enter for a newline.");
    } else {
        noteParts.push("Use scalar fields, enum pick lists, or JSON payloads. Press Enter in single-line fields to run, or Ctrl+Enter / Cmd+Enter from multiline editors.");
    }
    composerNote.textContent = noteParts.join(" ");

    renderInvocationForm();
    renderStatus();
    renderResult();
    renderInspector();
    renderChatHistory();
    syncConversationControls(conversation);
}

function renderInvocationForm() {
    if (!state.selectedMethod) {
        invokeForm.innerHTML = `<div class="empty-state">Select a method.</div>`;
        return;
    }

    const parameters = state.selectedMethod.parameters || [];
    const visibleParameters = parameters.filter((parameter) => !parameter.memoryId);
    const stateParameters = state.selectedMethod.stateParameters || [];

    if (visibleParameters.length === 0 && stateParameters.length === 0) {
        invokeForm.innerHTML = `<div class="empty-state">This method has no explicit input parameters. Use the action button to invoke it.</div>`;
        return;
    }

    if (state.selectedMethod.chatLike) {
        const promptParameter = visibleParameters[0];
        invokeForm.innerHTML = `
            <div class="field field-chat-input">
                <label for="field-${escapeHtml(promptParameter.name)}">${escapeHtml(promptParameter.name)}</label>
                <textarea
                    id="field-${escapeHtml(promptParameter.name)}"
                    class="composer-input"
                    data-param="${escapeHtml(promptParameter.name)}"
                    data-chat-input="true"
                    rows="4"
                    placeholder="Message ${escapeHtml(state.selectedAgent?.name || "agent")}..."></textarea>
            </div>
            ${renderAdvancedFields([], stateParameters, true)}
        `;
        return;
    }

    const argumentFields = visibleParameters.map((parameter) => inputFieldMarkup(parameter, "param")).join("");
    invokeForm.innerHTML = `
        <div class="field-note structured-note">
            Use direct scalar values or JSON payloads. Each invocation will appear in the transcript as request and response bubbles.
        </div>
        ${argumentFields}
        ${renderAdvancedFields([], stateParameters, false)}
    `;
}

function renderResult() {
    const conversation = currentConversation();
    const inspection = activeInspection(conversation);
    const result = state.tracking.enabled
        ? inspection?.result
        : conversation?.lastResponse?.result;
    const runCount = conversation?.runCount || 0;
    const lastEntry = conversation?.entries?.[conversation.entries.length - 1] || null;
    const trackedEvents = inspection?.events || [];
    const trackedInvocations = inspection?.invocations || [];

    if (state.tracking.enabled) {
        if (conversation?.pending) {
            resultSummary.textContent = "Waiting for response • tracking app-wide";
        } else if (trackedEvents.length > 0) {
            resultSummary.textContent = `Tracking ${trackedEvents.length} event${trackedEvents.length === 1 ? "" : "s"}`;
        } else if (trackedInvocations.length > 0) {
            resultSummary.textContent = `Tracking ${trackedInvocations.length} invocation${trackedInvocations.length === 1 ? "" : "s"}`;
        } else {
            resultSummary.textContent = "Tracking app-wide events";
        }
    } else {
        if (conversation?.pending) {
            resultSummary.textContent = "Waiting for response...";
        } else if (lastEntry?.meta === "Request failed" && runCount === 0) {
            resultSummary.textContent = "Last request failed";
        } else if (!state.selectedMethod || runCount === 0) {
            resultSummary.textContent = state.selectedMethod?.chatLike ? "No conversation yet" : "No invocations yet";
        } else if (state.selectedMethod.chatLike) {
            resultSummary.textContent = `${runCount} turn${runCount === 1 ? "" : "s"}`;
        } else {
            resultSummary.textContent = `${runCount} invocation${runCount === 1 ? "" : "s"}`;
        }
    }

    resultView.textContent = result !== undefined && result !== null
        ? pretty(result)
        : state.tracking.enabled
            ? "Tracking all agent events. The latest normalized result will appear here."
            : "Run a method to inspect the latest raw result.";
}

function activeInspection(conversation = currentConversation()) {
    if (state.tracking.enabled) {
        return state.tracking.inspection;
    }
    return conversation?.inspection || null;
}

function activeProgressEvents(conversation = currentConversation()) {
    const inspection = activeInspection(conversation);
    if (state.tracking.enabled) {
        return inspection?.events || [];
    }
    if (conversation?.pending) {
        return conversation.progressEvents || [];
    }
    return conversation?.inspection?.events || conversation?.progressEvents || [];
}

function renderConversationProgress(conversation = currentConversation()) {
    if (!state.selectedMethod && !state.tracking.enabled) {
        conversationProgress.innerHTML = `<div class="run-progress-empty">Select an agent method to see live progress.</div>`;
        return;
    }

    const monitoringAllEvents = state.tracking.enabled;
    const events = activeProgressEvents(conversation);
    const steps = buildRunProgressSteps(events);

    if (!steps.length) {
        const text = monitoringAllEvents
            ? "Tracking all agent events. Waiting for activity..."
            : conversation?.pending
                ? "Waiting for first event..."
                : conversation?.runCount
                    ? "No agent or tool events captured."
                    : "Progress appears here during agentic runs.";
        const showPending = monitoringAllEvents || conversation?.pending;
        const pendingClass = showPending ? " is-pending" : "";
        const pendingDot = showPending
            ? `<span class="run-progress-empty-dot" aria-hidden="true"></span>`
            : "";
        conversationProgress.innerHTML = `
            <div class="run-progress-empty${pendingClass}">
                ${pendingDot}
                <span>${escapeHtml(text)}</span>
            </div>
        `;
        return;
    }

    const rows = buildRunProgressRows(steps);
    conversationProgress.innerHTML = `
        <div class="run-progress-shell" aria-label="Agentic run progress">
            <div class="run-progress-track">
                ${rows.map((row, rowIndex) => `
                    ${rowIndex > 0 ? renderRunProgressTurn(rows[rowIndex - 1], row) : ""}
                    ${renderRunProgressRow(row)}
                `).join("")}
            </div>
        </div>
    `;
}

function syncAgentGraphActivity(conversation = currentConversation()) {
    const graphNodes = agentList?.querySelectorAll(".agent-graph-node[data-node-key]");
    if (!graphNodes?.length) {
        return;
    }

    const selectedAgentKey = state.selectedAgent ? agentGraphAgentKey(state.selectedAgent.name) : "";
    const runningNodeKeys = buildAgentGraphRunningNodeKeys(activeProgressEvents(conversation));

    graphNodes.forEach((node) => {
        const nodeKey = node.dataset.nodeKey || "";
        const running = runningNodeKeys.has(nodeKey);
        node.classList.toggle("is-active", nodeKey === selectedAgentKey);
        node.classList.toggle("is-running", running);
        node.setAttribute("aria-busy", running ? "true" : "false");
    });
}

const RUN_PROGRESS_ROW_SIZE = 3;
const RUN_PROGRESS_STEP_COLUMNS = [1, 3, 5];
const RUN_PROGRESS_CONNECTOR_COLUMNS = [2, 4];

function buildRunProgressSteps(events) {
    const steps = [];
    (events || []).forEach((event, index) => {
        if (!event || typeof event !== "object") {
            return;
        }

        switch (event.type) {
        case "before-agent":
            steps.push(createRunProgressStep(event, index, "agent", "running"));
            break;
        case "conditional-check":
            if (!findLatestOpenRunProgressStep(steps, event, "router")) {
                steps.push(createRunProgressStep(event, index, "router", "running"));
            }
            break;
        case "after-agent":
            closeRunProgressStep(steps, event, index, "agent", "complete");
            break;
        case "agent-error":
            closeRunProgressStep(steps, event, index, "agent", "failed");
            break;
        case "conditional-route":
            closeRunProgressStep(steps, event, index, "router", event.status === "no-match" ? "failed" : "complete");
            break;
        case "before-tool":
            steps.push(createRunProgressStep(event, index, "tool", "running"));
            break;
        case "after-tool":
            closeRunProgressStep(steps, event, index, "tool", event.failed ? "failed" : "complete");
            break;
        default:
            break;
        }
    });
    return steps;
}

function buildRunProgressRows(steps, rowSize = RUN_PROGRESS_ROW_SIZE) {
    const rows = [];
    for (let start = 0; start < steps.length; start += rowSize) {
        rows.push(createRunProgressRow(steps.slice(start, start + rowSize), rows.length));
    }
    return rows;
}

function buildAgentGraphRunningNodeKeys(events) {
    const runningNodeKeys = new Set();
    buildRunProgressSteps(events).forEach((step) => {
        if (step.status !== "running") {
            return;
        }

        const nodeKey = agentGraphRunningNodeKey(step);
        if (nodeKey) {
            runningNodeKeys.add(nodeKey);
        }
    });
    return runningNodeKeys;
}

function agentGraphRunningNodeKey(step) {
    if (!step || step.kind === "tool" || !step.name) {
        return "";
    }
    return agentGraphAgentKey(step.name);
}

function createRunProgressRow(logicalSteps, rowIndex) {
    const reverse = rowIndex % 2 === 1;
    const items = [];

    logicalSteps.forEach((step, logicalIndex) => {
        const stepColumnIndex = reverse
            ? RUN_PROGRESS_ROW_SIZE - 1 - logicalIndex
            : logicalIndex;
        items.push({
            type: "step",
            column: RUN_PROGRESS_STEP_COLUMNS[stepColumnIndex],
            step
        });

        if (logicalIndex >= logicalSteps.length - 1) {
            return;
        }
        const connectorColumnIndex = reverse
            ? RUN_PROGRESS_ROW_SIZE - 2 - logicalIndex
            : logicalIndex;
        items.push({
            type: "connector",
            column: RUN_PROGRESS_CONNECTOR_COLUMNS[connectorColumnIndex],
            from: step,
            to: logicalSteps[logicalIndex + 1]
        });
    });

    items.sort((left, right) => left.column - right.column);
    return {
        reverse,
        logicalSteps,
        startStep: logicalSteps[0],
        endStep: logicalSteps[logicalSteps.length - 1],
        items
    };
}

function closeRunProgressStep(steps, event, index, kind, status) {
    const step = findLatestOpenRunProgressStep(steps, event, kind);
    if (step) {
        step.status = status;
        step.timestamp = event.timestamp || step.timestamp;
        return;
    }
    steps.push(createRunProgressStep(event, index, kind, status));
}

function findLatestOpenRunProgressStep(steps, event, kind) {
    for (let index = steps.length - 1; index >= 0; index--) {
        const step = steps[index];
        if (step.kind !== kind || step.status !== "running") {
            continue;
        }

        const matches = kind === "tool"
            ? runProgressToolMatches(step, event)
            : kind === "router"
                ? runProgressRouterMatches(step, event)
                : runProgressAgentMatches(step, event);
        if (matches) {
            return step;
        }
    }
    return null;
}

function runProgressAgentMatches(step, event) {
    if (step.agentId && event.agentId) {
        return step.agentId === event.agentId;
    }
    return step.name === (event.agent || event.agentId || "agent");
}

function runProgressToolMatches(step, event) {
    if (step.toolId && event.toolId) {
        return step.toolId === event.toolId;
    }
    if (step.name !== (event.tool || event.toolId || step.name)) {
        return false;
    }
    return !step.agent || !event.agent || step.agent === event.agent;
}

function runProgressRouterMatches(step, event) {
    const eventRouterKey = runProgressRouterKey(event);
    if (step.routerKey && eventRouterKey) {
        return step.routerKey === eventRouterKey;
    }
    if (step.agentType && event.agentType && step.agentType !== event.agentType) {
        return false;
    }
    return step.name === runProgressStepName(event, "router");
}

function createRunProgressStep(event, index, kind, status) {
    return {
        id: `${kind}-${index}-${event.timestamp || index}`,
        kind,
        status,
        name: runProgressStepName(event, kind),
        agent: event.agent || "",
        agentId: event.agentId || "",
        agentType: event.agentType || "",
        method: event.method || "",
        toolId: event.toolId || "",
        routerKey: kind === "router" ? runProgressRouterKey(event) : "",
        timestamp: event.timestamp || ""
    };
}

function runProgressStepName(event, kind) {
    if (kind === "tool") {
        return event.tool || event.toolId || "tool";
    }
    if (kind === "router") {
        return event.agent || shortTypeName(event.agentType) || "router";
    }
    return event.agent || event.agentId || "agent";
}

function runProgressRouterKey(event) {
    const method = event.method || event.agent || "";
    const agentType = event.agentType || "";
    if (!method && !agentType) {
        return "";
    }
    return `${agentType}::${method}`;
}

function shortTypeName(typeName) {
    const normalized = String(typeName || "").trim();
    if (!normalized) {
        return "";
    }
    const separator = normalized.lastIndexOf(".");
    return separator === -1 ? normalized : normalized.slice(separator + 1);
}

function renderRunProgressStep(step) {
    return `
        <div
            class="run-progress-step is-${escapeHtml(step.kind)} is-${escapeHtml(step.status)}"
            title="${escapeHtml(runProgressStepTitle(step))}">
            <span class="run-progress-marble" aria-hidden="true">${runProgressStepIcon(step)}</span>
            <span class="run-progress-label">${escapeHtml(step.name)}</span>
        </div>
    `;
}

function runProgressStepIcon(step) {
    if (step.kind === "tool") {
        return "🛠️";
    }
    if (step.kind === "router") {
        return "🧭";
    }
    return "🤖";
}

function renderRunProgressConnector(step, nextStep) {
    const status = runProgressConnectorStatus(step, nextStep);
    return `<span class="run-progress-connector is-${status}" aria-hidden="true"></span>`;
}

function runProgressConnectorStatus(step, nextStep) {
    return step.status === "failed" || nextStep?.status === "failed"
        ? "failed"
        : step.status === "running" || nextStep?.status === "running"
            ? "running"
            : "complete";
}

function renderRunProgressRow(row) {
    return `
        <div class="run-progress-row ${row.reverse ? "is-reverse" : "is-forward"}">
            ${row.items.map((item) => item.type === "step"
                ? renderRunProgressRowStep(item)
                : renderRunProgressRowConnector(item)
            ).join("")}
        </div>
    `;
}

function renderRunProgressRowStep(item) {
    return `
        <div class="run-progress-row-cell is-step" style="grid-column:${item.column}">
            ${renderRunProgressStep(item.step)}
        </div>
    `;
}

function renderRunProgressRowConnector(item) {
    return `
        <div class="run-progress-row-cell is-connector" style="grid-column:${item.column}">
            ${renderRunProgressConnector(item.from, item.to)}
        </div>
    `;
}

function renderRunProgressTurn(previousRow, nextRow) {
    const sideClass = previousRow.reverse ? "is-left" : "is-right";
    const status = runProgressConnectorStatus(previousRow.endStep, nextRow.startStep);
    const column = previousRow.reverse ? 1 : 5;
    return `
        <div class="run-progress-turn ${sideClass}">
            <span
                class="run-progress-turn-line is-${status}"
                style="grid-column:${column}"
                aria-hidden="true"></span>
        </div>
    `;
}

function runProgressStepTitle(step) {
    const kindLabel = step.kind === "tool"
        ? "Tool"
        : step.kind === "router"
            ? "Conditional agent"
            : "Agent";
    const statusLabel = step.status === "running" ? "Running" : step.status === "failed" ? "Failed" : "Completed";
    const owner = step.kind === "tool" && step.agent ? `\nAgent: ${step.agent}` : "";
    const method = step.kind === "router" && step.method ? `\nMethod: ${step.method}` : "";
    return `${kindLabel}: ${step.name}${owner}${method}\nStatus: ${statusLabel}`;
}

function renderInspector() {
    const conversation = currentConversation();
    const monitoringAllEvents = state.tracking.enabled;
    const inspection = activeInspection(conversation);
    renderConversationProgress(conversation);
    syncAgentGraphActivity(conversation);

    const hasInspectableInvocation = monitoringAllEvents
        || (Boolean(conversation) && (conversation.runCount > 0 || conversation.pending || inspection));

    if ((!state.selectedMethod && !monitoringAllEvents) || !hasInspectableInvocation) {
        stateStatus.textContent = "No scope";
        setInspectorPanelHtml(stateView,
                              `<div class="empty-state">Run a method to inspect the latest state snapshot.</div>`,
                              "state");
        setInspectorPanelHtml(traceView,
                              `<div class="empty-state">Run a method to inspect the latest invocation trace.</div>`,
                              "trace");
        setInspectorPanelHtml(eventView,
                              `<div class="empty-state">Run a method to inspect the latest event log.</div>`,
                              "event");
        traceCount.textContent = "0 invocations";
        eventCount.textContent = "0 events";
        return;
    }

    if (monitoringAllEvents && !inspection) {
        stateStatus.textContent = "Tracking app-wide";
        setInspectorPanelHtml(stateView,
                              `<div class="empty-state">Tracking all agent events. Waiting for the first scope snapshot.</div>`,
                              "state");
        setInspectorPanelHtml(traceView,
                              `<div class="empty-state">Tracking all agent events. Invocation trace will appear here.</div>`,
                              "trace");
        setInspectorPanelHtml(eventView,
                              `<div class="empty-state">Tracking all agent events. Event log will appear here.</div>`,
                              "event");
        traceCount.textContent = "0 invocations";
        eventCount.textContent = "0 events";
        return;
    }

    stateStatus.textContent = monitoringAllEvents
        ? inspection?.scopeAvailable ? "Tracking app-wide • scope available" : "Tracking app-wide"
        : inspection?.scopeAvailable ? "Scope available" : "No scope";
    setInspectorPanelHtml(stateView, inspection?.scopeAvailable
        ? renderStateSnapshot(inspection.state || {})
        : monitoringAllEvents
            ? `<div class="empty-state">The latest tracked invocation did not expose an agentic scope. Plain leaf agents and non-agentic methods will not populate state.</div>`
            : `<div class="empty-state">This invocation did not expose an agentic scope. Plain leaf agents and non-agentic methods will not populate state.</div>`,
        "state");

    const invocations = inspection?.invocations || [];
    const events = inspection?.events || [];
    traceCount.textContent = `${invocations.length} invocation${invocations.length === 1 ? "" : "s"}`;
    eventCount.textContent = `${events.length} event${events.length === 1 ? "" : "s"}`;
    setInspectorPanelHtml(traceView, renderTimeline(invocations, "invocation"), "trace");
    setInspectorPanelHtml(eventView, renderTimeline(events, "event"), "event");
}

function ensureTrackingTabVisible() {
    if (currentTabName() !== "events") {
        switchTab("events");
    }
}

function currentTabName() {
    return document.querySelector(".tab-button.is-active")?.dataset.tab || "state";
}

function renderTimeline(items, kind) {
    if (!items.length) {
        return `<div class="empty-state">No ${kind}s captured yet.</div>`;
    }

    return items.map((item) => kind === "event"
        ? renderEventTimelineCard(item)
        : renderInvocationTimelineCard(item)
    ).join("");
}

function renderInvocationTimelineCard(item) {
    const cardKey = timelineCardKey(item, "invocation");
    const title = `${escapeHtml(item.agentName || "agent")} • ${escapeHtml(item.agentId || "")}`;
    const meta = escapeHtml(item.agentType || "");
    const details = invocationDetailEntries(item);

    return `
        <article class="timeline-card" data-card-key="${escapeHtml(cardKey)}">
            <div class="timeline-title">
                <span>${title}</span>
            </div>
            <div class="timeline-meta">${meta}</div>
            ${renderEventDetails(details, cardKey)}
        </article>
    `;
}

function renderEventTimelineCard(item) {
    const cardKey = timelineCardKey(item, "event");
    const title = `${escapeHtml(item.type || "event")} • ${escapeHtml(item.agent || item.tool || "")}`;
    const meta = escapeHtml(item.timestamp || "");
    const details = eventDetailEntries(item);

    return `
        <article class="timeline-card" data-card-key="${escapeHtml(cardKey)}">
            <div class="timeline-title">
                <span>${title}</span>
            </div>
            <div class="timeline-meta">${meta}</div>
            ${renderEventDetails(details, cardKey)}
        </article>
    `;
}

function renderEventDetails(entries, ownerKey = "") {
    if (!entries.length) {
        return `<div class="empty-state">No additional event details.</div>`;
    }

    return `
        <ul class="event-detail-list">
            ${entries.map(([name, value]) => renderEventDetailItem(name, value, ownerKey)).join("")}
        </ul>
    `;
}

function eventDetailEntries(item) {
    return Object.entries(item || {})
        .filter(([name, value]) => name !== "type" && name !== "timestamp" && value !== undefined);
}

function invocationDetailEntries(item) {
    return Object.entries(item || {})
        .filter(([name, value]) => !["agentType", "agentName", "agentId"].includes(name) && value !== undefined);
}

function renderEventDetailItem(name, value, ownerKey = "") {
    return renderCompactDetailItem(name, value, ownerKey);
}

function renderCompactDetailItem(name, value, ownerKey = "") {
    const inlineValue = renderEventInlineValue(value);
    const detailKey = compactDetailKey(ownerKey, name);
    return `
        <li class="event-detail-item state-variable-item">
            ${inlineValue
                ? `
                    <div class="event-detail-line">
                        <span class="event-detail-name">${escapeHtml(name)}:</span>
                        <span class="event-detail-inline">${inlineValue}</span>
                    </div>
                `
                : `
                    <details class="event-detail-section" data-detail-key="${escapeHtml(detailKey)}">
                        <summary>
                            <span class="event-detail-name">${escapeHtml(name)}:</span>
                            <span class="event-detail-summary">${escapeHtml(stateValueSummary(value))}</span>
                        </summary>
                        <div class="event-detail-section-body">
                            ${renderStateValue(value)}
                        </div>
                    </details>
                `}
        </li>
    `;
}

function renderEventInlineValue(value) {
    if (value === undefined || value === null || value === "") {
        return `<span class="event-detail-empty">No value</span>`;
    }

    if (typeof value === "number" || typeof value === "boolean") {
        return escapeHtml(String(value));
    }

    if (typeof value === "string") {
        const normalized = String(value).replace(/\r\n?/g, "\n");
        if (!normalized.includes("\n") && normalized.length <= 88) {
            return isRenderableMarkdown(normalized)
                ? renderMarkdownInline(normalized)
                : escapeHtml(normalized);
        }
        return "";
    }

    const compact = JSON.stringify(value);
    if (compact && compact.length <= 88 && !compact.includes("\n")) {
        return `<code class="event-inline-code">${escapeHtml(compact)}</code>`;
    }
    return "";
}

function renderStateSnapshot(stateData) {
    const entries = Object.entries(stateData || {}).filter(([, value]) => value !== undefined);
    if (entries.length === 0) {
        return `<div class="empty-state">Scope is available, but no context variables were captured.</div>`;
    }

    return `
        <ul class="state-variable-list event-detail-list">
            ${entries.map(([name, value]) => renderStateVariable(name, value)).join("")}
        </ul>
    `;
}

function renderStateVariable(name, value) {
    return renderCompactDetailItem(name, value, "state");
}

function setInspectorPanelHtml(element, html, cacheKey) {
    if (!element) {
        return;
    }
    if (state.inspector.markup[cacheKey] === html) {
        return;
    }

    const openDetailKeys = captureOpenDetailKeys(element);
    const previousScrollTop = element.scrollTop;
    state.inspector.markup[cacheKey] = html;
    element.innerHTML = html;
    restoreOpenDetailKeys(element, openDetailKeys);
    if (previousScrollTop > 0) {
        element.scrollTop = Math.min(previousScrollTop, Math.max(0, element.scrollHeight - element.clientHeight));
    }
}

function captureOpenDetailKeys(container) {
    return new Set(Array.from(container.querySelectorAll("details[data-detail-key][open]"))
        .map((detail) => detail.dataset.detailKey)
        .filter(Boolean));
}

function restoreOpenDetailKeys(container, openDetailKeys) {
    if (!openDetailKeys || openDetailKeys.size === 0) {
        return;
    }
    container.querySelectorAll("details[data-detail-key]").forEach((detail) => {
        if (openDetailKeys.has(detail.dataset.detailKey)) {
            detail.open = true;
        }
    });
}

function compactDetailKey(ownerKey, name) {
    return ownerKey ? `${ownerKey}::${name}` : name;
}

function timelineCardKey(item, kind) {
    if (kind === "invocation") {
        return [
            "invocation",
            item?.agentId || "",
            item?.agentName || "",
            item?.agentType || ""
        ].join("::");
    }
    return [
        "event",
        item?.timestamp || "",
        item?.type || "",
        item?.agentId || "",
        item?.agent || "",
        item?.toolId || "",
        item?.tool || ""
    ].join("::");
}

function stateValueSummary(value) {
    if (typeof value === "string") {
        return `${value.length} chars:${isRenderableMarkdown(value) ? "markdown" : "text"}`;
    }
    if (Array.isArray(value)) {
        return `${value.length} item${value.length === 1 ? "" : "s"}:array`;
    }
    if (value && typeof value === "object") {
        const entryCount = Object.keys(value).length;
        return `${entryCount} entr${entryCount === 1 ? "y" : "ies"}:object`;
    }
    if (value === null || value === undefined || value === "") {
        return "empty";
    }
    return `${String(value)}:${stateValueKind(value)}`;
}

function stateValueKind(value) {
    if (value === null) {
        return "null";
    }
    if (Array.isArray(value)) {
        return "array";
    }
    return typeof value;
}

function renderStateValue(value) {
    if (value === undefined || value === null || value === "") {
        return `<div class="state-empty-block">No value</div>`;
    }
    if (typeof value === "string") {
        return renderStateStringValue(value);
    }
    if (typeof value === "number" || typeof value === "boolean") {
        return `<pre class="state-text-block">${escapeHtml(String(value))}</pre>`;
    }
    if (Array.isArray(value)) {
        if (value.length === 0) {
            return `<div class="state-empty-block">No items</div>`;
        }
        return renderStateNestedEntries(value.map((item, index) => ({
            name: `[${index}]`,
            value: item
        })));
    }
    if (typeof value === "object") {
        const entries = Object.entries(value).filter(([, entryValue]) => entryValue !== undefined);
        if (entries.length === 0) {
            return `<div class="state-empty-block">No entries</div>`;
        }
        return renderStateNestedEntries(entries.map(([name, entryValue]) => ({
            name,
            value: entryValue
        })));
    }
    return `<pre class="state-text-block">${escapeHtml(pretty(value))}</pre>`;
}

function renderStateNestedEntries(entries) {
    return `
        <ul class="state-nested-list">
            ${entries.map((entry) => `
                <li class="state-nested-item">
                    <div class="state-nested-shell">
                        <div class="state-nested-name">${escapeHtml(entry.name)}</div>
                        <div class="state-nested-value">${renderStateValue(entry.value)}</div>
                    </div>
                </li>
            `).join("")}
        </ul>
    `;
}

function renderStateStringValue(value) {
    const markdown = renderMarkdownIfPresent(value);
    if (markdown) {
        return `<div class="chat-markdown state-markdown-value">${markdown}</div>`;
    }
    return `<pre class="state-text-block">${escapeHtml(String(value))}</pre>`;
}

function renderChatHistory() {
    if (!state.selectedAgent) {
        chatHistory.innerHTML = `<div class="empty-state">Select an agent to start browsing or sending turns.</div>`;
        return;
    }
    if (!state.selectedMethod) {
        chatHistory.innerHTML = `<div class="empty-state">Select a method to build a conversation transcript.</div>`;
        return;
    }

    const conversation = ensureConversation();
    chatHistory.setAttribute("aria-busy", conversation.pending ? "true" : "false");
    if (conversation.entries.length === 0) {
        chatHistory.innerHTML = "";
        return;
    }

    chatHistory.innerHTML = conversation.entries.map(renderChatEntry).join("");
    requestAnimationFrame(() => {
        chatHistory.scrollTop = chatHistory.scrollHeight;
    });
}

function renderChatEntry(entry) {
    const pendingClass = entry.pending ? " is-pending" : "";
    return `
        <article class="chat-row chat-row-${entry.role}${pendingClass}">
            <div class="chat-avatar chat-avatar-${entry.role === "user" ? "user" : "assistant"}">
                ${entry.role === "user" ? "You" : "AI"}
            </div>
            <div class="chat-bubble chat-bubble-${entry.role}${pendingClass}">
                <div class="chat-bubble-head">
                    <span class="chat-speaker">${escapeHtml(entry.speaker)}</span>
                    <span class="chat-meta">${escapeHtml(entry.meta || "")}</span>
                </div>
                ${entry.payload.label ? `<div class="chat-bubble-label">${escapeHtml(entry.payload.label)}</div>` : ""}
                ${renderBubbleBody(entry.payload)}
            </div>
        </article>
    `;
}

function renderBubbleBody(payload) {
    if (payload.kind === "pending") {
        return renderPendingBubble(payload.text);
    }
    if (payload.kind === "variables") {
        return renderVariableList(payload.entries);
    }
    if (payload.kind === "list") {
        return renderValueList(payload.items);
    }
    if (payload.kind === "json") {
        return `<pre class="chat-bubble-code">${escapeHtml(payload.text)}</pre>`;
    }
    if (payload.kind === "empty") {
        return `<div class="chat-bubble-empty">${escapeHtml(payload.text)}</div>`;
    }
    return `<div class="chat-markdown">${renderMarkdown(payload.text)}</div>`;
}

function renderPendingBubble(text) {
    return `
        <div class="chat-pending">
            <span class="chat-pending-dots" aria-hidden="true">
                <span class="chat-pending-dot"></span>
                <span class="chat-pending-dot"></span>
                <span class="chat-pending-dot"></span>
            </span>
            <span class="chat-pending-text">${escapeHtml(text || "Waiting for response...")}</span>
        </div>
    `;
}

function bubblePayload(value) {
    if (value === undefined || value === null || value === "") {
        return {kind: "empty", text: "No payload"};
    }
    if (typeof value === "string") {
        return {kind: "markdown", text: value};
    }
    if (typeof value === "number" || typeof value === "boolean") {
        return {kind: "markdown", text: String(value)};
    }
    if (Array.isArray(value)) {
        if (value.length === 0) {
            return {kind: "empty", text: "No items"};
        }
        return {
            kind: "list",
            items: value.map((item) => bubblePayload(item))
        };
    }
    if (typeof value === "object") {
        const entries = Object.entries(value).filter(([, entryValue]) => entryValue !== undefined);
        if (entries.length === 0) {
            return {kind: "empty", text: "No variables"};
        }
        return {
            kind: "variables",
            entries: entries.map(([name, entryValue]) => ({
                name,
                value: bubblePayload(entryValue)
            }))
        };
    }
    return {kind: "json", text: pretty(value)};
}

function renderVariableList(entries) {
    return `
        <ul class="chat-variable-list">
            ${entries.map((entry) => `
                <li class="chat-variable-item">
                    <div class="chat-variable-name">${escapeHtml(entry.name)}</div>
                    <div class="chat-variable-value">${renderBubbleBody(entry.value)}</div>
                </li>
            `).join("")}
        </ul>
    `;
}

function renderValueList(items) {
    return `
        <ul class="chat-value-list">
            ${items.map((item) => `
                <li class="chat-value-item">${renderBubbleBody(item)}</li>
            `).join("")}
        </ul>
    `;
}

function renderMarkdown(text) {
    const normalized = String(text ?? "").replace(/\r\n?/g, "\n");
    const blocks = markdownBlocks(normalized);
    if (blocks.length === 0) {
        return "";
    }
    return blocks.map(renderMarkdownBlock).join("");
}

function renderMarkdownIfPresent(text) {
    const normalized = String(text ?? "").replace(/\r\n?/g, "\n");
    if (!isRenderableMarkdown(normalized)) {
        return "";
    }
    return renderMarkdown(normalized);
}

function isRenderableMarkdown(text) {
    const normalized = String(text ?? "").replace(/\r\n?/g, "\n");
    if (!normalized.trim()) {
        return false;
    }

    const blocks = markdownBlocks(normalized);
    if (blocks.some((block) => block.type !== "paragraph")) {
        return true;
    }

    return blocks.some((block) => block.type === "paragraph" && hasInlineMarkdownSyntax(block.text));
}

function hasInlineMarkdownSyntax(text) {
    return /`[^`\n]+`/.test(text)
        || /\*\*[^*\n]+\*\*/.test(text)
        || /__[^_\n]+__/.test(text)
        || /~~[^~\n]+~~/.test(text)
        || /\[[^\]\n]+\]\(([^)\n]+)\)/.test(text);
}

function markdownBlocks(source) {
    const lines = source.split("\n");
    const blocks = [];
    let index = 0;

    while (index < lines.length) {
        const line = lines[index];
        const normalizedLine = normalizeMarkdownBlockLine(line);
        if (!line.trim()) {
            index++;
            continue;
        }

        const codeFence = normalizedLine.match(/^```([\w#+.-]+)?(?:\s+.*)?$/);
        if (codeFence) {
            const codeLines = [];
            index++;
            while (index < lines.length && !/^```\s*$/.test(normalizeMarkdownBlockLine(lines[index]))) {
                codeLines.push(lines[index]);
                index++;
            }
            if (index < lines.length) {
                index++;
            }
            blocks.push({
                type: "code",
                lang: codeFence[1] || "",
                text: codeLines.join("\n")
            });
            continue;
        }

        const heading = normalizedLine.match(/^(#{1,6})\s+(.*)$/);
        if (heading) {
            blocks.push({
                type: "heading",
                level: heading[1].length,
                text: heading[2]
            });
            index++;
            continue;
        }

        if (isMarkdownTableStart(lines, index)) {
            const headerCells = parseMarkdownTableRow(lines[index]);
            const alignments = parseMarkdownTableAlignments(lines[index + 1], headerCells.length);
            const rows = [];
            index += 2;
            while (index < lines.length && isMarkdownTableRow(lines[index], headerCells.length)) {
                rows.push(parseMarkdownTableRow(lines[index], headerCells.length));
                index++;
            }
            blocks.push({
                type: "table",
                header: headerCells,
                alignments,
                rows
            });
            continue;
        }

        if (/^[-*+]\s+/.test(normalizedLine)) {
            const items = [];
            while (index < lines.length && /^[-*+]\s+/.test(normalizeMarkdownBlockLine(lines[index]))) {
                items.push(normalizeMarkdownBlockLine(lines[index]).replace(/^[-*+]\s+/, ""));
                index++;
            }
            blocks.push({type: "ul", items});
            continue;
        }

        if (/^\d+\.\s+/.test(normalizedLine)) {
            const items = [];
            while (index < lines.length && /^\d+\.\s+/.test(normalizeMarkdownBlockLine(lines[index]))) {
                items.push(normalizeMarkdownBlockLine(lines[index]).replace(/^\d+\.\s+/, ""));
                index++;
            }
            blocks.push({type: "ol", items});
            continue;
        }

        if (/^>\s?/.test(normalizedLine)) {
            const quoteLines = [];
            while (index < lines.length && /^>\s?/.test(normalizeMarkdownBlockLine(lines[index]))) {
                quoteLines.push(normalizeMarkdownBlockLine(lines[index]).replace(/^>\s?/, ""));
                index++;
            }
            blocks.push({
                type: "quote",
                text: quoteLines.join("\n")
            });
            continue;
        }

        const paragraphLines = [];
        while (index < lines.length
            && lines[index].trim()
            && !/^```/.test(normalizeMarkdownBlockLine(lines[index]))
            && !/^(#{1,6})\s+/.test(normalizeMarkdownBlockLine(lines[index]))
            && !isMarkdownTableStart(lines, index)
            && !/^[-*+]\s+/.test(normalizeMarkdownBlockLine(lines[index]))
            && !/^\d+\.\s+/.test(normalizeMarkdownBlockLine(lines[index]))
            && !/^>\s?/.test(normalizeMarkdownBlockLine(lines[index]))) {
            paragraphLines.push(lines[index]);
            index++;
        }
        blocks.push({
            type: "paragraph",
            text: paragraphLines.join("\n")
        });
    }

    return blocks;
}

function normalizeMarkdownBlockLine(line) {
    return String(line ?? "").trimStart();
}

function renderMarkdownBlock(block) {
    if (block.type === "code") {
        return renderMarkdownCodeBlock(block.text, block.lang);
    }
    if (block.type === "heading") {
        return `<h4 class="chat-markdown-heading chat-markdown-heading-${block.level}">${renderMarkdownInline(block.text)}</h4>`;
    }
    if (block.type === "table") {
        return renderMarkdownTable(block);
    }
    if (block.type === "ul") {
        return `<ul>${block.items.map((item) => `<li>${renderMarkdownInline(item)}</li>`).join("")}</ul>`;
    }
    if (block.type === "ol") {
        return `<ol>${block.items.map((item) => `<li>${renderMarkdownInline(item)}</li>`).join("")}</ol>`;
    }
    if (block.type === "quote") {
        return `<blockquote>${renderMarkdownInline(block.text).replaceAll("\n", "<br>")}</blockquote>`;
    }
    return `<p>${renderMarkdownInline(block.text).replaceAll("\n", "<br>")}</p>`;
}

function renderMarkdownCodeBlock(text, lang) {
    const normalizedLang = normalizeCodeLanguage(lang);
    const displayLang = normalizedLang || sanitizeCodeLanguageLabel(lang);
    const langMarker = displayLang ? ` data-lang="${escapeHtml(displayLang)}"` : "";
    const highlighted = highlightMarkdownCode(String(text ?? ""), normalizedLang);
    return `<pre class="chat-bubble-code chat-markdown-pre"${langMarker}><code>${highlighted}</code></pre>`;
}

function sanitizeCodeLanguageLabel(lang) {
    return String(lang || "")
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9_+.-]/g, "");
}

function normalizeCodeLanguage(lang) {
    const label = sanitizeCodeLanguageLabel(lang);
    if (!label) {
        return "";
    }
    return CODE_LANGUAGE_ALIASES[label] || label;
}

function highlightMarkdownCode(source, lang) {
    if (lang === "java") {
        return highlightJavaCode(source);
    }
    if (lang === "json") {
        return highlightJsonCode(source);
    }
    if (lang === "yaml") {
        return highlightYamlCode(source);
    }
    if (lang === "xml") {
        return highlightXmlCode(source);
    }
    if (lang === "bash") {
        return highlightBashCode(source);
    }
    return escapeHtml(source);
}

function highlightJavaCode(source) {
    const fragments = [];
    let index = 0;
    let plainStart = 0;

    while (index < source.length) {
        if (source.startsWith("//", index)) {
            const end = lineEnd(source, index);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "comment");
            index = end;
            continue;
        }
        if (source.startsWith("/*", index)) {
            const commentEnd = source.indexOf("*/", index + 2);
            const end = commentEnd === -1 ? source.length : commentEnd + 2;
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "comment");
            index = end;
            continue;
        }
        if (source.startsWith('"""', index)) {
            const end = consumeQuotedLiteral(source, index, '"""', false);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            continue;
        }

        const char = source[index];
        if (char === '"') {
            const end = consumeQuotedLiteral(source, index, '"', true);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            continue;
        }
        if (char === "'") {
            const end = consumeQuotedLiteral(source, index, "'", true);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            continue;
        }
        if (char === "@") {
            const end = consumeJavaAnnotation(source, index);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "annotation");
            index = end;
            continue;
        }

        const numberEnd = consumeJavaNumber(source, index);
        if (numberEnd > index) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, numberEnd, "number");
            index = numberEnd;
            continue;
        }

        if (isIdentifierStart(char)) {
            const end = consumeIdentifier(source, index);
            const token = source.slice(index, end);
            let type = "";
            if (token === "true" || token === "false") {
                type = "boolean";
            } else if (token === "null") {
                type = "null";
            } else if (JAVA_KEYWORDS.has(token)) {
                type = "keyword";
            } else if (JAVA_PRIMITIVE_TYPES.has(token) || /^[A-Z]/.test(token)) {
                type = "type";
            }
            if (type) {
                plainStart = emitCodeToken(fragments, source, plainStart, index, end, type);
            }
            index = end;
            continue;
        }

        const operator = matchAnyPrefix(source, index, JAVA_MULTI_CHAR_OPERATORS);
        if (operator) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + operator.length, "operator");
            index += operator.length;
            continue;
        }
        if ("=+-*/%&|!<>?:~".includes(char)) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 1, "operator");
            index += 1;
            continue;
        }
        if ("(){}[];,.".includes(char)) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 1, "punctuation");
            index += 1;
            continue;
        }

        index++;
    }

    flushCodeTail(fragments, source, plainStart);
    return fragments.join("");
}

function highlightJsonCode(source) {
    const fragments = [];
    let index = 0;
    let plainStart = 0;

    while (index < source.length) {
        const char = source[index];
        if (char === '"') {
            const end = consumeQuotedLiteral(source, index, '"', true);
            const next = nextNonWhitespaceIndex(source, end);
            const type = source[next] === ":" ? "property" : "string";
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, type);
            index = end;
            continue;
        }

        const numberEnd = consumeJsonNumber(source, index);
        if (numberEnd > index) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, numberEnd, "number");
            index = numberEnd;
            continue;
        }

        if (source.startsWith("true", index) && isLiteralBoundary(source[index - 1]) && isLiteralBoundary(source[index + 4])) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 4, "boolean");
            index += 4;
            continue;
        }
        if (source.startsWith("false", index) && isLiteralBoundary(source[index - 1]) && isLiteralBoundary(source[index + 5])) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 5, "boolean");
            index += 5;
            continue;
        }
        if (source.startsWith("null", index) && isLiteralBoundary(source[index - 1]) && isLiteralBoundary(source[index + 4])) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 4, "null");
            index += 4;
            continue;
        }
        if ("{}[]:,".includes(char)) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 1, "punctuation");
            index += 1;
            continue;
        }

        index++;
    }

    flushCodeTail(fragments, source, plainStart);
    return fragments.join("");
}

function highlightYamlCode(source) {
    const state = {blockScalarIndent: null};
    return source.split("\n").map((line) => highlightYamlLine(line, state)).join("\n");
}

function highlightYamlLine(line, state) {
    const indentation = line.match(/^\s*/) ? line.match(/^\s*/)[0].length : 0;
    if (state.blockScalarIndent !== null) {
        if (!line.trim() || indentation > state.blockScalarIndent) {
            return line ? wrapCodeToken("string", line) : "";
        }
        state.blockScalarIndent = null;
    }

    const commentStart = findYamlCommentStart(line);
    const content = commentStart === -1 ? line : line.slice(0, commentStart);
    const comment = commentStart === -1 ? "" : line.slice(commentStart);
    let html = "";
    let index = 0;

    while (index < content.length && /\s/.test(content[index])) {
        html += escapeHtml(content[index]);
        index++;
    }
    if (content[index] === "-" && (index + 1 === content.length || /\s/.test(content[index + 1]))) {
        html += wrapCodeToken("punctuation", "-");
        index++;
        while (index < content.length && /\s/.test(content[index])) {
            html += escapeHtml(content[index]);
            index++;
        }
    }

    const keySeparator = findYamlKeySeparator(content, index);
    if (keySeparator !== -1) {
        const keyEnd = trimTrailingWhitespaceIndex(content, keySeparator);
        if (keyEnd > index) {
            html += wrapCodeToken("property", content.slice(index, keyEnd));
        }
        html += escapeHtml(content.slice(keyEnd, keySeparator));
        html += wrapCodeToken("punctuation", ":");
        html += highlightYamlValue(content.slice(keySeparator + 1), state, indentation);
    } else {
        html += highlightYamlValue(content.slice(index), state, indentation);
    }

    if (comment) {
        html += wrapCodeToken("comment", comment);
    }
    return html;
}

function highlightYamlValue(source, state, indentation) {
    const trimmed = source.trimStart();
    if (trimmed.startsWith("|") || trimmed.startsWith(">")) {
        state.blockScalarIndent = indentation;
    }

    const fragments = [];
    let index = 0;
    let plainStart = 0;

    while (index < source.length) {
        const char = source[index];
        if (char === '"') {
            const end = consumeQuotedLiteral(source, index, '"', true);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            continue;
        }
        if (char === "'") {
            const end = consumeQuotedLiteral(source, index, "'", false, true);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            continue;
        }
        if ((char === "&" || char === "*") && /[A-Za-z0-9_.-]/.test(source[index + 1] || "")) {
            const end = consumeYamlReference(source, index);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "variable");
            index = end;
            continue;
        }
        if (char === "!" && !/\s/.test(source[index + 1] || "")) {
            const end = consumeYamlTag(source, index);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "annotation");
            index = end;
            continue;
        }

        const numberEnd = consumeYamlNumber(source, index);
        if (numberEnd > index) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, numberEnd, "number");
            index = numberEnd;
            continue;
        }

        if (char === "~") {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 1, "null");
            index += 1;
            continue;
        }

        if (/[A-Za-z]/.test(char)) {
            const end = consumeYamlWord(source, index);
            const token = source.slice(index, end);
            const normalized = token.toLowerCase();
            let type = "";
            if (YAML_BOOLEAN_LITERALS.has(normalized)) {
                type = "boolean";
            } else if (YAML_NULL_LITERALS.has(normalized)) {
                type = "null";
            }
            if (type) {
                plainStart = emitCodeToken(fragments, source, plainStart, index, end, type);
            }
            index = end;
            continue;
        }

        if ("[]{}|>?:,".includes(char)) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 1, "punctuation");
            index += 1;
            continue;
        }

        index++;
    }

    flushCodeTail(fragments, source, plainStart);
    return fragments.join("");
}

function highlightBashCode(source) {
    const fragments = [];
    let index = 0;
    let plainStart = 0;
    let expectCommand = true;

    while (index < source.length) {
        const char = source[index];
        if (char === "\n") {
            expectCommand = true;
            index++;
            continue;
        }
        if (char === "#" && isBashCommentStart(source, index)) {
            const end = lineEnd(source, index);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "comment");
            index = end;
            expectCommand = true;
            continue;
        }
        if (char === "'") {
            const end = consumeQuotedLiteral(source, index, "'", false);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            expectCommand = false;
            continue;
        }
        if (char === '"') {
            const end = consumeQuotedLiteral(source, index, '"', true);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            expectCommand = false;
            continue;
        }
        if (char === "`") {
            const end = consumeQuotedLiteral(source, index, "`", true);
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            expectCommand = false;
            continue;
        }
        if (char === "$") {
            const variableEnd = consumeBashVariable(source, index);
            if (variableEnd > index) {
                plainStart = emitCodeToken(fragments, source, plainStart, index, variableEnd, "variable");
                index = variableEnd;
                expectCommand = false;
                continue;
            }
        }

        const operator = matchBashOperator(source, index);
        if (operator) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + operator.length, "operator");
            index += operator.length;
            expectCommand = BASH_COMMAND_SEPARATOR_OPERATORS.has(operator);
            continue;
        }
        if ("(){}[]".includes(char)) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, index + 1, "punctuation");
            index += 1;
            expectCommand = char === "(" || char === "{";
            continue;
        }

        const numberEnd = consumeBashNumber(source, index);
        if (numberEnd > index) {
            plainStart = emitCodeToken(fragments, source, plainStart, index, numberEnd, "number");
            index = numberEnd;
            expectCommand = false;
            continue;
        }

        if (/[A-Za-z_]/.test(char)) {
            const end = consumeBashWord(source, index);
            const token = source.slice(index, end);
            let type = "";
            if (BASH_KEYWORDS.has(token)) {
                type = "keyword";
                expectCommand = BASH_COMMAND_KEYWORDS.has(token);
            } else if (expectCommand && BASH_BUILTINS.has(token)) {
                type = "builtin";
                expectCommand = false;
            } else {
                expectCommand = false;
            }
            if (type) {
                plainStart = emitCodeToken(fragments, source, plainStart, index, end, type);
            }
            index = end;
            continue;
        }

        if (!/\s/.test(char)) {
            expectCommand = false;
        }
        index++;
    }

    flushCodeTail(fragments, source, plainStart);
    return fragments.join("");
}

function highlightXmlCode(source) {
    const fragments = [];
    let index = 0;
    let plainStart = 0;

    while (index < source.length) {
        if (source.startsWith("<!--", index)) {
            const commentEnd = source.indexOf("-->", index + 4);
            const end = commentEnd === -1 ? source.length : commentEnd + 3;
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "comment");
            index = end;
            continue;
        }
        if (source.startsWith("<![CDATA[", index)) {
            const cdataEnd = source.indexOf("]]>", index + 9);
            const end = cdataEnd === -1 ? source.length : cdataEnd + 3;
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "string");
            index = end;
            continue;
        }
        if (source.startsWith("<?", index)) {
            const end = consumeXmlMarkup(source, index + 2, "?>");
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "annotation");
            index = end;
            continue;
        }
        if (source.startsWith("<!", index)) {
            const end = consumeXmlMarkup(source, index + 2, ">");
            plainStart = emitCodeToken(fragments, source, plainStart, index, end, "annotation");
            index = end;
            continue;
        }
        if (source[index] === "<") {
            if (plainStart < index) {
                fragments.push(escapeHtml(source.slice(plainStart, index)));
            }
            const tag = highlightXmlTag(source, index);
            fragments.push(tag.html);
            plainStart = tag.end;
            index = tag.end;
            continue;
        }
        if (source[index] === "&") {
            const entityEnd = consumeXmlEntity(source, index);
            if (entityEnd > index) {
                plainStart = emitCodeToken(fragments, source, plainStart, index, entityEnd, "variable");
                index = entityEnd;
                continue;
            }
        }
        index++;
    }

    flushCodeTail(fragments, source, plainStart);
    return fragments.join("");
}

function highlightXmlTag(source, start) {
    let index = start;
    let html = "";

    if (source.startsWith("</", index)) {
        html += wrapCodeToken("punctuation", "</");
        index += 2;
    } else {
        html += wrapCodeToken("punctuation", "<");
        index += 1;
    }

    while (index < source.length && /\s/.test(source[index])) {
        html += escapeHtml(source[index]);
        index++;
    }

    const tagNameEnd = consumeXmlName(source, index);
    if (tagNameEnd > index) {
        html += wrapCodeToken("type", source.slice(index, tagNameEnd));
        index = tagNameEnd;
    }

    while (index < source.length) {
        if (source.startsWith("/>", index)) {
            html += wrapCodeToken("punctuation", "/>");
            index += 2;
            break;
        }
        if (source[index] === ">") {
            html += wrapCodeToken("punctuation", ">");
            index++;
            break;
        }
        if (/\s/.test(source[index])) {
            html += escapeHtml(source[index]);
            index++;
            continue;
        }
        if (source[index] === "=") {
            html += wrapCodeToken("operator", "=");
            index++;
            continue;
        }
        if (source[index] === "\"" || source[index] === "'") {
            const end = consumeQuotedLiteral(source, index, source[index], false);
            html += wrapCodeToken("string", source.slice(index, end));
            index = end;
            continue;
        }
        if (source[index] === "&") {
            const entityEnd = consumeXmlEntity(source, index);
            if (entityEnd > index) {
                html += wrapCodeToken("variable", source.slice(index, entityEnd));
                index = entityEnd;
                continue;
            }
        }

        const attributeEnd = consumeXmlName(source, index);
        if (attributeEnd > index) {
            html += wrapCodeToken("property", source.slice(index, attributeEnd));
            index = attributeEnd;
            continue;
        }

        html += wrapCodeToken("punctuation", source[index]);
        index++;
    }

    return {html, end: index};
}

function emitCodeToken(fragments, source, plainStart, tokenStart, tokenEnd, type) {
    if (plainStart < tokenStart) {
        fragments.push(escapeHtml(source.slice(plainStart, tokenStart)));
    }
    fragments.push(wrapCodeToken(type, source.slice(tokenStart, tokenEnd)));
    return tokenEnd;
}

function flushCodeTail(fragments, source, plainStart) {
    if (plainStart < source.length) {
        fragments.push(escapeHtml(source.slice(plainStart)));
    }
}

function wrapCodeToken(type, text) {
    return `<span class="code-token code-token-${type}">${escapeHtml(text)}</span>`;
}

function lineEnd(source, index) {
    const newline = source.indexOf("\n", index);
    return newline === -1 ? source.length : newline;
}

function consumeQuotedLiteral(source, start, delimiter, allowEscapes, doubledDelimiterEscape = false) {
    let index = start + delimiter.length;
    while (index < source.length) {
        if (doubledDelimiterEscape && source.startsWith(delimiter + delimiter, index)) {
            index += delimiter.length * 2;
            continue;
        }
        if (allowEscapes && source[index] === "\\") {
            index = Math.min(source.length, index + 2);
            continue;
        }
        if (source.startsWith(delimiter, index)) {
            return index + delimiter.length;
        }
        index++;
    }
    return source.length;
}

function consumeIdentifier(source, start) {
    let index = start + 1;
    while (index < source.length && isIdentifierPart(source[index])) {
        index++;
    }
    return index;
}

function consumeJavaAnnotation(source, start) {
    let index = start + 1;
    while (index < source.length && (source[index] === "." || isIdentifierPart(source[index]))) {
        index++;
    }
    return index;
}

function consumeJavaNumber(source, start) {
    if (!(isDigit(source[start]) || (source[start] === "." && isDigit(source[start + 1])))) {
        return start;
    }
    const match = source.slice(start).match(/^(?:0[xX][0-9a-fA-F_]+(?:\.[0-9a-fA-F_]+)?(?:[pP][+-]?\d[\d_]*)?[dDfFlL]?|0[bB][01_]+[lL]?|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d[\d_]*)?[dDfFlL]?|\.\d[\d_]+(?:[eE][+-]?\d[\d_]*)?[dDfF]?)/);
    return match ? start + match[0].length : start;
}

function consumeJsonNumber(source, start) {
    if (!(isDigit(source[start]) || (source[start] === "-" && isDigit(source[start + 1])))) {
        return start;
    }
    const match = source.slice(start).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
    return match ? start + match[0].length : start;
}

function consumeYamlNumber(source, start) {
    if (!(isDigit(source[start]) || (source[start] === "-" && isDigit(source[start + 1])))) {
        return start;
    }
    const match = source.slice(start).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
    return match ? start + match[0].length : start;
}

function consumeYamlReference(source, start) {
    let index = start + 1;
    while (index < source.length && /[A-Za-z0-9_.-]/.test(source[index])) {
        index++;
    }
    return index;
}

function consumeYamlTag(source, start) {
    let index = start + 1;
    while (index < source.length && !/\s/.test(source[index]) && !",[]{}".includes(source[index])) {
        index++;
    }
    return index;
}

function consumeYamlWord(source, start) {
    let index = start + 1;
    while (index < source.length && /[A-Za-z0-9_.-]/.test(source[index])) {
        index++;
    }
    return index;
}

function consumeBashVariable(source, start) {
    if (source[start] !== "$") {
        return start;
    }
    const next = source[start + 1];
    if (!next) {
        return start;
    }
    if (next === "{") {
        return consumeBalancedLiteral(source, start + 2, "{", "}");
    }
    if (next === "(") {
        return consumeBalancedLiteral(source, start + 2, "(", ")");
    }
    if (/[A-Za-z_]/.test(next)) {
        let index = start + 2;
        while (index < source.length && /[A-Za-z0-9_]/.test(source[index])) {
            index++;
        }
        return index;
    }
    if (/[0-9@*#?$!_-]/.test(next)) {
        return start + 2;
    }
    return start;
}

function consumeBalancedLiteral(source, start, openChar, closeChar) {
    let depth = 1;
    let index = start;
    while (index < source.length) {
        if (source[index] === "'") {
            index = consumeQuotedLiteral(source, index, "'", false);
            continue;
        }
        if (source[index] === '"') {
            index = consumeQuotedLiteral(source, index, '"', true);
            continue;
        }
        if (source[index] === "`") {
            index = consumeQuotedLiteral(source, index, "`", true);
            continue;
        }
        if (source[index] === "\\") {
            index = Math.min(source.length, index + 2);
            continue;
        }
        if (source[index] === openChar) {
            depth++;
            index++;
            continue;
        }
        if (source[index] === closeChar) {
            depth--;
            index++;
            if (depth === 0) {
                return index;
            }
            continue;
        }
        index++;
    }
    return source.length;
}

function consumeBashNumber(source, start) {
    if (!(isDigit(source[start]) || (source[start] === "-" && isDigit(source[start + 1])))) {
        return start;
    }
    const match = source.slice(start).match(/^-?\d+(?:\.\d+)?/);
    return match ? start + match[0].length : start;
}

function consumeBashWord(source, start) {
    let index = start + 1;
    while (index < source.length && /[A-Za-z0-9_]/.test(source[index])) {
        index++;
    }
    return index;
}

function consumeXmlName(source, start) {
    const first = source[start];
    if (!first || !/[A-Za-z_:]/.test(first)) {
        return start;
    }
    let index = start + 1;
    while (index < source.length && /[A-Za-z0-9_.:-]/.test(source[index])) {
        index++;
    }
    return index;
}

function consumeXmlEntity(source, start) {
    if (source[start] !== "&") {
        return start;
    }
    const match = source.slice(start).match(/^&(?:#\d+|#x[0-9a-fA-F]+|[A-Za-z_][A-Za-z0-9_.:-]*);/);
    return match ? start + match[0].length : start;
}

function consumeXmlMarkup(source, start, closingToken) {
    let index = start;
    let bracketDepth = 0;

    while (index < source.length) {
        if (source[index] === "'" || source[index] === "\"") {
            index = consumeQuotedLiteral(source, index, source[index], false);
            continue;
        }
        if (closingToken === ">" && source[index] === "[") {
            bracketDepth++;
            index++;
            continue;
        }
        if (closingToken === ">" && source[index] === "]") {
            bracketDepth = Math.max(0, bracketDepth - 1);
            index++;
            continue;
        }
        if (closingToken === "?>" && source.startsWith("?>", index)) {
            return index + 2;
        }
        if (closingToken === ">" && bracketDepth === 0 && source[index] === ">") {
            return index + 1;
        }
        index++;
    }

    return source.length;
}

function matchAnyPrefix(source, start, prefixes) {
    for (const prefix of prefixes) {
        if (source.startsWith(prefix, start)) {
            return prefix;
        }
    }
    return "";
}

function matchBashOperator(source, start) {
    const multiChar = matchAnyPrefix(source, start, BASH_MULTI_CHAR_OPERATORS);
    if (multiChar) {
        return multiChar;
    }
    const singleChar = source[start];
    return "|&;=<>".includes(singleChar) ? singleChar : "";
}

function findYamlCommentStart(line) {
    for (let index = 0; index < line.length; index++) {
        if (line[index] === "'") {
            index = consumeQuotedLiteral(line, index, "'", false, true) - 1;
            continue;
        }
        if (line[index] === '"') {
            index = consumeQuotedLiteral(line, index, '"', true) - 1;
            continue;
        }
        if (line[index] === "#" && (index === 0 || /\s/.test(line[index - 1]))) {
            return index;
        }
    }
    return -1;
}

function findYamlKeySeparator(source, start) {
    let braceDepth = 0;
    let bracketDepth = 0;
    for (let index = start; index < source.length; index++) {
        if (source[index] === "'") {
            index = consumeQuotedLiteral(source, index, "'", false, true) - 1;
            continue;
        }
        if (source[index] === '"') {
            index = consumeQuotedLiteral(source, index, '"', true) - 1;
            continue;
        }
        if (source[index] === "{") {
            braceDepth++;
            continue;
        }
        if (source[index] === "}") {
            braceDepth = Math.max(0, braceDepth - 1);
            continue;
        }
        if (source[index] === "[") {
            bracketDepth++;
            continue;
        }
        if (source[index] === "]") {
            bracketDepth = Math.max(0, bracketDepth - 1);
            continue;
        }
        if (source[index] === ":" && braceDepth === 0 && bracketDepth === 0) {
            const next = source[index + 1] || "";
            if (!next || /\s/.test(next) || "[{]},#\"'|>!&*-".includes(next)) {
                return index;
            }
        }
    }
    return -1;
}

function trimTrailingWhitespaceIndex(source, end) {
    let index = end;
    while (index > 0 && /\s/.test(source[index - 1])) {
        index--;
    }
    return index;
}

function nextNonWhitespaceIndex(source, start) {
    let index = start;
    while (index < source.length && /\s/.test(source[index])) {
        index++;
    }
    return index;
}

function isBashCommentStart(source, index) {
    if (source[index] !== "#") {
        return false;
    }
    const previous = index === 0 ? "" : source[index - 1];
    if (!previous || previous === "\n") {
        return true;
    }
    if (!/\s/.test(previous) && !";|&(){}[]".includes(previous)) {
        return false;
    }
    if (previous === "{" && source[index - 2] === "$") {
        return false;
    }
    return true;
}

function isIdentifierStart(char) {
    return Boolean(char) && /[A-Za-z_$]/.test(char);
}

function isIdentifierPart(char) {
    return Boolean(char) && /[A-Za-z0-9_$]/.test(char);
}

function isLiteralBoundary(char) {
    return !char || !/[A-Za-z0-9_$-]/.test(char);
}

function isDigit(char) {
    return Boolean(char) && /[0-9]/.test(char);
}

function renderMarkdownTable(block) {
    const header = block.header.map((cell, index) => renderMarkdownTableCell("th", cell, block.alignments[index])).join("");
    const body = block.rows.map((row) => `
        <tr>${row.map((cell, index) => renderMarkdownTableCell("td", cell, block.alignments[index])).join("")}</tr>
    `).join("");

    return `
        <div class="chat-markdown-table-wrap">
            <table class="chat-markdown-table">
                <thead>
                    <tr>${header}</tr>
                </thead>
                <tbody>${body}</tbody>
            </table>
        </div>
    `;
}

function renderMarkdownTableCell(tagName, text, alignment) {
    const alignAttr = alignment ? ` style="text-align:${alignment}"` : "";
    return `<${tagName}${alignAttr}>${renderMarkdownInline(text)}</${tagName}>`;
}

function renderMarkdownInline(text) {
    let html = escapeHtml(text);
    const tokens = [];

    html = html.replace(/`([^`]+)`/g, (_, code) => stashMarkdownToken(tokens, `<code>${code}</code>`));
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_, label, url) => stashMarkdownToken(tokens, renderMarkdownLink(label, url)));
    html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    html = html.replace(/__([^_]+)__/g, "<strong>$1</strong>");
    html = html.replace(/~~([^~]+)~~/g, "<del>$1</del>");

    return tokens.reduce((current, token, index) => current.replaceAll(`__md_token_${index}__`, token), html);
}

function stashMarkdownToken(tokens, html) {
    const token = `__md_token_${tokens.length}__`;
    tokens.push(html);
    return token;
}

function renderMarkdownLink(label, rawUrl) {
    const href = sanitizeUrl(rawUrl);
    if (!href) {
        return label;
    }
    return `<a href="${href}" target="_blank" rel="noreferrer noopener">${label}</a>`;
}

function sanitizeUrl(rawUrl) {
    const url = String(rawUrl || "").trim();
    if (/^(https?:|mailto:)/i.test(url) || url.startsWith("/") || url.startsWith("#")) {
        return url;
    }
    return null;
}

function isMarkdownTableStart(lines, index) {
    if (index + 1 >= lines.length) {
        return false;
    }

    const headerCells = parseMarkdownTableRow(lines[index]);
    const separatorCells = parseMarkdownTableRow(lines[index + 1]);
    if (headerCells.length < 2 || separatorCells.length !== headerCells.length) {
        return false;
    }

    return separatorCells.every(isMarkdownTableSeparatorCell);
}

function isMarkdownTableRow(line, expectedColumns) {
    const cells = parseMarkdownTableRow(line);
    return cells.length === expectedColumns && !cells.every(isMarkdownTableSeparatorCell);
}

function parseMarkdownTableAlignments(separatorLine, expectedColumns) {
    return parseMarkdownTableRow(separatorLine, expectedColumns).map((cell) => {
        const normalized = cell.replace(/\s+/g, "");
        const left = normalized.startsWith(":");
        const right = normalized.endsWith(":");
        if (left && right) {
            return "center";
        }
        if (right) {
            return "right";
        }
        return "left";
    });
}

function parseMarkdownTableRow(line, expectedColumns) {
    const normalized = normalizeMarkdownBlockLine(line).trim();
    if (!normalized.includes("|")) {
        return [];
    }

    let row = normalized;
    if (row.startsWith("|")) {
        row = row.slice(1);
    }
    if (row.endsWith("|")) {
        row = row.slice(0, -1);
    }

    const cells = row.split("|").map((cell) => cell.trim());
    if (!expectedColumns) {
        return cells;
    }

    if (cells.length >= expectedColumns) {
        return cells.slice(0, expectedColumns);
    }

    return cells.concat(Array.from({length: expectedColumns - cells.length}, () => ""));
}

function isMarkdownTableSeparatorCell(cell) {
    return /^:?-{3,}:?$/.test(cell.replace(/\s+/g, ""));
}

function inputFieldMarkup(parameter, kind) {
    const id = `${kind}-${parameter.name}`;
    if (hasEnumChoices(parameter)) {
        return `
            <div class="field">
                <label for="${escapeHtml(id)}">${escapeHtml(parameter.name)}</label>
                <select
                    id="${escapeHtml(id)}"
                    data-${escapeHtml(kind)}="${escapeHtml(parameter.name)}">
                    <option value="">Select ${escapeHtml(parameter.name)}</option>
                    ${parameter.enumValues.map((value) => `
                        <option value="${escapeHtml(value)}">${escapeHtml(value)}</option>
                    `).join("")}
                </select>
            </div>
        `;
    }

    if (parameter.simpleText) {
        return `
            <div class="field">
                <label for="${escapeHtml(id)}">${escapeHtml(parameter.name)}</label>
                <input
                    id="${escapeHtml(id)}"
                    data-${escapeHtml(kind)}="${escapeHtml(parameter.name)}"
                    type="text"
                    placeholder="${escapeHtml(parameter.typeName)}">
            </div>
        `;
    }

    return `
        <div class="field">
            <label for="${escapeHtml(id)}">${escapeHtml(parameter.name)}</label>
            <textarea
                id="${escapeHtml(id)}"
                data-${escapeHtml(kind)}="${escapeHtml(parameter.name)}"
                rows="5"
                placeholder='{"value":"..."}'></textarea>
        </div>
    `;
}

function hasEnumChoices(parameter) {
    return Array.isArray(parameter?.enumValues) && parameter.enumValues.length > 0;
}

function renderAdvancedFields(argumentParameters, stateParameters, collapsible) {
    const fields = [];
    argumentParameters.forEach((parameter) => fields.push(inputFieldMarkup(parameter, "param")));
    if (stateParameters.length > 0) {
        fields.push(`
            <div class="field">
                <label>Agentic state</label>
                <div class="field-note">Additional workflow variables required by routing or activation conditions.</div>
            </div>
        `);
        stateParameters.forEach((parameter) => fields.push(inputFieldMarkup(parameter, "state-param")));
    }

    if (fields.length === 0) {
        return "";
    }

    if (collapsible) {
        return `
            <details class="advanced-fields">
                <summary>Advanced input and agentic state</summary>
                <div class="advanced-fields-body">
                    ${fields.join("")}
                </div>
            </details>
        `;
    }

    return `
        <div class="advanced-fields is-inline">
            <div class="advanced-fields-body">
                ${fields.join("")}
            </div>
        </div>
    `;
}

function renderStatus() {
    agentChip.textContent = state.selectedAgent ? state.selectedAgent.name : "No agent";
    methodChip.textContent = state.selectedMethod ? state.selectedMethod.name : "No method";
}

async function invokeAgent() {
    let conversation;
    let invocationId;

    try {
        if (!state.selectedAgent || !state.selectedMethod) {
            throw new Error("Select an agent and method first");
        }

        conversation = ensureConversation();
        if (conversation.pending) {
            return;
        }

        const speaker = state.selectedAgent.name;
        const detail = state.selectedMethod.id;
        const payload = collectInvokePayload(conversation);
        const requestPayload = transcriptRequestPayload(payload);

        conversation.pending = true;
        conversation.progressEvents = [];
        conversation.lastRequest = payload;
        invocationId = startPendingInvocation(conversation, requestPayload, {
            speaker,
            detail
        });

        syncConversationControls();
        renderResult();
        renderInspector();
        renderChatHistory();

        const started = await api("/api/invocations", {
            method: "POST",
            body: JSON.stringify(payload)
        });
        const response = await pollInvocation(started.invocationId, conversation);

        conversation.pending = false;
        conversation.pendingInvocationId = null;
        conversation.lastResponse = response;
        conversation.inspection = response.inspection || null;
        conversation.progressEvents = response.inspection?.events || conversation.progressEvents;
        conversation.runCount += 1;
        resolvePendingInvocation(conversation, invocationId, response.result, {
            speaker,
            detail
        });

        syncConversationControls();
        renderResult();
        renderInspector();
        renderChatHistory();
        toastMessage("Invocation completed");
    } catch (error) {
        if (conversation) {
            conversation.pending = false;
            conversation.pendingInvocationId = null;
            if (invocationId) {
                rejectPendingInvocation(conversation, invocationId, error);
            }
            syncConversationControls();
            renderResult();
            renderInspector();
            renderChatHistory();
        }
        showError(error);
    }
}

async function pollInvocation(invocationId, conversation) {
    let response = await api(`/api/invocations/${encodeURIComponent(invocationId)}`, {headers: {}});

    while (response.status === "running") {
        conversation.inspection = response.inspection || conversation.inspection;
        conversation.progressEvents = response.inspection?.events || conversation.progressEvents;
        renderInspector();
        await delay(INVOCATION_POLL_INTERVAL_MS);
        response = await api(`/api/invocations/${encodeURIComponent(invocationId)}`, {headers: {}});
    }

    conversation.inspection = response.inspection || conversation.inspection;
    conversation.progressEvents = response.inspection?.events || conversation.progressEvents;
    renderInspector();

    if (response.status === "failed") {
        throw new Error(response.error || "Invocation failed");
    }
    return response;
}

function collectInvokePayload(conversation) {
    const parameters = (state.selectedMethod?.parameters || []).filter((parameter) => !parameter.memoryId);
    const argumentsPayload = {};

    parameters.forEach((parameter) => {
        const field = document.querySelector(`[data-param="${cssEscape(parameter.name)}"]`);
        const value = field?.value ?? "";
        if (hasEnumChoices(parameter)) {
            if (value.trim()) {
                argumentsPayload[parameter.name] = value.trim();
            }
            return;
        }
        if (parameter.simpleText) {
            argumentsPayload[parameter.name] = value;
            return;
        }
        argumentsPayload[parameter.name] = value.trim() ? JSON.parse(value) : null;
    });

    const statePayload = {};
    (state.selectedMethod?.stateParameters || []).forEach((parameter) => {
        const field = document.querySelector(`[data-state-param="${cssEscape(parameter.name)}"]`);
        const value = field?.value ?? "";
        if (!value.trim()) {
            return;
        }
        if (hasEnumChoices(parameter)) {
            statePayload[parameter.name] = value.trim();
            return;
        }
        if (parameter.simpleText) {
            statePayload[parameter.name] = value.trim();
            return;
        }
        statePayload[parameter.name] = JSON.parse(value);
    });

    const needsMemoryId = (state.selectedMethod?.parameters || []).some((parameter) => parameter.memoryId);
    return {
        agent: state.selectedAgent.name,
        method: state.selectedMethod.id,
        memoryId: needsMemoryId ? ensureConversationMemoryId(conversation) : null,
        arguments: argumentsPayload,
        state: Object.keys(statePayload).length === 0 ? null : statePayload
    };
}

function clearConversation() {
    if (!state.selectedAgent || !state.selectedMethod) {
        return;
    }

    const conversation = freshConversation();
    state.conversations[currentConversationKey()] = conversation;
    syncConversationControls(conversation);
    renderResult();
    renderInspector();
    renderChatHistory();
    toastMessage("Conversation cleared");
}

function switchTab(tabName) {
    document.querySelectorAll(".tab-button").forEach((button) => {
        button.classList.toggle("is-active", button.dataset.tab === tabName);
    });
    document.querySelectorAll(".tab-panel").forEach((panel) => {
        panel.classList.toggle("is-active", panel.dataset.panel === tabName);
    });
}

function buildInvocationEntries(input, output, options = {}) {
    const entries = [];

    if (input !== undefined) {
        entries.push({
            id: `${options.idBase}-user`,
            role: "user",
            speaker: "You",
            meta: state.selectedMethod?.name || "Invocation",
            payload: bubblePayload(input)
        });
    }

    if (output !== undefined || options.pending) {
        entries.push({
            id: `${options.idBase}-assistant`,
            role: "assistant",
            speaker: options.speaker || state.selectedAgent?.name || "Agent",
            meta: options.detail || state.selectedMethod?.id || "",
            payload: options.pending
                ? pendingPayload(options.pendingText)
                : bubblePayload(output),
            pending: Boolean(options.pending)
        });
    }

    return entries;
}

function pendingPayload(text) {
    return {
        kind: "pending",
        text: text || "Waiting for response..."
    };
}

function transcriptRequestPayload(payload) {
    if (!payload) {
        return undefined;
    }

    const argumentsPayload = payload.arguments || {};
    const statePayload = payload.state || {};
    const argumentEntries = Object.entries(argumentsPayload).filter(([, value]) => value !== undefined);
    const hasState = Object.keys(statePayload).length > 0;

    if (state.selectedMethod?.chatLike && argumentEntries.length === 1 && !hasState) {
        return argumentEntries[0][1];
    }

    const normalized = {};
    argumentEntries.forEach(([name, value]) => {
        normalized[name] = value;
    });
    if (hasState) {
        normalized.agenticState = statePayload;
    }

    return Object.keys(normalized).length === 0 ? undefined : normalized;
}

function currentConversationKey() {
    if (!state.selectedAgent || !state.selectedMethod) {
        return null;
    }
    return `${state.selectedAgent.name}::${state.selectedMethod.id}`;
}

function currentConversation() {
    const key = currentConversationKey();
    return key ? ensureConversation() : null;
}

function ensureConversation() {
    const key = currentConversationKey();
    if (!key) {
        return null;
    }
    if (!state.conversations[key]) {
        state.conversations[key] = freshConversation();
    }
    return state.conversations[key];
}

function freshConversation() {
    return {
        entries: [],
        memoryId: null,
        lastRequest: null,
        lastResponse: null,
        inspection: null,
        progressEvents: [],
        pending: false,
        pendingInvocationId: null,
        runCount: 0
    };
}

function startPendingInvocation(conversation, input, options = {}) {
    const invocationId = `${currentConversationKey()}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    conversation.pendingInvocationId = invocationId;
    conversation.entries.push(...buildInvocationEntries(input, undefined, {
        idBase: invocationId,
        speaker: options.speaker,
        detail: options.detail,
        pending: true,
        pendingText: options.pendingText
    }));
    return invocationId;
}

function resolvePendingInvocation(conversation, invocationId, output, options = {}) {
    const assistantEntryId = `${invocationId}-assistant`;
    const assistantIndex = conversation.entries.findIndex((entry) => entry.id === assistantEntryId);
    const resolvedEntry = {
        id: assistantEntryId,
        role: "assistant",
        speaker: options.speaker || state.selectedAgent?.name || "Agent",
        meta: options.detail || state.selectedMethod?.id || "",
        payload: bubblePayload(output),
        pending: false
    };

    if (assistantIndex >= 0) {
        conversation.entries[assistantIndex] = resolvedEntry;
    } else {
        conversation.entries.push(resolvedEntry);
    }
}

function rejectPendingInvocation(conversation, invocationId, error) {
    const assistantEntryId = `${invocationId}-assistant`;
    const assistantIndex = conversation.entries.findIndex((entry) => entry.id === assistantEntryId);
    const message = error?.message || String(error);
    const previousEntry = assistantIndex >= 0 ? conversation.entries[assistantIndex] : null;
    const failedEntry = {
        id: assistantEntryId,
        role: "assistant",
        speaker: previousEntry?.speaker || "Agent",
        meta: "Request failed",
        payload: {
            kind: "markdown",
            text: `**Request failed**\n\n${message}`
        },
        pending: false
    };

    if (assistantIndex >= 0) {
        conversation.entries[assistantIndex] = failedEntry;
    } else {
        conversation.entries.push(failedEntry);
    }
}

function syncConversationControls(conversation = currentConversation()) {
    const isPending = Boolean(conversation?.pending);
    const hasContent = Boolean(conversation && (conversation.runCount > 0 || conversation.entries.length > 0));

    invokeButton.disabled = !state.selectedAgent || !state.selectedMethod || isPending;
    invokeButton.classList.toggle("is-running", isPending);
    invokeButton.setAttribute("aria-busy", isPending ? "true" : "false");
    invokeButton.textContent = isPending
        ? state.selectedMethod?.chatLike ? "Sending..." : "Running..."
        : state.selectedMethod?.chatLike ? "Send" : "Run";
    clearConversationButton.disabled = !state.selectedAgent || !state.selectedMethod || isPending || !hasContent;
    methodSelect.disabled = !state.selectedAgent || !state.selectedMethod || isPending;
    invokeForm.classList.toggle("is-busy", isPending);
    invokeForm.setAttribute("aria-busy", isPending ? "true" : "false");
    invokeForm.querySelectorAll("input, textarea, select, button").forEach((element) => {
        element.disabled = isPending;
    });
}

function ensureConversationMemoryId(conversation) {
    if (!conversation.memoryId) {
        conversation.memoryId = createConversationMemoryId();
    }
    return conversation.memoryId;
}

function createConversationMemoryId() {
    if (window.crypto?.randomUUID) {
        return `browser-${window.crypto.randomUUID()}`;
    }
    return `browser-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function delay(durationMs) {
    return new Promise((resolve) => window.setTimeout(resolve, durationMs));
}

function pretty(value) {
    if (typeof value === "string") {
        return value;
    }
    return JSON.stringify(value, null, 2);
}

function showError(error) {
    console.error(error);
    toastMessage(error.message || String(error), true);
}

function toastMessage(message, isError = false) {
    toast.hidden = false;
    toast.textContent = message;
    toast.style.background = isError ? "var(--toast-error)" : "var(--toast-bg)";
    toast.style.borderColor = isError ? "rgba(255, 211, 224, 0.3)" : "rgba(168, 207, 240, 0.18)";
    clearTimeout(toast.timeoutId);
    toast.timeoutId = window.setTimeout(() => {
        toast.hidden = true;
    }, 3200);
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function cssEscape(value) {
    return value.replace(/"/g, '\\"');
}
