import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";

export type ToastLevel = "info" | "warn" | "error";

export interface ToastItem {
  id: number;
  message: string;
  level: ToastLevel;
}

export function useToasts() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const push = useCallback((message: string, level: ToastLevel = "info") => {
    const id = Date.now() + Math.random();
    setToasts((current) => [...current.slice(-3), { id, message, level }]);
    window.setTimeout(() => {
      setToasts((current) => current.filter((toast) => toast.id !== id));
    }, level === "error" ? 6000 : 3800);
  }, []);

  return { toasts, push };
}

export function Toasts({ toasts }: { toasts: ToastItem[] }) {
  if (toasts.length === 0) return null;
  return (
    <div className="toasts">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`toast${toast.level === "error" ? " toast--error" : ""}${
            toast.level === "warn" ? " toast--warn" : ""
          }`}
        >
          {toast.message}
        </div>
      ))}
    </div>
  );
}

export function Sheet({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="sheet" onClick={onClose} role="dialog" aria-modal="true">
      <div className="sheet__body" onClick={(event) => event.stopPropagation()}>
        <header className="sheet__header">
          <h2 className="sheet__title">{title}</h2>
          <button type="button" className="btn btn--ghost btn--icon" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>
        <div className="sheet__content">{children}</div>
      </div>
    </div>
  );
}

export function Labeled({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <div>
      <p className="card__label">{label}</p>
      {children}
      {hint ? <p className="hint">{hint}</p> : null}
    </div>
  );
}

export function StatusLine({
  label,
  value,
  sub,
  tone,
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: "ok" | "warn";
}) {
  return (
    <div className="card card--tight">
      <p className="card__label">{label}</p>
      <p className="card__value" style={tone ? { color: tone === "ok" ? "var(--ok)" : "var(--warn)" } : undefined}>
        {value}
      </p>
      {sub ? <p className="card__sub">{sub}</p> : null}
    </div>
  );
}

/** Drag-free hold to repeat: used by the D-pad and the volume rockers. */
export function useHoldRepeat(action: () => void, startDelay = 420, every = 115) {
  const startTimer = useRef<number | null>(null);
  const repeatTimer = useRef<number | null>(null);

  const stop = useCallback(() => {
    if (startTimer.current !== null) window.clearTimeout(startTimer.current);
    if (repeatTimer.current !== null) window.clearInterval(repeatTimer.current);
    startTimer.current = null;
    repeatTimer.current = null;
  }, []);

  const start = useCallback(() => {
    stop();
    action();
    startTimer.current = window.setTimeout(() => {
      repeatTimer.current = window.setInterval(action, every);
    }, startDelay);
  }, [action, every, startDelay, stop]);

  useEffect(() => stop, [stop]);

  return {
    onPointerDown: (event: React.PointerEvent) => {
      event.preventDefault();
      start();
    },
    onPointerUp: stop,
    onPointerLeave: stop,
    onPointerCancel: stop,
    onContextMenu: (event: React.MouseEvent) => event.preventDefault(),
  };
}
