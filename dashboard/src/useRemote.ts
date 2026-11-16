import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Api,
  ApiError,
  RemoteSocket,
  type AppEntry,
  type AssistantRun,
  type EngineState,
  type Envelope,
  type KeyName,
  type NowPlayingInfo,
  type ScreenSnapshot,
  type Session,
  type Settings,
  type SocketStatus,
  type TextResult,
} from "./api";
import type { ToastLevel } from "./components/ui";

interface TvInfo {
  name: string;
  version: string;
  port: number;
}

export interface RemoteState {
  api: Api | null;
  status: SocketStatus;
  tv: TvInfo | null;
  screen: ScreenSnapshot | null;
  engine: EngineState | null;
  settings: Settings | null;
  apps: AppEntry[];
  run: AssistantRun | null;
  alive: boolean;
  /** The TV is declining accessibility because of the Android 13+ install-source rule. */
  restrictedSettings: boolean;
  pressKey: (key: KeyName, repeat?: number) => void;
  typeText: (text: string, mode: "replace" | "append" | "clear" | "enter", submit: boolean) => Promise<TextResult | null>;
  refreshSettings: () => void;
  refreshApps: (force?: boolean) => Promise<void>;
  setSettings: (settings: Settings) => void;
  setRun: (run: AssistantRun | null) => void;
  clearRun: () => void;
  sendSocket: (type: string, data?: unknown) => void;
  nowPlaying: NowPlayingInfo | null;
  refreshNowPlaying: () => Promise<void>;
  controlMedia: (action: string, positionMs?: number) => Promise<boolean>;
}

/**
 * Holds everything the dashboard knows about the TV: the socket, the last screen
 * snapshot, the engine state, apps and the current assistant run.
 */
