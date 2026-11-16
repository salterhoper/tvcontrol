/**
 * The single source of truth for how the dashboard talks to the TV.
 * The wire contract is documented in docs/API.md - keep the two in step.
 */

export const KEY_NAMES = [
  "up",
  "down",
  "left",
  "right",
  "ok",
  "back",
  "home",
  "recents",
  "notifications",
  "quick_settings",
  "scroll_up",
  "scroll_down",
  "scroll_left",
  "scroll_right",
  "volume_up",
  "volume_down",
  "mute",
  "play_pause",
  "play",
  "pause",
  "next",
  "previous",
  "stop",
  "rewind",
  "fast_forward",
] as const;

export type KeyName = (typeof KEY_NAMES)[number];

export interface NodeSummary {
  text?: string | null;
  desc?: string | null;
  cls?: string | null;
  focused?: boolean;
  focusable?: boolean;
  clickable?: boolean;
  editable?: boolean;
  scrollable?: boolean;
  enabled?: boolean;
  bounds?: string | null;
  viewId?: string | null;
}

export interface FieldInfo {
  text?: string | null;
  hint?: string | null;
  pkg?: string | null;
  canSubmit?: boolean;
}

export interface ScreenSnapshot {
  pkg?: string | null;
  app?: string | null;
  activity?: string | null;
  focus?: NodeSummary | null;
  field?: FieldInfo | null;
  labels?: string[];
  scrollable?: boolean;
  imeVisible?: boolean;
  takenAt?: number;
}

export interface EngineState {
  accessibilityConnected: boolean;
  canNavigate: boolean;
  canType: boolean;
  canVolume: boolean;
  note?: string | null;
}

export interface ActionResult {
  ok: boolean;
  via?: string | null;
  moved?: boolean | null;
  note?: string | null;
}

export interface TextResult extends ActionResult {
  submitted?: boolean | null;
  target?: FieldInfo | null;
}

export interface VolumeResult {
  ok: boolean;
  levelPercent?: number | null;
  muted?: boolean | null;
  note?: string | null;
}

export interface LaunchOutcome {
  ok: boolean;
  pkg?: string | null;
  label?: string | null;
  note?: string | null;
}

export interface AppEntry {
  pkg: string;
  label: string;
  leanback: boolean;
}

export interface LlmSettings {
  provider: string;
  baseUrl: string;
  model: string;
  apiKeyConfigured: boolean;
  providers: string[];
}

export interface AssistantSettings {
  mode: string;
  maxSteps: number;
  stepDelayMs: number;
}

export interface Settings {
  port: number;
  deviceName: string;
  autoStart: boolean;
  pin: string;
  devices: string[];
  llm: LlmSettings;
  assistant: AssistantSettings;
}

export interface InfoResponse {
  name: string;
  version: string;
  port: number;
  deviceModel: string;
  androidVersion: string;
  engine: EngineState;
  volume: VolumeResult;
  screen?: ScreenSnapshot | null;
  settings: Settings;
  serverTime: number;
  /** True when Android 13+ is blocking accessibility because of the install source. */
  restrictedSettings?: boolean;
}

/** Result of GET /api/diagnostics: what this TV can actually be driven through. */
export interface DiagnosticsReport {
  androidRelease: string;
  sdk: number;
  model: string;
  fingerprint: string;
  buildType?: string;
  installer?: string | null;
  playStoreInstalled: boolean;
  playServicesInstalled: boolean;
  googleRemoteServiceInstalled: boolean;
  remoteServicePortsOpen: number[];
  remoteServicePortsOpenOnLan: number[];
  adbDebuggingEnabled: boolean;
  developmentSettingsEnabled: boolean;
  restrictedSettings: boolean;
  accessibilityConnected: boolean;
  enabledAccessibilityServices?: string | null;
  serviceListedAsEnabled?: boolean;
  writeSecureSettingsGranted?: boolean;
  platformSignedWithTestKeys?: boolean;
  appSignedAsPlatform?: boolean;
  verdict: string;
  actions: string[];
}

/** Result of POST /api/engine/enable. */
export interface EnableResult {
  ok: boolean;
  hasPermission: boolean;
  message: string;
}

