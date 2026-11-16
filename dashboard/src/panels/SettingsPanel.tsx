import { useEffect, useState } from "react";
import type { AdBlockState, DiagnosticsReport, Settings } from "../api";
import { IconBluetooth, IconUpload } from "../components/icons";
import { Labeled, Sheet, StatusLine } from "../components/ui";
import ProxyCard from "./ProxyCard";
import RootCard from "./RootCard";
import type { RemoteState } from "../useRemote";

/** Keep in step with LlmDefaults on the TV; used when the provider changes. */
const PROVIDER_DEFAULTS: Record<string, { baseUrl: string; model: string }> = {
  openai: { baseUrl: "https://api.openai.com/v1", model: "gpt-4o-mini" },
  gemini: { baseUrl: "https://generativelanguage.googleapis.com/v1beta", model: "gemini-2.0-flash" },
  anthropic: { baseUrl: "https://api.anthropic.com/v1", model: "claude-3-5-haiku-latest" },
};

export function SettingsPanel({
  remote,
  onClose,
  onToast,
  onOpenBluetooth,
  onOpenSideload,
  onUnpaired,
}: {
  remote: RemoteState;
  onClose: () => void;
  onToast: (message: string, level?: "info" | "warn" | "error") => void;
  onOpenBluetooth: () => void;
  onOpenSideload: () => void;
  onUnpaired: () => void;
}) {
  const { api, settings, engine, tv, setSettings } = remote;
  const [draft, setDraft] = useState<Settings | null>(settings);
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<DiagnosticsReport | null>(null);
  const [checking, setChecking] = useState(false);
  const [adblock, setAdblock] = useState<AdBlockState | null>(null);
  const [adBusy, setAdBusy] = useState(false);
  const [adHost, setAdHost] = useState("");
  const [adCustom, setAdCustom] = useState(false);

  useEffect(() => {
    setDraft(settings);
  }, [settings]);

  // Run the compatibility check as soon as the panel opens: it is the answer to
  // "why can this TV not be controlled", and it needs no adb to produce.
  useEffect(() => {
    if (!api) return;
    let cancelled = false;
    setChecking(true);
    api
      .diagnostics()
      .then((next) => {
        if (!cancelled) setReport(next);
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setChecking(false);
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  // Ad blocking is a setting on the TV itself, so it is read (not remembered) when
  // the panel opens. No probe here: that costs a DNS round trip and the panel wants
  // to open fast - "Check now" and every change run one.
  useEffect(() => {
    if (!api) return;
    let cancelled = false;
    api
      .adblock()
      .then((next) => {
        if (!cancelled) adoptAdBlock(next);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [api]);

  /** Follows the TV's answer: the resolver that is really in force, and whether it was ours. */
  function adoptAdBlock(next: AdBlockState, wanted?: boolean) {
    setAdblock(next);
    setAdHost(next.chosenHostname);
    setAdCustom(!next.presets.some((preset) => preset.hostname === next.chosenHostname));
    if (next.note) onToast(next.note, next.enabled === (wanted ?? next.enabled) ? "info" : "warn");
  }

  async function applyAdBlock(enabled: boolean, hostname?: string) {
    if (!api) return;
    setAdBusy(true);
    try {
      adoptAdBlock(await api.setAdblock(enabled, hostname), enabled);
    } catch (error) {
      onToast(error instanceof Error ? error.message : "Could not change ad blocking", "error");
    } finally {
      setAdBusy(false);
    }
  }

  async function checkAdBlock() {
    if (!api) return;
    setAdBusy(true);
    try {
      const next = await api.adblock(true);
      setAdblock(next);
      onToast(
        next.lastCheck?.note ?? "Checked",
        next.lastCheck && (!next.lastCheck.normalResolves || !next.lastCheck.adBlocked) ? "warn" : "info",
      );
    } catch (error) {
      onToast(error instanceof Error ? error.message : "Could not check the resolver", "error");
    } finally {
      setAdBusy(false);
    }
  }

  if (!draft) {
    return (
      <Sheet title="Settings" onClose={onClose}>
        <p className="legend">Loading…</p>
      </Sheet>
    );
  }

  const patch = (updates: Partial<Settings>) => setDraft({ ...draft, ...updates });

  async function save() {
    if (!api || !draft) return;
    setBusy(true);
    try {
      const llm: Record<string, unknown> = {
        provider: draft.llm.provider,
        baseUrl: draft.llm.baseUrl,
        model: draft.llm.model,
      };
      if (apiKey.trim()) llm.apiKey = apiKey.trim();

      const updated = await api.updateSettings({
        deviceName: draft.deviceName,
        port: draft.port,
        autoStart: draft.autoStart,
        llm,
        assistant: {
          mode: draft.assistant.mode,
          maxSteps: draft.assistant.maxSteps,
          stepDelayMs: draft.assistant.stepDelayMs,
        },
      });
      setSettings(updated);
      setApiKey("");
      onToast("Settings saved", "info");
    } catch (error) {
      onToast(error instanceof Error ? error.message : "Could not save", "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet title="Settings" onClose={onClose}>
      <div className="rowsplit">
        <StatusLine
          label="Accessibility engine"
          value={engine?.accessibilityConnected ? "Ready" : "Off"}
          tone={engine?.accessibilityConnected ? "ok" : "warn"}
        />
        <StatusLine label="TV" value={tv?.name ?? "—"} sub={`v${tv?.version ?? "?"} · port ${tv?.port ?? "?"}`} />
      </div>

      <section className="card card--tight">
        <p className="card__label">Can this TV be controlled?</p>
        {report ? (
          <>
            <p className="legend">{report.verdict}</p>
            {report.actions.length > 0 ? (
              <ol className="legend legend--list">
                {report.actions.map((action) => (
                  <li key={action}>{action}</li>
                ))}
              </ol>
            ) : null}
            <div className="rowsplit">
              <StatusLine
                label="Android"
                value={`${report.androidRelease} · SDK ${report.sdk}${report.buildType ? ` · ${report.buildType}` : ""}`}
              />
              <StatusLine label="Model" value={report.model} sub={report.fingerprint} />
              <StatusLine label="Installed by" value={report.installer ?? "unknown (sideloaded)"} />
              <StatusLine
                label="Play Store / services"
                value={`${report.playStoreInstalled ? "yes" : "no"} / ${report.playServicesInstalled ? "yes" : "no"}`}
              />
              <StatusLine
                label="Google remote service"
                value={report.googleRemoteServiceInstalled ? "installed" : "not found"}
                sub={
                  report.remoteServicePortsOpen.length > 0
                    ? `listening on ${report.remoteServicePortsOpen.join(", ")}`
                    : "no ports answering"
                }
              />
              <StatusLine
                label="Platform test keys"
                value={report.platformSignedWithTestKeys ? "yes - system privileges possible" : "no"}
                sub={report.appSignedAsPlatform ? "this app is signed as platform" : undefined}
                tone={report.platformSignedWithTestKeys ? "ok" : undefined}
              />
              <StatusLine label="ADB debugging" value={report.adbDebuggingEnabled ? "on" : "off"} />
              <StatusLine
                label="Accessibility blocked"
                value={report.restrictedSettings ? "yes - install source" : "no"}
                tone={report.restrictedSettings ? "warn" : "ok"}
              />
              <StatusLine
                label="Engine in Android's list"
                value={report.serviceListedAsEnabled ? "yes" : "no"}
                sub={report.accessibilityConnected ? "and running" : "not running"}
              />
              <StatusLine
                label="App can write system settings"
                value={report.writeSecureSettingsGranted ? "granted" : "not granted"}
              />
            </div>
          </>
        ) : (
          <p className="legend">{checking ? "Checking the TV…" : "No report yet."}</p>
        )}
      </section>

      <section className="card card--tight">
        <div className="card__head">
          <p className="card__label">WiFi Sideload & File Dropper</p>
          <span className="badge badge--ok">WiFi Direct</span>
        </div>
        <p className="legend">
          Upload and install APKs or transfer media files to your TV without cables or USB drives.
        </p>
        <div style={{ marginTop: 10 }}>
          <button
            type="button"
            className="btn btn--primary"
            style={{ width: "100%", display: "flex", alignItems: "center", justifyContent: "center", gap: 8 }}
            onClick={() => {
              onClose();
              onOpenSideload();
            }}
          >
            <IconUpload size={18} /> Open Sideload & File Dropper
          </button>
        </div>
      </section>

      <section className="card card--tight">
        <div className="card__head">
          <p className="card__label">Bluetooth Accessories</p>
          <span className="badge">Wireless</span>
        </div>
        <p className="legend">
          Manage paired Bluetooth audio, gamepads, keyboards, and scan for new nearby devices.
        </p>
        <div style={{ marginTop: 10 }}>
          <button
            type="button"
            className="btn btn--primary"
            style={{ width: "100%", display: "flex", alignItems: "center", justifyContent: "center", gap: 8 }}
            onClick={() => {
              onClose();
              onOpenBluetooth();
            }}
          >
            <IconBluetooth size={18} /> Manage Bluetooth Devices
          </button>
        </div>
      </section>

      <RootCard api={api} onToast={onToast} />

      <section className="card card--tight">
        <div className="card__head">
          <p className="card__label">Block ads on this TV</p>
          {adblock ? (
            <span className={`badge badge--${adblock.enabled ? (adblock.lastCheck && !adblock.lastCheck.adBlocked ? "warn" : "ok") : "off"}`}>
              {adblock.enabled ? "On" : "Off"}
            </span>
          ) : null}
        </div>
        {adblock ? (
          <>
            <p className="legend">{adblock.verdict}</p>
            {adblock.actions.length > 0 ? (
              <ol className="legend legend--list">
                {adblock.actions.map((action) => (
                  <li key={action}>{action}</li>
                ))}
              </ol>
            ) : null}
            <div className="rowsplit">
              <StatusLine
                label="Private DNS"
                value={adblock.enabled ? `on · ${adblock.hostname ?? "?"}` : "off · network DNS"}
                tone={adblock.enabled ? "ok" : undefined}
              />
              <StatusLine
                label="Last check"
                value={
                  adblock.lastCheck
                    ? !adblock.lastCheck.normalResolves
                      ? "DNS not answering"
                      : adblock.lastCheck.adBlocked
                        ? "ad domains blocked"
                        : "not blocking"
                    : "never checked"
                }
                sub={adblock.lastCheck ? new Date(adblock.lastCheck.at).toLocaleTimeString() : undefined}
                tone={adblock.lastCheck && adblock.enabled ? (adblock.lastCheck.adBlocked ? "ok" : "warn") : undefined}
              />
            </div>
            {adblock.enabled ? (
              <div className="rowsplit" style={{ marginTop: 8 }}>
                <button type="button" className="btn" disabled={adBusy} onClick={() => void checkAdBlock()}>
                  {adBusy ? "Checking…" : "Check now"}
                </button>
                <button type="button" className="btn btn--danger" disabled={adBusy || !adblock.canWrite} onClick={() => void applyAdBlock(false)}>
                  Turn off
                </button>
              </div>
            ) : (
              <>
                <div className="rowsplit" style={{ marginTop: 8 }}>
                  <select
                    className="select"
                    value={adCustom ? "custom" : adHost}
                    onChange={(event) => {
                      const value = event.target.value;
                      setAdCustom(value === "custom");
                      if (value !== "custom") setAdHost(value);
                    }}
                  >
                    {adblock.presets.map((preset) => (
                      <option key={preset.id} value={preset.hostname}>
                        {preset.label}
                      </option>
                    ))}
                    <option value="custom">Custom resolver…</option>
                  </select>
                  <button
                    type="button"
                    className="btn btn--primary"
                    disabled={adBusy || !adblock.canWrite || adHost.trim().length === 0}
                    onClick={() => void applyAdBlock(true, adHost.trim())}
                  >
                    {adBusy ? "Applying…" : "Turn on"}
                  </button>
                </div>
                {adCustom ? (
                  <div className="row">
                    <input
                      className="field"
                      value={adHost}
                      onChange={(event) => setAdHost(event.target.value)}
                      placeholder="dns.adguard-dns.com"
                      autoCapitalize="none"
                      spellCheck={false}
                    />
                  </div>
                ) : (
                  <p className="hint">{adblock.presets.find((preset) => preset.hostname === adHost)?.note ?? ""}</p>
                )}
              </>
            )}
            <p className="hint">
              Points the TV's Private DNS at a filtering resolver, so every app that uses the TV's DNS loses ad and
              tracker domains. YouTube's in-video ads are not affected - those come from the same servers as the video.
              Before switching, the app resolves the hostname and opens its DNS-over-TLS port; if ordinary names stop
              resolving afterwards, it puts the previous settings back on its own.
            </p>
          </>
        ) : (
          <p className="legend">Loading…</p>
        )}
      </section>

      <ProxyCard api={api} onToast={onToast} />

      <Labeled label="Assistant model" hint="Anything that speaks the OpenAI API also works, including a local model server.">
        <select
          className="select"
          value={draft.llm.provider}
          onChange={(event) => {
            const provider = event.target.value;
            const defaults = PROVIDER_DEFAULTS[provider];
            patch({
              llm: {
                ...draft.llm,
                provider,
                ...(defaults ?? {}),
                apiKeyConfigured: draft.llm.apiKeyConfigured,
              },
            });
          }}
        >
          {draft.llm.providers.map((provider) => (
            <option key={provider} value={provider}>
              {provider}
            </option>
          ))}
        </select>

        <div className="row">
          <input
            className="field"
            value={draft.llm.baseUrl}
            onChange={(event) => patch({ llm: { ...draft.llm, baseUrl: event.target.value } })}
            placeholder="https://api.openai.com/v1"
            autoCapitalize="none"
            spellCheck={false}
          />
        </div>
        <div className="row">
          <input
            className="field"
            value={draft.llm.model}
            onChange={(event) => patch({ llm: { ...draft.llm, model: event.target.value } })}
            placeholder="model name"
            autoCapitalize="none"
            spellCheck={false}
          />
        </div>
        <div className="row">
          <input
            className="field"
            type="password"
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            placeholder={draft.llm.apiKeyConfigured ? "API key stored - type to replace" : "API key"}
            autoCapitalize="none"
            spellCheck={false}
          />
        </div>
      </Labeled>

      <Labeled label="Assistant behaviour">
        <div className="rowsplit">
          <select
            className="select"
            value={draft.assistant.mode}
            onChange={(event) => patch({ assistant: { ...draft.assistant, mode: event.target.value } })}
          >
            <option value="plan">Plan (one shot)</option>
            <option value="agent">Agent (recovers from failures)</option>
          </select>
          <input
            className="field"
            type="number"
            min={1}
            max={40}
            value={draft.assistant.maxSteps}
            onChange={(event) => patch({ assistant: { ...draft.assistant, maxSteps: Number(event.target.value) } })}
          />
        </div>
        <p className="hint">Plan mode spends fewer tokens; agent mode re-reads the screen and corrects itself once.</p>
      </Labeled>

      <Labeled label="This TV">
        <input
          className="field"
          value={draft.deviceName}
          onChange={(event) => patch({ deviceName: event.target.value })}
        />
        <div className="rowsplit" style={{ marginTop: 8 }}>
          <input
            className="field"
            type="number"
            min={1024}
            max={65535}
            value={draft.port}
            onChange={(event) => patch({ port: Number(event.target.value) })}
          />
          <label className="switch card card--tight">
            <span>Start on boot</span>
            <input
              type="checkbox"
              checked={draft.autoStart}
              onChange={(event) => patch({ autoStart: event.target.checked })}
            />
          </label>
        </div>
      </Labeled>

      <button type="button" className="btn btn--primary btn--wide" disabled={busy} onClick={() => void save()}>
        {busy ? "Saving…" : "Save settings"}
      </button>

      <Labeled label="Security">
        <div className="rowsplit">
          <button
            type="button"
            className="btn"
            onClick={() =>
              api?.rotatePin().then((updated) => {
                setSettings(updated);
                onToast(`New PIN: ${updated.pin}`, "warn");
              })
            }
          >
            New PIN
          </button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => {
              void api?.revokeDevices();
              onToast("All paired devices were signed out", "warn");
              onUnpaired();
            }}
          >
            Sign out everything
          </button>
        </div>
        <p className="hint">Paired devices: {draft.devices.length ? draft.devices.join(", ") : "none"}</p>
      </Labeled>
    </Sheet>
  );
}

export default SettingsPanel;
