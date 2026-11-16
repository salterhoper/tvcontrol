import type { KeyName, VolumeResult } from "../api";
import {
  IconFastForward,
  IconMute,
  IconNext,
  IconPlay,
  IconPrev,
  IconRewind,
  IconStop,
  IconVolDown,
  IconVolUp,
} from "./icons";

interface MediaDeckProps {
  onKey: (key: KeyName) => void;
  volumeState?: VolumeResult | null;
  disabled?: boolean;
}

export function MediaDeck({ onKey, volumeState, disabled = false }: MediaDeckProps) {
  return (
    <div className="media-deck" style={disabled ? { opacity: 0.5, pointerEvents: "none" } : undefined}>
      {/* Top track navigation */}
      <div className="media-deck__secondary">
        <button
          type="button"
          className="btn btn--ghost btn--icon"
          onClick={() => onKey("previous")}
          aria-label="Previous"
          title="Previous Track"
        >
          <IconPrev />
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--icon"
          onClick={() => onKey("stop")}
          aria-label="Stop"
          title="Stop Playback"
        >
          <IconStop />
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--icon"
          onClick={() => onKey("next")}
          aria-label="Next"
          title="Next Track"
        >
          <IconNext />
        </button>
      </div>

      {/* Main playback hero controls */}
      <div className="media-deck__hero">
        <button
          type="button"
          className="media-deck__btn media-deck__btn--seek"
          onClick={() => onKey("rewind")}
          aria-label="Rewind 10 seconds"
          title="Rewind"
        >
          <IconRewind />
          <span className="media-deck__sublabel">-10s</span>
        </button>

        <button
          type="button"
          className="media-deck__play"
          onClick={() => onKey("play_pause")}
          aria-label="Play or Pause"
        >
          <IconPlay />
        </button>

        <button
          type="button"
          className="media-deck__btn media-deck__btn--seek"
          onClick={() => onKey("fast_forward")}
          aria-label="Fast Forward 10 seconds"
          title="Fast Forward"
        >
          <IconFastForward />
          <span className="media-deck__sublabel">+10s</span>
        </button>
      </div>

      {/* Volume slider / rockers */}
      <div className="media-deck__volume">
        <button
          type="button"
          className="btn btn--icon"
          onClick={() => onKey("volume_down")}
          aria-label="Volume Down"
        >
          <IconVolDown />
        </button>
        <button
          type="button"
          className={`btn btn--icon${volumeState?.muted ? " btn--danger" : ""}`}
          onClick={() => onKey("mute")}
          aria-label="Mute / Unmute"
        >
          <IconMute />
        </button>
        <button
          type="button"
          className="btn btn--icon"
          onClick={() => onKey("volume_up")}
          aria-label="Volume Up"
        >
          <IconVolUp />
        </button>
      </div>
      {volumeState?.levelPercent != null ? (
        <div className="media-deck__vol-status">
          Volume: {volumeState.muted ? "Muted" : `${volumeState.levelPercent}%`}
        </div>
      ) : null}
    </div>
  );
}
