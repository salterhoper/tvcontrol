import type { KeyName } from "../api";
import { useHoldRepeat } from "./ui";

interface PadProps {
  onKey: (key: KeyName) => void;
  disabled?: boolean;
}

/**
 * The hero control. Hold a direction and it repeats, exactly like leaning on the
 * remote's ring - which is how people actually browse a TV.
 */
export function Pad({ onKey, disabled = false }: PadProps) {
  const up = useHoldRepeat(() => onKey("up"));
  const down = useHoldRepeat(() => onKey("down"));
  const left = useHoldRepeat(() => onKey("left"));
  const right = useHoldRepeat(() => onKey("right"));

  return (
    <div className="pad" style={disabled ? { opacity: 0.5 } : undefined}>
      <button
        type="button"
        className="pad__btn pad__btn--up"
        aria-label="Up"
        disabled={disabled}
        {...up}
      >
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M6 15l6-6 6 6" /></svg>
      </button>
      <button
        type="button"
        className="pad__btn pad__btn--down"
        aria-label="Down"
        disabled={disabled}
        {...down}
      >
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M6 9l6 6 6-6" /></svg>
      </button>
      <button
        type="button"
        className="pad__btn pad__btn--left"
        aria-label="Left"
        disabled={disabled}
        {...left}
      >
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M15 6l-6 6 6 6" /></svg>
      </button>
      <button
        type="button"
        className="pad__btn pad__btn--right"
        aria-label="Right"
        disabled={disabled}
        {...right}
      >
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M9 6l6 6-6 6" /></svg>
      </button>
      <button
        type="button"
        className="pad__ok"
        aria-label="OK"
        disabled={disabled}
        onClick={() => onKey("ok")}
      >
        OK
      </button>
    </div>
  );
}
