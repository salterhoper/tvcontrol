import { useEffect, useState } from "react";
import type { Api, ProxyServer, ProxyServerInput, ProxyState } from "../api";
import { IconEdit, IconPlus, IconTrash } from "../components/icons";
import { StatusLine } from "../components/ui";

type Toast = (message: string, level?: "info" | "warn" | "error") => void;

const EMPTY: ProxyServerInput = { name: "", host: "", port: 1080, username: "", password: "" };

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** Accepts `socks5://user:pass@host:port`, `host:port:user:pass` or `host:port`. */
function parseProxyUrl(text: string): Partial<ProxyServerInput> | null {
  const raw = text.trim();
  if (!raw) return null;
  try {
    if (/^socks5h?:\/\//i.test(raw)) {
      const url = new URL(raw.replace(/^socks5h?:/i, "http:"));
      return {
        host: url.hostname,
        port: url.port ? Number(url.port) : 1080,
        username: decodeURIComponent(url.username),
        password: decodeURIComponent(url.password),
      };
    }
  } catch {
    return null;
  }
  const parts = raw.split(":");
  if (parts.length === 2 || parts.length === 4) {
    const port = Number(parts[1]);
    if (!parts[0] || !Number.isInteger(port)) return null;
    return { host: parts[0], port, username: parts[2] ?? "", password: parts[3] ?? "" };
  }
  return null;
}

