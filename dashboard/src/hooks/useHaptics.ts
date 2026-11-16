import { useCallback, useState } from "react";
import { loadHapticsEnabled, saveHapticsEnabled } from "../types/remote-builder";

export function useHaptics() {
  const [enabled, setEnabled] = useState<boolean>(() => loadHapticsEnabled());

  const toggle = useCallback(() => {
    setEnabled((curr) => {
      const next = !curr;
      saveHapticsEnabled(next);
      return next;
    });
  }, []);

  const trigger = useCallback(
    (pattern: number | number[] = 12) => {
      if (!enabled) return;
      if (typeof navigator !== "undefined" && "vibrate" in navigator) {
        try {
          navigator.vibrate(pattern);
        } catch {
          // ignore
        }
      }
    },
    [enabled],
  );

  return { enabled, toggle, trigger };
}