export interface AdBlockPreset {
  id: string;
  label: string;
  hostname: string;
  note: string;
}

/** Outcome of one live probe of the TV's DNS: ordinary names resolve, ad names sink. */
export interface AdBlockCheck {
  at: number;
  normalResolves: boolean;
  adBlocked: boolean;
  note: string;
}

/** Result of GET|POST /api/adblock - the TV's Private DNS state, not a remembered flag. */
export interface AdBlockState {
  enabled: boolean;
  hostname?: string | null;
  mode?: string | null;
  canWrite: boolean;
  chosenHostname: string;
  previousHostname?: string | null;
  presets: AdBlockPreset[];
  lastCheck?: AdBlockCheck | null;
  note?: string | null;
  verdict: string;
  actions: string[];
}

/** One saved SOCKS5 server, as the TV reports it (passwords never come back). */
export interface ProxyServer {
  id: string;
  name: string;
  host: string;
  port: number;
  username?: string | null;
  hasPassword: boolean;
  active: boolean;
  lastTest?: ProxyTest | null;
}

export interface ProxyTest {
  ok: boolean;
  at: number;
  latencyMs?: number | null;
  /** The address websites see through this server. */
  exitIp?: string | null;
  message: string;
}

export interface ProxyStats {
  since: number;
  activeConnections: number;
  totalConnections: number;
  bytesUp: number;
  bytesDown: number;
  dnsQueries: number;
}

/** Result of every /api/proxy call: saved servers and whether the TV is routed through one. */
export interface ProxyState {
  enabled: boolean;
  running: boolean;
  paused: boolean;
  consentNeeded: boolean;
  failOpen: boolean;
  activeId?: string | null;
  servers: ProxyServer[];
  stats?: ProxyStats | null;
  note?: string | null;
  verdict: string;
  actions: string[];
}

export interface ProxyServerInput {
  id?: string;
  name: string;
  host: string;
  port: number;
  username?: string;
  /** Omit to keep the stored password, "" to clear it. */
  password?: string;
}

export interface RootCheckItem {
  id: string;
  label: string;
  /** true = a sign of root, false = absent, null = could not be determined. */
  found?: boolean | null;
  detail: string;
}

/** Result of GET /api/root: is the TV rooted, and can an app use it. */
export interface RootReport {
  level: "app" | "shell" | "weak" | "none";
  rooted: boolean;
  appCanUseRoot: boolean;
  headline: string;
  verdict: string;
  checks: RootCheckItem[];
  actions: string[];
  checkedAt: number;
}

export interface AssistantStep {
  index: number;
  action: string;
  detail?: string | null;
  status: string;
  note?: string | null;
}

export interface AssistantRun {
  id: string;
  utterance: string;
  status: string;
  say?: string | null;
  steps: AssistantStep[];
  error?: string | null;
  startedAt: number;
  finishedAt?: number | null;
}

export interface Session {
  host: string;
  token: string;
  name: string;
}

export interface Envelope<D = unknown> {
  v: number;
  type: string;
  ts: number;
  data?: D;
}

export interface BluetoothDevice {
  name: string;
  address: string;
  bonded: boolean;
  connected: boolean;
  deviceType: "audio" | "input" | "phone" | "computer" | "wearable" | "other";
  majorClass?: number;
  rssi?: number | null;
  bondState: "bonded" | "bonding" | "none";
}

export interface BluetoothStatus {
  supported: boolean;
  enabled: boolean;
  scanning: boolean;
  name?: string | null;
  address?: string | null;
  pairedDevices: BluetoothDevice[];
  discoveredDevices: BluetoothDevice[];
}

export interface BluetoothActionResult {
  ok: boolean;
  message?: string | null;
  error?: string | null;
}

export interface FileItem {
  name: string;
  size: number;
  lastModified: number;
  isApk: boolean;
  extension: string;
  formattedSize: string;
  packageName?: string | null;
  appName?: string | null;
  appVersion?: string | null;
  path: string;
}

export interface StorageInfo {
  totalBytes: number;
  freeBytes: number;
  usableBytes: number;
  formattedFree: string;
  formattedTotal: string;
  freePercent: number;
  storagePath: string;
  files: FileItem[];
}