/** "Route this TV through a SOCKS5 proxy" - saved servers, one-tap switching, live traffic. */
export function ProxyCard({ api, onToast }: { api: Api | null; onToast: Toast }) {
  const [state, setState] = useState<ProxyState | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [form, setForm] = useState<ProxyServerInput | null>(null);
  const [paste, setPaste] = useState("");

  useEffect(() => {
    if (!api) return;
    let cancelled = false;
    const load = () =>
      api
        .proxy()
        .then((next) => {
          if (!cancelled) setState(next);
        })
        .catch(() => undefined);
    void load();
    // Live numbers while routing is on, and so the card notices the TV-side VPN prompt being accepted.
    const timer = window.setInterval(() => void load(), 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [api]);

  async function run(label: string, action: () => Promise<ProxyState>, warnUnless?: (next: ProxyState) => boolean) {
    setBusy(label);
    try {
      const next = await action();
      setState(next);
      if (next.note) onToast(next.note, warnUnless && !warnUnless(next) ? "warn" : "info");
      return next;
    } catch (error) {
      onToast(error instanceof Error ? error.message : "The TV did not accept that", "error");
      return null;
    } finally {
      setBusy(null);
    }
  }

  async function save() {
    if (!api || !form) return;
    const payload: ProxyServerInput = { ...form, host: form.host.trim(), name: form.name.trim() };
    // Editing with an empty password box keeps the stored one.
    if (form.id && !form.password) delete payload.password;
    const next = await run("save", () => api.saveProxyServer(payload));
    if (next) {
      setForm(null);
      setPaste("");
    }
  }

  function edit(server: ProxyServer) {
    setForm({
      id: server.id,
      name: server.name,
      host: server.host,
      port: server.port,
      username: server.username ?? "",
      password: "",
    });
    setPaste("");
  }

  if (!state) {
    return (
      <section className="card card--tight">
        <p className="card__label">Route traffic through a proxy</p>
        <p className="legend">Loading…</p>
      </section>
    );
  }

  const active = state.servers.find((server) => server.active);
  const badge = state.running ? "ok" : state.enabled ? "warn" : "off";
  const badgeText = state.running ? "On" : state.paused ? "Paused" : state.enabled ? "Starting" : "Off";

  return (
    <section className="card card--tight">
      <div className="card__head">
        <p className="card__label">Route traffic through a proxy (SOCKS5)</p>
        <span className={`badge badge--${badge}`}>{badgeText}</span>
      </div>
      <p className="legend">{state.verdict}</p>
      {state.actions.length > 0 ? (
        <ol className="legend legend--list">
          {state.actions.map((action) => (
            <li key={action}>{action}</li>
          ))}
        </ol>
      ) : null}

      {state.running && state.stats ? (
        <div className="rowsplit">
          <StatusLine
            label="Exit"
            value={active?.name ?? "—"}
            sub={active?.lastTest?.exitIp ? `seen as ${active.lastTest.exitIp}` : `${active?.host}:${active?.port}`}
            tone="ok"
          />
          <StatusLine
            label="Traffic"
            value={`↑ ${formatBytes(state.stats.bytesUp)} · ↓ ${formatBytes(state.stats.bytesDown)}`}
            sub={`${state.stats.activeConnections} open · ${state.stats.totalConnections} total · ${state.stats.dnsQueries} lookups`}
          />
        </div>
      ) : null}

      {state.servers.length > 0 ? (
        <ul className="proxy-list">
          {state.servers.map((server) => (
            <li key={server.id} className={`proxy-row${server.active ? " proxy-row--active" : ""}`}>
              <button
                type="button"
                className="proxy-row__main"
                disabled={busy !== null || server.active}
                onClick={() =>
                  void run(`select-${server.id}`, () => api!.selectProxy(server.id), (next) => next.activeId === server.id)
                }
                aria-pressed={server.active}
                title={server.active ? "In use" : state.running ? "Switch the TV to this server" : "Use this server"}
              >
                <span className={`proxy-row__dot${server.active ? " proxy-row__dot--on" : ""}`} />
                <span className="proxy-row__text">
                  <strong>{server.name}</strong>
                  <span>
                    {server.host}:{server.port}
                    {server.username ? ` · ${server.username}` : ""}
                  </span>
                  {server.lastTest ? (
                    <span className={server.lastTest.ok ? "proxy-row__ok" : "proxy-row__bad"}>
                      {server.lastTest.ok
                        ? `${server.lastTest.latencyMs ?? "?"} ms${server.lastTest.exitIp ? ` · ${server.lastTest.exitIp}` : ""}`
                        : server.lastTest.message}
                    </span>
                  ) : null}
                </span>
                {busy === `select-${server.id}` ? <span className="legend">Switching…</span> : null}
              </button>
              <div className="proxy-row__tools">
                <button
                  type="button"
                  className="btn btn--sm"
                  disabled={busy !== null}
                  onClick={() =>
                    void run(`test-${server.id}`, () => api!.testProxyServer(server.id), (next) =>
                      Boolean(next.servers.find((s) => s.id === server.id)?.lastTest?.ok),
                    )
                  }
                >
                  {busy === `test-${server.id}` ? "Testing…" : "Test"}
                </button>
                <button type="button" className="btn-mini" aria-label={`Edit ${server.name}`} onClick={() => edit(server)}>
                  <IconEdit />
                </button>
                <button
                  type="button"
                  className="btn-mini btn-mini--danger"
                  aria-label={`Delete ${server.name}`}
                  disabled={busy !== null}
                  onClick={() => void run(`delete-${server.id}`, () => api!.deleteProxyServer(server.id))}
                >
                  <IconTrash />
                </button>
              </div>
            </li>
          ))}
        </ul>
      ) : null}

      {form ? (
        <div className="proxy-form">
          {!form.id ? (
            <input
              className="field"
              value={paste}
              onChange={(event) => {
                setPaste(event.target.value);
                const parsed = parseProxyUrl(event.target.value);
                if (parsed) setForm({ ...form, ...parsed });
              }}
              placeholder="Paste socks5://user:pass@host:port (optional)"
              autoCapitalize="none"
              spellCheck={false}
            />
          ) : null}
          <input
            className="field"
            value={form.name}
            onChange={(event) => setForm({ ...form, name: event.target.value })}
            placeholder="Name, e.g. Home VPS"
          />
          <div className="rowsplit">
            <input
              className="field"
              value={form.host}
              onChange={(event) => setForm({ ...form, host: event.target.value })}
              placeholder="Host or IP"
              autoCapitalize="none"
              spellCheck={false}
            />
            <input
              className="field"
              type="number"
              min={1}
              max={65535}
              value={form.port}
              onChange={(event) => setForm({ ...form, port: Number(event.target.value) })}
              placeholder="1080"
            />
          </div>
          <div className="rowsplit">
            <input
              className="field"
              value={form.username ?? ""}
              onChange={(event) => setForm({ ...form, username: event.target.value })}
              placeholder="Username (optional)"
              autoCapitalize="none"
              spellCheck={false}
              autoComplete="off"
            />
            <input
              className="field"
              type="password"
              value={form.password ?? ""}
              onChange={(event) => setForm({ ...form, password: event.target.value })}
              placeholder={
                form.id && state.servers.find((s) => s.id === form.id)?.hasPassword
                  ? "Stored - type to replace"
                  : "Password (optional)"
              }
              autoCapitalize="none"
              spellCheck={false}
              autoComplete="new-password"
            />
          </div>
          <div className="rowsplit">
            <button type="button" className="btn" onClick={() => setForm(null)}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn--primary"
              disabled={busy !== null || !form.host.trim() || !(form.port >= 1 && form.port <= 65535)}
              onClick={() => void save()}
            >
              {busy === "save" ? "Saving…" : form.id ? "Save changes" : "Add server"}
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          className="btn btn--wide"
          style={{ marginTop: 8, display: "flex", alignItems: "center", justifyContent: "center", gap: 6 }}
          onClick={() => setForm({ ...EMPTY })}
        >
          <IconPlus /> Add SOCKS5 server
        </button>
      )}

      {state.servers.length > 0 ? (
        <div className="rowsplit" style={{ marginTop: 8 }}>
          {state.enabled ? (
            <button
              type="button"
              className="btn btn--danger"
              disabled={busy !== null}
              onClick={() => void run("toggle", () => api!.setProxyEnabled(false))}
            >
              {busy === "toggle" ? "Stopping…" : "Turn routing off"}
            </button>
          ) : (
            <button
              type="button"
              className="btn btn--primary"
              disabled={busy !== null || !active}
              onClick={() =>
                void run("toggle", () => api!.setProxyEnabled(true), (next) => next.running || next.consentNeeded)
              }
            >
              {busy === "toggle" ? "Testing & starting…" : `Route TV through ${active?.name ?? "proxy"}`}
            </button>
          )}
          <label className="switch card card--tight">
            <span>Kill switch</span>
            <input
              type="checkbox"
              checked={!state.failOpen}
              disabled={busy !== null}
              onChange={(event) => void run("options", () => api!.setProxyOptions({ failOpen: !event.target.checked }))}
            />
          </label>
        </div>
      ) : null}

      <p className="hint">
        Sends every app on the TV through the selected server using Android's VPN slot (one-time OK on the TV). Tap a
        server to switch - open connections move over at once. Names are looked up by the proxy; QUIC and IPv6 are held
        back so apps use plain TCP through it, and your home network stays direct so this dashboard keeps working. Kill
        switch: if the proxy dies, the TV stays offline instead of falling back to its own connection.
      </p>
    </section>
  );
}

export default ProxyCard;