export function useRemote(
  session: Session | null,
  notify: (message: string, level?: ToastLevel) => void,
  onUnauthorized: () => void,
): RemoteState {
  const [status, setStatus] = useState<SocketStatus>("closed");
  const [tv, setTv] = useState<TvInfo | null>(null);
  const [screen, setScreen] = useState<ScreenSnapshot | null>(null);
  const [engine, setEngine] = useState<EngineState | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [apps, setApps] = useState<AppEntry[]>([]);
  const [run, setRun] = useState<AssistantRun | null>(null);
  const [alive, setAlive] = useState(false);
  const [restrictedSettings, setRestrictedSettings] = useState(false);
  const [nowPlaying, setNowPlaying] = useState<NowPlayingInfo | null>(null);

  const notifyRef = useRef(notify);
  notifyRef.current = notify;
  const unauthorizedRef = useRef(onUnauthorized);
  unauthorizedRef.current = onUnauthorized;

  const host = session?.host ?? "";
  const token = session?.token ?? "";

  const api = useMemo(() => (session ? new Api(host, token) : null), [session, host, token]);

  const handleEnvelope = useCallback((envelope: Envelope<any>) => {
    switch (envelope.type) {
      case "hello":
        setTv((current) => ({
          name: envelope.data?.name ?? current?.name ?? "Android TV",
          version: envelope.data?.version ?? current?.version ?? "",
          port: envelope.data?.port ?? current?.port ?? 0,
        }));
        setAlive(true);
        break;

      case "now_playing":
        setNowPlaying(envelope.data as NowPlayingInfo);
        break;

      case "engine.state":
        setEngine((current) => ({
          accessibilityConnected: Boolean(envelope.data?.connected),
          canNavigate: Boolean(envelope.data?.connected),
          canType: Boolean(envelope.data?.connected),
          canVolume: true,
          note: current?.note ?? null,
        }));
        break;

      case "screen.update":
      case "input.field":
        setScreen(envelope.data as ScreenSnapshot);
        break;

      case "app.changed":
        setScreen((current) => ({
          ...(current ?? {}),
          pkg: envelope.data?.pkg ?? current?.pkg ?? null,
          app: envelope.data?.app ?? current?.app ?? null,
        }));
        break;

      case "assistant.run":
        setRun(envelope.data as AssistantRun);
        break;

      case "key.result": {
        const result = envelope.data?.result;
        if (result && !result.ok) {
          notifyRef.current(result.note ?? "The TV refused that key", "warn");
        }
        break;
      }

      case "text.result": {
        const result = envelope.data as TextResult;
        if (!result.ok) notifyRef.current(result.note ?? "Could not type on the TV", "warn");
        else if (result.submitted) notifyRef.current("Typed and sent", "info");
        break;
      }

      case "settings.changed":
        break;

      case "settings.note":
        notifyRef.current(String(envelope.data?.message ?? ""), "warn");
        break;

      default:
        break;
    }
  }, []);

  const socket = useMemo(
    () =>
      session
        ? new RemoteSocket(host, token, {
            onEvent: handleEnvelope,
            onStatus: setStatus,
          })
        : null,
    [session, host, token, handleEnvelope],
  );

  // Connect once per session and load the initial state.
  useEffect(() => {
    if (!socket || !api) return;
    socket.connect();

    let cancelled = false;
    (async () => {
      try {
        const info = await api.info();
        if (cancelled) return;
        setSettings(info.settings);
        setEngine(info.engine);
        setScreen(info.screen ?? null);
        setRestrictedSettings(info.restrictedSettings === true);
        setTv({ name: info.name, version: info.version, port: info.port });
        const list = await api.apps();
        if (!cancelled) setApps(list.apps);
        api.nowPlaying().then((np) => {
          if (!cancelled && np) setNowPlaying(np);
        }).catch(() => undefined);
      } catch (error) {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 401) {
          unauthorizedRef.current();
        } else {
          notifyRef.current(error instanceof Error ? error.message : "The TV is not reachable", "error");
        }
      }
    })();

    setAlive(true);
    return () => {
      cancelled = true;
      socket.close();
    };
  }, [socket, api]);

  const pressKey = useCallback(
    (key: KeyName, repeat = 1) => {
      if (navigator.vibrate) navigator.vibrate(8);
      if (socket && status === "open") {
        socket.send("key", { key, repeat });
        return;
      }
      api?.key(key, repeat).catch((error: unknown) => {
        notifyRef.current(error instanceof Error ? error.message : "Key failed", "warn");
      });
    },
    [api, socket, status],
  );

  const typeText = useCallback(
    async (text: string, mode: "replace" | "append" | "clear" | "enter", submit: boolean) => {
      if (!api) return null;
      try {
        const result = await api.text(text, mode, submit);
        if (!result.ok) notifyRef.current(result.note ?? "The TV did not accept the text", "warn");
        return result;
      } catch (error) {
        notifyRef.current(error instanceof Error ? error.message : "Typing failed", "error");
        return null;
      }
    },
    [api],
  );

  const refreshSettings = useCallback(() => {
    api?.settings().then(setSettings).catch(() => undefined);
  }, [api]);

  const refreshApps = useCallback(
    async (force = true) => {
      if (!api) return;
      try {
        const res = await api.apps(force);
        setApps(res.apps);
      } catch {
        // ignore
      }
    },
    [api],
  );

  const refreshNowPlaying = useCallback(async () => {
    if (!api) return;
    try {
      const res = await api.nowPlaying();
      if (res) setNowPlaying(res);
    } catch {
      // ignore
    }
  }, [api]);

  const controlMedia = useCallback(
    async (action: string, positionMs?: number): Promise<boolean> => {
      if (!api) return false;
      try {
        const res = await api.controlMedia(action, positionMs);
        if (res.nowPlaying) setNowPlaying(res.nowPlaying);
        return res.ok;
      } catch (e) {
        notifyRef.current(e instanceof Error ? e.message : "Media control failed", "warn");
        return false;
      }
    },
    [api],
  );

  const clearRun = useCallback(() => setRun(null), []);

  const sendSocket = useCallback(
    (type: string, data?: unknown) => {
      socket?.send(type, data);
    },
    [socket],
  );

  return {
    api,
    status,
    tv,
    screen,
    engine,
    settings,
    apps,
    run,
    alive,
    restrictedSettings,
    pressKey,
    typeText,
    refreshSettings,
    refreshApps,
    setSettings,
    setRun,
    clearRun,
    sendSocket,
    nowPlaying,
    refreshNowPlaying,
    controlMedia,
  };
}
