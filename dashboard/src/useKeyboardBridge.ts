import { useEffect, useRef } from "react";
import type { Api, KeyName, ScreenSnapshot } from "./api";

type TextMode = "append" | "clear" | "enter" | "backspace";

/** Keys that are not text and map straight onto a remote button. */
const KEY_MAP: Record<string, KeyName> = {
  ArrowUp: "up",
  ArrowDown: "down",
  ArrowLeft: "left",
  ArrowRight: "right",
  Escape: "back",
  Home: "home",
  PageUp: "scroll_up",
  PageDown: "scroll_down",
  MediaPlayPause: "play_pause",
  MediaTrackNext: "next",
  MediaTrackPrevious: "previous",
  AudioVolumeUp: "volume_up",
  AudioVolumeDown: "volume_down",
  AudioVolumeMute: "mute",
};

const BATCH_MS = 35;

/**
 * Turns the computer's keyboard into the TV's keyboard while `enabled`.
 *
 * Printable characters go to the focused text field on the TV, batched for a few
 * milliseconds so fast typing is one request instead of one per letter. Requests are
 * chained, never parallel, so letters cannot arrive out of order. Everything else maps
 * to a remote button: arrows, Esc = Back, Enter = OK (or the search key in a text field).
 */
export function useKeyboardBridge({
  enabled,
  api,
  screen,
  pressKey,
  notify,
}: {
  enabled: boolean;
  api: Api | null;
  screen: ScreenSnapshot | null;
  pressKey: (key: KeyName, repeat?: number) => void;
  notify: (message: string, level?: "info" | "warn" | "error") => void;
}) {
  // Latest values without re-binding the listeners on every screen update.
  const live = useRef({ api, screen, pressKey, notify });
  live.current = { api, screen, pressKey, notify };

  useEffect(() => {
    if (!enabled) return;

    let buffer = "";
    let timer: number | null = null;
    let chain: Promise<unknown> = Promise.resolve();
    let lastWarn = 0;

    const send = (text: string, mode: TextMode, submit = false) => {
      chain = chain.then(async () => {
        const { api: client, notify: say } = live.current;
        if (!client) return;
        try {
          const result = await client.text(text, mode, submit);
          if (!result.ok && Date.now() - lastWarn > 4000) {
            lastWarn = Date.now();
            say(result.note ?? "The TV did not accept the text", "warn");
          }
        } catch (error) {
          if (Date.now() - lastWarn > 4000) {
            lastWarn = Date.now();
            say(error instanceof Error ? error.message : "Typing failed", "error");
          }
        }
      });
    };

    const flush = () => {
      if (timer !== null) window.clearTimeout(timer);
      timer = null;
      if (!buffer) return;
      const text = buffer;
      buffer = "";
      send(text, "append");
    };

    const queueText = (text: string) => {
      buffer += text;
      if (timer === null) timer = window.setTimeout(flush, BATCH_MS);
    };

    const isDashboardField = (target: EventTarget | null) => {
      const element = target as HTMLElement | null;
      if (!element) return false;
      return element.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(element.tagName);
    };

    const onKeyDown = (event: KeyboardEvent) => {
      // Let the dashboard's own inputs (PIN, search boxes, settings) behave normally.
      if (isDashboardField(event.target) || event.isComposing) return;
      if (event.ctrlKey || event.metaKey || event.altKey) {
        // Ctrl+V is handled by the paste listener; Ctrl+Backspace clears the field.
        if ((event.ctrlKey || event.metaKey) && event.key === "Backspace") {
          event.preventDefault();
          flush();
          send("", "clear");
        }
        return;
      }

      const { screen: snapshot, pressKey: press } = live.current;
      const inField = Boolean(snapshot?.field);

      if (event.key === "Backspace") {
        event.preventDefault();
        if (buffer) {
          buffer = buffer.slice(0, -1);
          if (!buffer && timer !== null) {
            window.clearTimeout(timer);
            timer = null;
          }
        } else {
          send("", "backspace");
        }
        return;
      }

      if (event.key === "Delete") {
        event.preventDefault();
        flush();
        send("", "clear");
        return;
      }

      if (event.key === "Enter") {
        event.preventDefault();
        flush();
        if (inField) send("", "enter");
        else press("ok");
        return;
      }

      const mapped = KEY_MAP[event.key];
      if (mapped) {
        event.preventDefault();
        flush();
        press(mapped);
        return;
      }

      if (event.key.length === 1) {
        event.preventDefault();
        if (event.key === " " && !inField) {
          flush();
          if (!event.repeat) press("play_pause");
          return;
        }
        queueText(event.key);
      }
    };

    const onPaste = (event: ClipboardEvent) => {
      if (isDashboardField(event.target)) return;
      const text = event.clipboardData?.getData("text");
      if (!text) return;
      event.preventDefault();
      queueText(text.replace(/\r?\n/g, " "));
    };

    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("paste", onPaste);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("paste", onPaste);
      flush();
    };
  }, [enabled]);
}