export interface FileActionResult {
  ok: boolean;
  message?: string | null;
  error?: string | null;
  file?: FileItem | null;
}

export interface NowPlayingInfo {
  active: boolean;
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  packageName?: string | null;
  appName?: string | null;
  durationMs: number;
  positionMs: number;
  playbackSpeed: number;
  isPlaying: boolean;
  playbackState: string;
  canSeek: boolean;
  canPlayPause: boolean;
  canSkipNext: boolean;
  canSkipPrev: boolean;
  hasArtwork: boolean;
  artworkBase64?: string | null;
  lastUpdated: number;
}

export interface MediaControlResult {
  ok: boolean;
  action: string;
  message?: string | null;
  error?: string | null;
  nowPlaying?: NowPlayingInfo | null;
}

const STORAGE_KEY = "tvcontrol.session";
const MANUAL_HOST_KEY = "tvcontrol.host";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

// ---------------------------------------------------------------- session state

export function normaliseHost(raw: string): string {
  return raw
    .trim()
    .replace(/^https?:\/\//i, "")
    .replace(/\/+$/, "")
    .replace(/\/#.*$/, "");
}

/** Default host is the TV we were served from; a dev server needs one typed in. */
export function defaultHost(): string {
  const stored = localStorage.getItem(MANUAL_HOST_KEY);
  const fromQuery = new URLSearchParams(window.location.search).get("host");
  if (fromQuery) return normaliseHost(fromQuery);

  const servedByTv = window.location.port !== "5173" && window.location.protocol.startsWith("http");
  if (servedByTv) return window.location.host;
  return stored ? normaliseHost(stored) : "";
}

export function rememberHost(host: string): void {
  localStorage.setItem(MANUAL_HOST_KEY, normaliseHost(host));
}

export function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Session;
    if (!parsed.host || !parsed.token) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  localStorage.removeItem(STORAGE_KEY);
}

// --------------------------------------------------------------------- rest api

export interface PingResponse {
  ok: boolean;
  name: string;
  version: string;
  requiresPairing: boolean;
}

export async function ping(host: string): Promise<PingResponse> {
  const res = await fetch(`http://${normaliseHost(host)}/api/ping`);
  if (!res.ok) throw new ApiError("The TV did not answer", res.status);
  return (await res.json()) as PingResponse;
}

export async function pair(host: string, pin: string, device: string): Promise<Session> {
  const cleanHost = normaliseHost(host);
  const res = await fetch(`http://${cleanHost}/api/pair`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pin, device }),
  });
  const payload = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(payload?.message ?? "Pairing failed", res.status, payload?.error);
  }
  return { host: cleanHost, token: payload.token as string, name: payload.name as string };
}

export class Api {
  constructor(
    private readonly host: string,
    private readonly token: string,
  ) {}

  get origin(): string {
    return `http://${this.host}`;
  }

