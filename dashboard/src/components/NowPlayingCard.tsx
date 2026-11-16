import { useEffect, useRef, useState } from "react";
import type { NowPlayingInfo } from "../api";
import {
  IconFastForward,
  IconMusic,
  IconNext,
  IconPause,
  IconPlay,
  IconPrev,
  IconRefresh,
  IconRewind,
} from "./icons";

interface NowPlayingCardProps {
  nowPlaying: NowPlayingInfo | null;
  onControl: (action: string, positionMs?: number) => Promise<boolean>;
  onRefresh?: () => void;
  artUrl?: string;
  compact?: boolean;
}

function formatTime(ms: number): string {
  if (!ms || ms <= 0 || isNaN(ms)) return "0:00";
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const sStr = s < 10 ? `0${s}` : `${s}`;
  if (h > 0) {
    const mStr = m < 10 ? `0${m}` : `${m}`;
    return `${h}:${mStr}:${sStr}`;
  }
  return `${m}:${sStr}`;
}

export function NowPlayingCard({
  nowPlaying,
  onControl,
  onRefresh,
  artUrl,
  compact = false,
}: NowPlayingCardProps) {
  const [scrubPos, setScrubPos] = useState<number | null>(null);
  const [currentPos, setCurrentPos] = useState<number>(0);
  const [actionPending, setActionPending] = useState(false);

  const isScrubbingRef = useRef(false);
  const lastUpdateRef = useRef<number>(Date.now());

  const active = Boolean(nowPlaying?.active && (nowPlaying?.title || nowPlaying?.appName));
  const isPlaying = Boolean(nowPlaying?.isPlaying);
  const durationMs = nowPlaying?.durationMs && nowPlaying.durationMs > 0 ? nowPlaying.durationMs : 0;
  const speed = nowPlaying?.playbackSpeed && nowPlaying.playbackSpeed > 0 ? nowPlaying.playbackSpeed : 1.0;

  // Sync state whenever nowPlaying updates from socket or poll
  useEffect(() => {
    if (!isScrubbingRef.current) {
      setCurrentPos(nowPlaying?.positionMs ?? 0);
      lastUpdateRef.current = Date.now();
    }
  }, [nowPlaying?.positionMs, nowPlaying?.lastUpdated]);

  // Smooth local position interpolation when playing
  useEffect(() => {
    if (!isPlaying || isScrubbingRef.current) return;

    const interval = window.setInterval(() => {
      setCurrentPos((prev) => {
        const next = prev + 500 * speed;
        if (durationMs > 0 && next >= durationMs) {
          return durationMs;
        }
        return next;
      });
    }, 500);

    return () => window.clearInterval(interval);
  }, [isPlaying, speed, durationMs]);

  // Handle scrubber drag / release
  const displayPos = scrubPos !== null ? scrubPos : currentPos;
  const progressPercent = durationMs > 0 ? Math.min(100, Math.max(0, (displayPos / durationMs) * 100)) : 0;

  async function handleScrubChange(valueMs: number) {
    setScrubPos(valueMs);
  }

  async function handleScrubCommit(valueMs: number) {
    isScrubbingRef.current = false;
    setScrubPos(null);
    setCurrentPos(valueMs);
    await onControl("seek", Math.round(valueMs));
  }

  async function handleAction(action: string) {
    if (actionPending) return;
    setActionPending(true);
    try {
      await onControl(action);
    } finally {
      setActionPending(false);
    }
  }

  // Artwork resolution
  const artworkSrc = nowPlaying?.artworkBase64 || (nowPlaying?.hasArtwork && artUrl ? artUrl : null);

  if (!active) {
    return (
      <div className={`now-playing-hud now-playing-idle ${compact ? "compact" : ""}`}>
        <div className="now-playing-idle-inner">
          <div className="idle-art-placeholder">
            <IconMusic size={compact ? 18 : 22} />
          </div>
          <div className="idle-info">
            <span className="idle-title">No Media Active</span>
            <span className="idle-sub">Play music or video on TV</span>
          </div>
          <div className="idle-actions">
            <button
              type="button"
              className="now-playing-btn-icon"
              title="Check Active Sessions"
              onClick={onRefresh}
            >
              <IconRefresh size={16} />
            </button>
            <button
              type="button"
              className="now-playing-btn-play"
              title="Send Play/Pause"
              onClick={() => handleAction("play_pause")}
            >
              <IconPlay />
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={`now-playing-hud ${compact ? "compact" : ""} ${isPlaying ? "is-playing" : "is-paused"}`}>
      {/* Background ambient glow if artwork is present */}
      {artworkSrc && (
        <div
          className="now-playing-ambient-glow"
          style={{ backgroundImage: `url(${artworkSrc})` }}
          aria-hidden="true"
        />
      )}

      <div className="now-playing-content">
        {/* Top: Art + Metadata */}
        <div className="now-playing-header">
          <div className="now-playing-art-wrap">
            {artworkSrc ? (
              <img
                src={artworkSrc}
                alt="Artwork"
                className="now-playing-art-img"
                loading="lazy"
                onError={(e) => {
                  // Hide image on broken URL
                  (e.target as HTMLElement).style.display = "none";
                }}
              />
            ) : (
              <div className="now-playing-art-fallback">
                <IconMusic size={compact ? 22 : 32} />
              </div>
            )}
            {isPlaying && <div className="now-playing-live-dot" title="Live Playing" />}
          </div>

          <div className="now-playing-meta">
            <div className="now-playing-app-tag">
              <span className="app-dot" />
              <span className="app-name">{nowPlaying?.appName || nowPlaying?.packageName || "Media Player"}</span>
            </div>
            <h4 className="now-playing-title" title={nowPlaying?.title || "Unknown Title"}>
              {nowPlaying?.title || "Unknown Title"}
            </h4>
            <p className="now-playing-artist" title={nowPlaying?.artist || nowPlaying?.album || ""}>
              {nowPlaying?.artist || nowPlaying?.album || "Unknown Artist"}
            </p>
          </div>
        </div>

        {/* Middle: Timeline Scrubber */}
        {durationMs > 0 ? (
          <div className="now-playing-scrubber-row">
            <span className="time-label current-time">{formatTime(displayPos)}</span>
            <div className="scrubber-bar-container">
              <input
                type="range"
                className="now-playing-slider"
                min={0}
                max={durationMs}
                step={500}
                value={Math.round(displayPos)}
                onPointerDown={() => {
                  isScrubbingRef.current = true;
                }}
                onTouchStart={() => {
                  isScrubbingRef.current = true;
                }}
                onChange={(e) => handleScrubChange(Number(e.target.value))}
                onPointerUp={(e) => handleScrubCommit(Number((e.target as HTMLInputElement).value))}
                onTouchEnd={(e) => handleScrubCommit(Number((e.target as HTMLInputElement).value))}
                style={{
                  background: `linear-gradient(to right, var(--accent, #6366f1) ${progressPercent}%, rgba(255, 255, 255, 0.15) ${progressPercent}%)`,
                }}
                aria-label="Media seek scrubber"
              />
            </div>
            <span className="time-label duration-time">{formatTime(durationMs)}</span>
          </div>
        ) : (
          <div className="now-playing-scrubber-row live-stream-row">
            <span className="live-badge">LIVE / UNBOUNDED</span>
            <span className="time-label current-time">{formatTime(displayPos)}</span>
          </div>
        )}

        {/* Bottom: Transport Controls */}
        <div className="now-playing-controls">
          <button
            type="button"
            className="transport-btn skip-10"
            title="Rewind 10 seconds"
            onClick={() => handleAction("rewind")}
            aria-label="Rewind 10 seconds"
          >
            <IconRewind size={18} />
            <span className="sub-label">-10s</span>
          </button>

          <button
            type="button"
            className="transport-btn"
            title="Previous Track"
            onClick={() => handleAction("previous")}
            aria-label="Previous Track"
          >
            <IconPrev />
          </button>

          <button
            type="button"
            className="transport-btn play-pause-btn"
            title={isPlaying ? "Pause" : "Play"}
            onClick={() => handleAction("play_pause")}
            aria-label={isPlaying ? "Pause" : "Play"}
          >
            {isPlaying ? <IconPause size={22} /> : <IconPlay />}
          </button>

          <button
            type="button"
            className="transport-btn"
            title="Next Track"
            onClick={() => handleAction("next")}
            aria-label="Next Track"
          >
            <IconNext />
          </button>

          <button
            type="button"
            className="transport-btn skip-10"
            title="Forward 10 seconds"
            onClick={() => handleAction("fast_forward")}
            aria-label="Fast forward 10 seconds"
          >
            <IconFastForward size={18} />
            <span className="sub-label">+10s</span>
          </button>
        </div>
      </div>
    </div>
  );
}
