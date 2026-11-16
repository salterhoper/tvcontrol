import { useEffect, useRef, useState } from "react";
import { ApiError, defaultHost, normaliseHost, pair, ping, rememberHost, type Session } from "../api";

function deviceName(): string {
  const agent = navigator.userAgent;
  if (/iPhone/i.test(agent)) return "iPhone";
  if (/iPad/i.test(agent)) return "iPad";
  if (/Android/i.test(agent)) return "Android device";
  if (/Macintosh/i.test(agent)) return "Mac";
  return "Browser";
}

export function PairScreen({
  initialPin,
  onPaired,
}: {
  initialPin: string;
  onPaired: (session: Session) => void;
}) {
  const [host, setHost] = useState(defaultHost());
  const [pin, setPin] = useState(initialPin);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [found, setFound] = useState<string | null>(null);
  const autoTried = useRef(false);

  const hostReady = host.trim().length > 0;

  // Probe the address so the screen can say "Living Room TV" before the PIN is typed.
  useEffect(() => {
    if (!hostReady) {
      setFound(null);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      ping(normaliseHost(host))
        .then((response) => {
          if (!cancelled) setFound(response.name);
        })
        .catch(() => {
          if (!cancelled) setFound(null);
        });
    }, 280);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [host, hostReady]);

  async function submit(pinValue: string) {
    if (!hostReady || pinValue.trim().length < 4) return;
    setBusy(true);
    setError(null);
    try {
      const cleanHost = normaliseHost(host);
      rememberHost(cleanHost);
      const session = await pair(cleanHost, pinValue.trim(), deviceName());
      onPaired(session);
    } catch (problem) {
      setError(problem instanceof ApiError ? problem.message : "Could not reach the TV");
    } finally {
      setBusy(false);
    }
  }

  // Arriving from the QR code on the TV: the PIN is already in the URL.
  useEffect(() => {
    if (!initialPin || !hostReady || autoTried.current) return;
    autoTried.current = true;
    void submit(initialPin);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialPin, hostReady]);

  return (
    <div className="pair">
      <p className="pair__brand">TV Control</p>
      <h1 className="pair__title">
        {found ? `Connect to ${found}` : "Point this at your TV"}
      </h1>
      <p className="pair__sub">
        Open the address shown on the TV screen, or type it here. The PIN is on the TV too.
      </p>

      <div>
        <p className="card__label">TV address</p>
        <input
          className="field"
          value={host}
          onChange={(event) => setHost(event.target.value)}
          placeholder="192.168.1.42:8787"
          inputMode="url"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
        <p className="hint">
          {hostReady
            ? found
              ? "TV found and answering."
              : "Nothing is answering at that address yet."
            : "e.g. 192.168.1.42:8787 - the dashboard address from the TV screen."}
        </p>
      </div>

      <div>
        <p className="card__label">Pairing PIN</p>
        <input
          className="pin-input"
          value={pin}
          onChange={(event) => setPin(event.target.value.replace(/\D/g, "").slice(0, 6))}
          onKeyDown={(event) => {
            if (event.key === "Enter") void submit(pin);
          }}
          placeholder="000000"
          inputMode="numeric"
          autoComplete="one-time-code"
        />
      </div>

      {error ? <div className="notice notice--warn">{error}</div> : null}

      <button
        type="button"
        className="btn btn--primary btn--wide"
        disabled={busy || !hostReady || pin.length < 4}
        onClick={() => void submit(pin)}
      >
        {busy ? "Pairing…" : "Pair"}
      </button>

      <p className="legend">
        The PIN is stored on the TV and only your paired devices get a token. Rotate it any time from
        the TV screen or from Settings.
      </p>
    </div>
  );
}

export default PairScreen;
