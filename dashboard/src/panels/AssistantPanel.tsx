import { useState } from "react";
import { IconMic } from "../components/icons";
import { Sheet } from "../components/ui";
import { useVoiceInput } from "../hooks/useVoiceInput";
import type { RemoteState } from "../useRemote";

const QUICK_ASKS = [
  "Open YouTube",
  "Search YouTube for the latest movie trailers",
  "Turn the volume down",
  "Go back to the home screen",
  "What is on the screen right now?",
];

const STATUS_LABEL: Record<string, string> = {
  running: "Working…",
  done: "Done",
  partial: "Partly done",
  failed: "Failed",
  cancelled: "Cancelled",
  needs_input: "Needs your answer",
};

export function AssistantPanel({
  remote,
  onClose,
  onOpenSettings,
}: {
  remote: RemoteState;
  onClose: () => void;
  onOpenSettings: () => void;
}) {
  const { api, run, settings, screen, setRun } = remote;
  const [utterance, setUtterance] = useState("");
  const [busy, setBusy] = useState(false);
  const [dryRun, setDryRun] = useState(false);

  const llm = settings?.llm;
  const localModel = llm ? /localhost|127\.0\.0\.1|192\.168\.|http:\/\/10\./.test(llm.baseUrl) : false;
  const configured = Boolean(llm && (llm.apiKeyConfigured || localModel));
  const running = run?.status === "running";

  async function send(text: string) {
    if (!api || !text.trim()) return;
    setBusy(true);
    try {
      await api.assist(text.trim(), { useScreen: true, dryRun });
      setUtterance("");
    } catch (error) {
      setRun({
        id: "local-error",
        utterance: text,
        status: "failed",
        steps: [],
        error: error instanceof Error ? error.message : "The assistant could not start",
        startedAt: Date.now(),
      });
    } finally {
      setBusy(false);
    }
  }

  const voice = useVoiceInput((transcript) => {
    setUtterance(transcript);
  });

  return (
    <Sheet title="Assistant" onClose={onClose}>
      {!configured ? (
        <div className="notice notice--warn">
          No model is configured yet. Add a provider, model and API key in Settings - or point the base
          URL at a model server on your own network.
          <div className="row">
            <button type="button" className="btn" onClick={onOpenSettings}>
              Open settings
            </button>
          </div>
        </div>
      ) : null}

      <div className="card card--tight">
        <p className="card__label">The TV right now</p>
        <p className="card__sub">
          {screen?.app ?? "unknown app"}
          {screen?.focus?.text ? ` · selected: ${screen.focus.text}` : ""}
          {screen?.field?.hint ? ` · field: ${screen.field.hint}` : ""}
        </p>
      </div>

      <div>
        <div style={{ position: "relative" }}>
          <textarea
            className="field"
            rows={3}
            value={utterance}
            onChange={(event) => setUtterance(event.target.value)}
            placeholder="e.g. open Netflix and search for Dune (or tap the mic to speak)"
          />
        </div>
        <div className="row">
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy || running || !utterance.trim() || !configured}
            onClick={() => void send(utterance)}
          >
            {running ? "Running…" : dryRun ? "Preview plan" : "Run"}
          </button>
          {voice.supported ? (
            <button
              type="button"
              className={`btn btn--icon${voice.isListening ? " btn--active btn--pulsing" : ""}`}
              onClick={voice.toggle}
              title={voice.isListening ? "Listening… tap to stop" : "Speak with microphone"}
              aria-label="Voice input"
            >
              <IconMic active={voice.isListening} />
            </button>
          ) : null}
          {running ? (
            <button type="button" className="btn btn--danger" onClick={() => void api?.cancelAssistant()}>
              Stop
            </button>
          ) : null}
          <label className="switch" style={{ marginLeft: "auto" }}>
            <span className="legend">Preview only</span>
            <input type="checkbox" checked={dryRun} onChange={(event) => setDryRun(event.target.checked)} />
          </label>
        </div>
      </div>

      <div className="scroller">
        {QUICK_ASKS.map((ask) => (
          <button key={ask} type="button" className="app-chip" onClick={() => void send(ask)} disabled={!configured || running}>
            {ask}
          </button>
        ))}
      </div>

      {run ? (
        <div className="card">
          <p className="card__label">
            {STATUS_LABEL[run.status] ?? run.status} · “{run.utterance}”
          </p>
          {run.say ? <div className="bubble bubble--say" style={{ marginTop: 6 }}>{run.say}</div> : null}
          {run.error ? (
            <div className="bubble bubble--error" style={{ marginTop: 8 }}>
              {run.error}
            </div>
          ) : null}
          {run.steps.length > 0 ? (
            <div className="trace" style={{ marginTop: 10 }}>
              {run.steps.map((step, index) => (
                <div key={`${step.index}-${index}`} className={`trace__step trace__step--${step.status}`}>
                  <span className="trace__index">{step.index}</span>
                  <span>
                    {step.action}
                    {step.detail ? ` · ${step.detail}` : ""}
                    {step.note ? <span className="trace__note">{step.note}</span> : null}
                  </span>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      ) : (
        <p className="legend">
          The assistant reads the screen, plans a few button presses and performs them. It can open apps,
          navigate with the D-pad, type into search boxes and control playback. It never does anything you
          could not do with the remote.
        </p>
      )}
    </Sheet>
  );
}

export default AssistantPanel;