  get baseUrl(): string {
    return this.origin;
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await fetch(`${this.origin}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${this.token}`,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    const payload = text ? safeParse(text) : null;
    if (!res.ok) {
      throw new ApiError(payload?.message ?? res.statusText, res.status, payload?.error);
    }
    return payload as T;
  }

  info(): Promise<InfoResponse> {
    return this.request("GET", "/api/info");
  }

  diagnostics(): Promise<DiagnosticsReport> {
    return this.request("GET", "/api/diagnostics");
  }

  enableEngine(): Promise<EnableResult> {
    return this.request("POST", "/api/engine/enable");
  }

  screen(): Promise<ScreenSnapshot> {
    return this.request("GET", "/api/screen");
  }

  apps(refresh = false): Promise<{ apps: AppEntry[] }> {
    return this.request("GET", `/api/apps${refresh ? "?refresh=1" : ""}`);
  }

  key(key: KeyName, repeat = 1): Promise<{ key: string; result: ActionResult }> {
    return this.request("POST", "/api/key", { key, repeat });
  }

  text(text: string, mode: "replace" | "append" | "clear" | "enter" | "backspace" = "replace", submit = false): Promise<TextResult> {
    return this.request("POST", "/api/text", { text, mode, submit });
  }

  launch(target: { package?: string; query?: string }): Promise<LaunchOutcome> {
    return this.request("POST", "/api/launch", target);
  }

  search(query: string, target: "auto" | "youtube" | "play" = "auto"): Promise<ActionResult> {
    return this.request("POST", "/api/search", { query, target });
  }

  volume(action: "up" | "down" | "mute" | "set", value?: number): Promise<VolumeResult> {
    return this.request("POST", "/api/volume", { action, value });
  }

  settings(): Promise<Settings> {
    return this.request("GET", "/api/settings");
  }

  updateSettings(patch: Record<string, unknown>): Promise<Settings> {
    return this.request("PUT", "/api/settings", patch);
  }

  rotatePin(): Promise<Settings> {
    return this.request("POST", "/api/settings/pin/rotate");
  }

  revokeDevices(): Promise<{ ok: boolean }> {
    return this.request("POST", "/api/settings/devices/revoke");
  }

  /** Private DNS ad blocking; `probe` adds a live DNS check (a couple of seconds). */
  adblock(probe = false): Promise<AdBlockState> {
    return this.request("GET", `/api/adblock${probe ? "?probe=1" : ""}`);
  }

  setAdblock(enabled: boolean, hostname?: string): Promise<AdBlockState> {
    return this.request("POST", "/api/adblock", { enabled, hostname });
  }

  /** SOCKS5 routing for the whole TV. */
  proxy(): Promise<ProxyState> {
    return this.request("GET", "/api/proxy");
  }

  setProxyEnabled(enabled: boolean, id?: string): Promise<ProxyState> {
    return this.request("POST", "/api/proxy", { enabled, id });
  }

  saveProxyServer(server: ProxyServerInput): Promise<ProxyState> {
    return this.request("POST", "/api/proxy/servers", server);
  }

  deleteProxyServer(id: string): Promise<ProxyState> {
    return this.request("DELETE", `/api/proxy/servers/${encodeURIComponent(id)}`);
  }

  testProxyServer(id: string): Promise<ProxyState> {
    return this.request("POST", `/api/proxy/servers/${encodeURIComponent(id)}/test`);
  }

  selectProxy(id: string): Promise<ProxyState> {
    return this.request("POST", "/api/proxy/active", { id });
  }

  setProxyOptions(options: { failOpen?: boolean }): Promise<ProxyState> {
    return this.request("POST", "/api/proxy/options", options);
  }

  rootCheck(): Promise<RootReport> {
    return this.request("GET", "/api/root");
  }

  assist(utterance: string, options?: { useScreen?: boolean; dryRun?: boolean }): Promise<{ runId: string }> {
    return this.request("POST", "/api/assistant", {
      utterance,
      useScreen: options?.useScreen ?? true,
      dryRun: options?.dryRun ?? false,
    });
  }

  cancelAssistant(): Promise<{ ok: boolean }> {
    return this.request("POST", "/api/assistant/cancel");
  }

  run(id: string): Promise<AssistantRun> {
    return this.request("GET", `/api/assistant/${id}`);
  }

  bluetooth(): Promise<BluetoothStatus> {
    return this.request("GET", "/api/bluetooth");
  }

  setBluetoothState(enabled: boolean): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/state", { enabled });
  }

