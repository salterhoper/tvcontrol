import { useCallback, useEffect, useMemo, useState } from "react";
import { clearSession, loadSession, saveSession, type Session } from "./api";
import { Toasts, useToasts } from "./components/ui";
import AssistantPanel from "./panels/AssistantPanel";
import { BluetoothPanel } from "./panels/BluetoothPanel";
import { SideloadPanel } from "./panels/SideloadPanel";
import SettingsPanel from "./panels/SettingsPanel";
import PairScreen from "./screens/PairScreen";
import RemoteScreen from "./screens/RemoteScreen";
import { useRemote } from "./useRemote";

/** The QR code on the TV links to #/pair?pin=123456 so scanning pairs in one tap. */
function pinFromHash(): string {
  const hash = window.location.hash;
  const questionMark = hash.indexOf("?");
  if (questionMark < 0) return "";
  const params = new URLSearchParams(hash.slice(questionMark + 1));
  return params.get("pin") ?? "";
}

export default function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [panel, setPanel] = useState<"assistant" | "settings" | "bluetooth" | "sideload" | null>(null);
  const { toasts, push } = useToasts();
  const initialPin = useMemo(pinFromHash, []);

  const handleUnauthorized = useCallback(() => {
    clearSession();
    setSession(null);
    push("That device was signed out. Pair again with the PIN on the TV.", "warn");
  }, [push]);

  const remote = useRemote(session, push, handleUnauthorized);

  const onPaired = useCallback(
    (next: Session) => {
      saveSession(next);
      setSession(next);
      if (window.location.hash) window.history.replaceState(null, "", window.location.pathname);
      push(`Paired with ${next.name}`, "info");
    },
    [push],
  );

  // Announce finished assistant runs even when the panel is closed.
  const run = remote.run;
  const setRun = remote.setRun;
  useEffect(() => {
    if (!run || run.status === "running" || run.status === "planned") return;
    if (panel === "assistant") return;
    const summary = run.say ?? run.error ?? run.status;
    push(`Assistant: ${summary}`, run.status === "failed" ? "error" : "info");
    setRun(null);
  }, [run, panel, push, setRun]);

  if (!session) {
    return (
      <>
        <PairScreen initialPin={initialPin} onPaired={onPaired} />
        <Toasts toasts={toasts} />
      </>
    );
  }

  return (
    <>
      <RemoteScreen
        remote={remote}
        onOpenAssistant={() => setPanel("assistant")}
        onOpenSettings={() => setPanel("settings")}
        onOpenBluetooth={() => setPanel("bluetooth")}
        onOpenSideload={() => setPanel("sideload")}
        onToast={push}
        sheetOpen={panel !== null}
      />

      {panel === "assistant" ? (
        <AssistantPanel
          remote={remote}
          onClose={() => setPanel(null)}
          onOpenSettings={() => setPanel("settings")}
        />
      ) : null}

      {panel === "settings" ? (
        <SettingsPanel
          remote={remote}
          onClose={() => setPanel(null)}
          onToast={push}
          onOpenBluetooth={() => setPanel("bluetooth")}
          onOpenSideload={() => setPanel("sideload")}
          onUnpaired={() => {
            clearSession();
            setSession(null);
            setPanel(null);
          }}
        />
      ) : null}

      {panel === "bluetooth" ? (
        <BluetoothPanel
          remote={remote}
          onClose={() => setPanel(null)}
          onToast={push}
        />
      ) : null}

      {panel === "sideload" ? (
        <SideloadPanel
          remote={remote}
          onClose={() => setPanel(null)}
          onToast={push}
        />
      ) : null}

      <Toasts toasts={toasts} />
    </>
  );
}
