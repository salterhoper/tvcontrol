
export type DeckMode = "dpad" | "media" | "numpad" | "custom";

export type ActionType = "key" | "app" | "search" | "macro" | "text";

export interface MacroStep {
  action: "key" | "app" | "wait" | "search" | "text";
  value: string;
  delayMs?: number;
}

export interface CustomButton {
  id: string;
  label: string;
  icon?: string;
  color?: "default" | "accent" | "blue" | "red" | "purple" | "orange" | "green";
  actionType: ActionType;
  actionValue: string;
  macroSteps?: MacroStep[];
}

export const DEFAULT_CUSTOM_BUTTONS: CustomButton[] = [
  {
    id: "btn-youtube",
    label: "YouTube",
    icon: "youtube",
    color: "red",
    actionType: "app",
    actionValue: "com.google.android.youtube.tv",
  },
  {
    id: "btn-netflix",
    label: "Netflix",
    icon: "netflix",
    color: "red",
    actionType: "app",
    actionValue: "com.netflix.ninja",
  },
  {
    id: "btn-plex",
    label: "Plex",
    icon: "film",
    color: "orange",
    actionType: "app",
    actionValue: "com.plexapp.android",
  },
  {
    id: "btn-smarttube",
    label: "SmartTube",
    icon: "play",
    color: "accent",
    actionType: "app",
    actionValue: "com.teamsmart.videomanager.tv",
  },
  {
    id: "btn-trailers",
    label: "Trailers",
    icon: "search",
    color: "purple",
    actionType: "search",
    actionValue: "new movie trailers 4k",
  },
  {
    id: "btn-quick-settings",
    label: "Settings",
    icon: "gear",
    color: "blue",
    actionType: "key",
    actionValue: "quick_settings",
  },
  {
    id: "btn-notifs",
    label: "Notifs",
    icon: "zap",
    color: "default",
    actionType: "key",
    actionValue: "notifications",
  },
  {
    id: "btn-cinemamute",
    label: "Night Mute",
    icon: "vol",
    color: "green",
    actionType: "macro",
    actionValue: "mute-vol-macro",
    macroSteps: [
      { action: "key", value: "mute", delayMs: 150 },
      { action: "key", value: "volume_down", delayMs: 100 },
      { action: "key", value: "volume_down", delayMs: 100 },
    ],
  },
];

const BUTTONS_KEY = "tvcontrol_custom_buttons_v1";
const DECK_KEY = "tvcontrol_deck_mode_v1";
const HAPTICS_KEY = "tvcontrol_haptics_enabled_v1";

export function loadCustomButtons(): CustomButton[] {
  try {
    const raw = localStorage.getItem(BUTTONS_KEY);
    if (!raw) return DEFAULT_CUSTOM_BUTTONS;
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed) && parsed.length > 0) return parsed;
  } catch {
    // fallback
  }
  return DEFAULT_CUSTOM_BUTTONS;
}

export function saveCustomButtons(buttons: CustomButton[]): void {
  try {
    localStorage.setItem(BUTTONS_KEY, JSON.stringify(buttons));
  } catch {
    // localStorage may be disabled
  }
}

export function loadDeckMode(): DeckMode {
  try {
    const mode = localStorage.getItem(DECK_KEY) as DeckMode;
    if (mode === "dpad" || mode === "media" || mode === "numpad" || mode === "custom") {
      return mode;
    }
  } catch {
    // fallback
  }
  return "dpad";
}

export function saveDeckMode(mode: DeckMode): void {
  try {
    localStorage.setItem(DECK_KEY, mode);
  } catch {
    // ignore
  }
}

export function loadHapticsEnabled(): boolean {
  try {
    const val = localStorage.getItem(HAPTICS_KEY);
    if (val === null) return true; // enabled by default
    return val === "1";
  } catch {
    return true;
  }
}

export function saveHapticsEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(HAPTICS_KEY, enabled ? "1" : "0");
  } catch {
    // ignore
  }
}