  scanBluetooth(action: "start" | "stop" = "start"): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/scan", { action });
  }

  pairBluetooth(address: string): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/pair", { address });
  }

  connectBluetooth(address: string): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/connect", { address });
  }

  disconnectBluetooth(address: string): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/disconnect", { address });
  }

  unpairBluetooth(address: string): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/unpair", { address });
  }

  launchTvPairing(): Promise<BluetoothActionResult> {
    return this.request("POST", "/api/bluetooth/launch-pairing");
  }

  files(): Promise<StorageInfo> {
    return this.request("GET", "/api/files");
  }

  uploadFile(
    file: File,
    options?: {
      installImmediately?: boolean;
      onProgress?: (percent: number, loaded: number, total: number) => void;
    },
  ): Promise<FileActionResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const installParam = options?.installImmediately ? "?install=1" : "";
      xhr.open("POST", `${this.origin}/api/files/upload${installParam}`);
      if (this.token) {
        xhr.setRequestHeader("Authorization", `Bearer ${this.token}`);
      }

      if (options?.onProgress) {
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable) {
            const percent = Math.round((e.loaded / e.total) * 100);
            options.onProgress?.(percent, e.loaded, e.total);
          }
        };
      }

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            resolve(JSON.parse(xhr.responseText) as FileActionResult);
          } catch {
            resolve({ ok: true, message: "Upload complete" });
          }
        } else {
          try {
            const err = JSON.parse(xhr.responseText);
            reject(new Error(err.message || err.error || `Upload failed with status ${xhr.status}`));
          } catch {
            reject(new Error(`Upload failed with status ${xhr.status}`));
          }
        }
      };

      xhr.onerror = () => {
        reject(new Error("Network error during file upload"));
      };

      const formData = new FormData();
      formData.append("file", file, file.name);
      xhr.send(formData);
    });
  }

  installApk(name: string): Promise<FileActionResult> {
    return this.request("POST", "/api/files/install", { name });
  }

  deleteFile(name: string): Promise<{ ok: boolean }> {
    return this.request("DELETE", `/api/files?name=${encodeURIComponent(name)}`);
  }

  downloadFileUrl(name: string): string {
    return `${this.origin}/api/files/download?name=${encodeURIComponent(name)}`;
  }

  nowPlaying(): Promise<NowPlayingInfo> {
    return this.request("GET", "/api/media/now-playing");
  }

  controlMedia(action: string, positionMs?: number): Promise<MediaControlResult> {
    return this.request("POST", "/api/media/control", { action, positionMs });
  }

  mediaArtUrl(): string {
    const ts = Date.now();
    return `${this.origin}/api/media/art?t=${ts}${this.token ? `&token=${encodeURIComponent(this.token)}` : ""}`;
  }
}

function safeParse(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return { message: text.slice(0, 200) };
  }
}

// ------------------------------------------------------------------- websocket

export type SocketStatus = "connecting" | "open" | "closed";

export interface SocketHandlers {
  onEvent: (envelope: Envelope<any>) => void;
  onStatus: (status: SocketStatus) => void;
}

/**
 * Control channel. Button repeats are latency sensitive, so they skip REST and go
 * straight down this socket. Reconnects on its own with a small backoff; the TV
 * drops idle sockets when it goes to sleep, which is normal.
 */
export class RemoteSocket {
  private socket: WebSocket | null = null;
  private heartbeat: number | null = null;
  private retry = 0;
  private closedByUs = false;
  private reconnectTimer: number | null = null;

  constructor(
    private readonly host: string,
    private readonly token: string,
    private readonly handlers: SocketHandlers,
  ) {}

  connect(): void {
    this.closedByUs = false;
    this.handlers.onStatus("connecting");
    const url = `ws://${this.host}/ws?token=${encodeURIComponent(this.token)}`;
    let socket: WebSocket;
    try {
      socket = new WebSocket(url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;

    socket.onopen = () => {
      this.retry = 0;
      this.handlers.onStatus("open");
      this.send("hello");
      this.heartbeat = window.setInterval(() => this.send("ping"), 20_000);
    };

    socket.onmessage = (event) => {
      try {
        this.handlers.onEvent(JSON.parse(event.data as string) as Envelope<any>);
      } catch {
        /* ignore malformed frames */
      }
    };

    socket.onclose = () => {
      this.stopHeartbeat();
      this.handlers.onStatus("closed");
      if (!this.closedByUs) this.scheduleReconnect();
    };

    socket.onerror = () => {
      /* onclose always follows */
    };
  }

  send(type: string, data?: unknown): void {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    this.socket.send(JSON.stringify({ v: 1, type, data: data ?? {} }));
  }

  close(): void {
    this.closedByUs = true;
    this.stopHeartbeat();
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.close();
    this.socket = null;
  }

  private scheduleReconnect(): void {
    this.retry = Math.min(this.retry + 1, 8);
    const delay = Math.min(400 * 2 ** (this.retry - 1), 6000);
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = window.setTimeout(() => this.connect(), delay);
  }

  private stopHeartbeat(): void {
    if (this.heartbeat !== null) window.clearInterval(this.heartbeat);
    this.heartbeat = null;
  }
}
