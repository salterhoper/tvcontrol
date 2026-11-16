import type { KeyName } from "../api";
import { IconBack } from "./icons";

interface NumpadDeckProps {
  onDigit: (digit: string) => void;
  onKey: (key: KeyName) => void;
  disabled?: boolean;
}

export function NumpadDeck({ onDigit, onKey, disabled = false }: NumpadDeckProps) {
  const digits = [
    ["1", "2", "3"],
    ["4", "5", "6"],
    ["7", "8", "9"],
  ];

  return (
    <div className="numpad-deck" style={disabled ? { opacity: 0.5, pointerEvents: "none" } : undefined}>
      <div className="numpad-grid">
        {digits.map((row, rIdx) => (
          <div key={rIdx} className="numpad-row">
            {row.map((d) => (
              <button
                key={d}
                type="button"
                className="numpad-btn"
                onClick={() => onDigit(d)}
              >
                {d}
              </button>
            ))}
          </div>
        ))}
        <div className="numpad-row">
          <button
            type="button"
            className="numpad-btn numpad-btn--sub"
            onClick={() => onKey("back")}
            aria-label="Back"
          >
            <IconBack />
          </button>
          <button
            type="button"
            className="numpad-btn"
            onClick={() => onDigit("0")}
          >
            0
          </button>
          <button
            type="button"
            className="numpad-btn numpad-btn--action"
            onClick={() => onKey("ok")}
          >
            OK
          </button>
        </div>
      </div>

      <div className="numpad-channels">
        <button
          type="button"
          className="btn btn--ghost"
          onClick={() => onKey("scroll_up")}
          title="Channel Up / Scroll Up"
        >
          ▲ CH +
        </button>
        <button
          type="button"
          className="btn btn--ghost"
          onClick={() => onKey("scroll_down")}
          title="Channel Down / Scroll Down"
        >
          ▼ CH -
        </button>
      </div>
    </div>
  );
}
