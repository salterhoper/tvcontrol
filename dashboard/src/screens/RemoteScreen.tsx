import { useEffect, useState } from "react";
import type { KeyName } from "../api";
import { Pad } from "../components/Dpad";
import { MediaDeck } from "../components/MediaDeck";
import { NumpadDeck } from "../components/NumpadDeck";
import { CustomRemoteGrid } from "../components/CustomRemoteGrid";
import { NowPlayingCard } from "../components/NowPlayingCard";
import {
  IconBack,
  IconBluetooth,
  IconDpad,
  IconGear,
  IconGrid,
  IconHome,
  IconKeyboard,
  IconMic,
  IconMute,
  IconNext,
  IconPlay,
  IconPrev,
  IconRecents,
  IconRefresh,
  IconSearch,
  IconSpark,
  IconTv,
  IconUpload,
  IconVolDown,
  IconVolUp,
  IconX,
  IconZap,
} from "../components/icons";
import { Labeled } from "../components/ui";
import { useHaptics } from "../hooks/useHaptics";
import { useVoiceInput } from "../hooks/useVoiceInput";
import {
  loadCustomButtons,
  loadDeckMode,
  saveCustomButtons,
  saveDeckMode,
  type CustomButton,
  type DeckMode,
} from "../types/remote-builder";
import { useKeyboardBridge } from "../useKeyboardBridge";
import type { RemoteState } from "../useRemote";

interface RemoteScreenProps {
  remote: RemoteState;
  onOpenAssistant: () => void;
  onOpenSettings: () => void;
  onOpenBluetooth: () => void;
  onOpenSideload: () => void;
  onToast: (message: string, level?: "info" | "warn" | "error") => void;
  /** True while a sheet (settings, assistant) is open: the PC keyboard must not type behind it. */
  sheetOpen: boolean;
}

type MobileTab = "remote" | "tv-apps" | "keyboard";

export function RemoteScreen({
  remote,
  onOpenAssistant,
  onOpenSettings,
  onOpenBluetooth,
  onOpenSideload,
  onToast,
  sheetOpen,
}: RemoteScreenProps) {
  const {
    api,
    screen,
    engine,
    apps,
    status,
    tv,
    pressKey,
    typeText,
    restrictedSettings,
    refreshApps,
    nowPlaying,
    refreshNowPlaying,
    controlMedia,
  } = remote;

  // Deck mode & custom buttons
  const [deckMode, setDeckMode] = useState<DeckMode>(() => loadDeckMode());
  const [customButtons, setCustomButtons] = useState<CustomButton[]>(() => loadCustomButtons());
  const [mobileTab, setMobileTab] = useState<MobileTab>("remote");

  // Input states
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const [appFilter, setAppFilter] = useState("");
  const [enabling, setEnabling] = useState(false);
  const [refreshingApps, setRefreshingApps] = useState(false);

  // Responsive state
  const [isDesktop, setIsDesktop] = useState(() =>
    typeof window !== "undefined" ? window.innerWidth >= 900 : false,
  );

  useEffect(() => {
    function handleResize() {
      setIsDesktop(window.innerWidth >= 900);
    }
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  // Haptic feedback
  const haptics = useHaptics();

  // Voice Search
  const voiceSearch = useVoiceInput((transcript) => {
    setQuery(transcript);
    onToast(`Searching for “${transcript}”`, "info");
    api?.search(transcript, "auto").then((result) => {
      if (!result.ok) onToast(result.note ?? "Search failed", "warn");
    });
  });

  // PC Keyboard toggle
  const [pcKeyboard, setPcKeyboard] = useState(() => {
    try {
      return localStorage.getItem("pcKeyboard") === "1";
    } catch {
      return false;
    }
  });

  function togglePcKeyboard() {
    const next = !pcKeyboard;
    setPcKeyboard(next);
    try {
      localStorage.setItem("pcKeyboard", next ? "1" : "0");
    } catch {
      // ignore
    }
  }

  useKeyboardBridge({ enabled: pcKeyboard && !sheetOpen, api, screen, pressKey, notify: onToast });

  const focusedLabel = screen?.focus?.text ?? screen?.focus?.desc ?? null;
  const fieldHint = screen?.field?.hint ?? screen?.field?.text ?? null;
  const accessibilityOff = engine ? !engine.accessibilityConnected : false;

  const visibleApps = appFilter.trim()
    ? apps.filter(
        (app) =>
          app.label.toLowerCase().includes(appFilter.trim().toLowerCase()) ||
          app.pkg.toLowerCase().includes(appFilter.trim().toLowerCase()),
      )
    : apps;

  function handleKey(key: KeyName, repeat?: number) {
    haptics.trigger(12);
    pressKey(key, repeat);
  }

  function handleDeckChange(mode: DeckMode) {
    haptics.trigger(15);
    setDeckMode(mode);
    saveDeckMode(mode);
  }

  async function sendText(mode: "replace" | "append" | "clear", submit: boolean) {
    if (mode !== "clear" && !text.trim()) return;
    haptics.trigger(15);
    const result = await typeText(text, mode, submit);
    if (result?.ok && mode === "replace") setText("");
  }

  function handleNumpadDigit(digit: string) {
    haptics.trigger(10);
    typeText(digit, "append", false);
  }

  async function enableEngine() {
    if (!api) return;
    setEnabling(true);
    try {
      const result = await api.enableEngine();
      onToast(result.message, result.ok ? "info" : "warn");
    } catch (error) {
      onToast(error instanceof Error ? error.message : "Could not reach the TV", "error");
    } finally {
      setEnabling(false);
    }
  }

  // --- Sub-renderers ---

  const renderNotices = () => (
    <>
      {pcKeyboard ? (
        <div className="notice notice--live">
          <strong>Computer keyboard is live.</strong> Type to write into the TV's focused field. Arrows move, Enter = OK
          (or Search in text boxes), Esc = Back, Home = Home, Space = Play/Pause, Backspace deletes.
        </div>
      ) : null}

      {accessibilityOff ? (
        <div className="notice notice--warn">
          {restrictedSettings ? (
            <>
              This TV is declining accessibility for sideloaded apps. Run this once from ADB on the same Wi-Fi:
              <br />
              <code>adb shell cmd appops set &lt;package&gt; ACCESS_RESTRICTED_SETTINGS allow</code>
              <br />
              Until then: volume, media keys, launching apps and search work; navigation and typing do not.
            </>
          ) : (
            <>
              The accessibility engine is off on the TV. Open Settings → Accessibility → “TV Control remote”.
              Volume and media keys still work.
            </>
          )}
          <div className="btn-group" style={{ marginTop: 8 }}>
            <button type="button" className="btn" onClick={() => void enableEngine()} disabled={enabling}>
              {enabling ? "Asking the TV…" : "Turn engine on from here"}
            </button>
          </div>
        </div>
      ) : null}
    </>
  );

  const renderOnTvCard = () => (
    <section className="card card--tight">
      <div className="card__head">
        <p className="card__label">On the TV now</p>
        {screen?.imeVisible ? <span className="badge badge--ok">Keyboard Open</span> : null}
      </div>
      <p className="card__value">{screen?.app ?? "—"}</p>
      <p className="card__sub">
        {focusedLabel ? `Selected: ${focusedLabel}` : screen?.pkg ? screen.pkg : "Waiting for the TV…"}
      </p>
    </section>
  );


  const renderSearchCard = () => (
    <section className="card card--tight">
      <p className="card__label">Fast Search & Deep Links</p>
      <div className="row" style={{ marginTop: 4 }}>
        <input
          className="field"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="e.g. blade runner, lo-fi music"
          onKeyDown={(event) => {
            if (event.key === "Enter" && query.trim()) {
              api?.search(query, "auto").then((result) => {
                onToast(result.ok ? `Searching for “${query}”` : result.note ?? "Nothing could handle that search", result.ok ? "info" : "warn");
              });
            }
          }}
        />
        {voiceSearch.supported ? (
          <button
            type="button"
            className={`btn btn--icon${voiceSearch.isListening ? " btn--active btn--pulsing" : ""}`}
            onClick={voiceSearch.toggle}
            title={voiceSearch.isListening ? "Listening… tap to stop" : "Voice search"}
            aria-label="Voice search"
          >
            <IconMic active={voiceSearch.isListening} />
          </button>
        ) : null}
        <button
          type="button"
          className="btn"
          onClick={() => {
            if (!query.trim()) return;
            api?.search(query, "auto").then((result) => {
              onToast(result.ok ? `Searching for “${query}”` : result.note ?? "Nothing could handle that search", result.ok ? "info" : "warn");
            });
          }}
        >
          <IconSearch /> Search
        </button>
      </div>
    </section>
  );

  const renderKeyboardCard = () => (
    <section className="card">
      <Labeled
        label={fieldHint ? `Type into the TV · ${fieldHint}` : "Type into the TV"}
        hint={
          fieldHint
            ? undefined
            : "Focus a search or text box on the TV, then type here to instantly send it."
        }
      >
        <input
          className="field"
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder="Text for the focused field"
          onKeyDown={(event) => {
            if (event.key === "Enter") void sendText("replace", true);
          }}
        />
        <div className="row" style={{ marginTop: 8 }}>
          <button type="button" className="btn btn--primary" onClick={() => void sendText("replace", false)}>
            Send text
          </button>
          <button type="button" className="btn" onClick={() => void sendText("append", false)}>
            Append
          </button>
          <button type="button" className="btn" onClick={() => void sendText("clear", false)}>
            Clear
          </button>
          <button type="button" className="btn btn--ghost" onClick={() => void sendText("replace", true)}>
            Send + Enter
          </button>
        </div>
      </Labeled>
    </section>
  );

  const handleRefreshApps = async () => {
    if (refreshingApps) return;
    setRefreshingApps(true);
    try {
      await refreshApps(true);
      onToast("App library updated from TV", "info");
    } catch {
      onToast("Could not refresh apps", "warn");
    } finally {
      setRefreshingApps(false);
    }
  };

  const renderAppLauncherCard = () => (
    <section className="card card--tight">
      <div className="card__head">
        <p className="card__label">App Launcher Library</p>
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <span className="badge">
            {appFilter.trim() ? `${visibleApps.length} of ${apps.length}` : `${apps.length} apps`}
          </span>
          <button
            type="button"
            className="btn btn--ghost"
            style={{ padding: "3px 8px", fontSize: "0.75rem", display: "inline-flex", alignItems: "center", gap: 4 }}
            onClick={onOpenSideload}
            title="Install APK via Wi-Fi"
          >
            <IconUpload size={14} /> Sideload
          </button>
          <button
            type="button"
            className={`btn btn--icon${refreshingApps ? " btn--pulsing" : ""}`}
            style={{ padding: 4, width: 28, height: 28, minWidth: 28 }}
            onClick={() => void handleRefreshApps()}
            disabled={refreshingApps}
            title="Refresh installed apps from TV"
            aria-label="Refresh installed apps"
          >
            <IconRefresh size={14} />
          </button>
        </div>
      </div>

      <div className="row" style={{ marginTop: 6 }}>
        <div className="app-filter-wrapper">
          <input
            className="field"
            value={appFilter}
            onChange={(event) => setAppFilter(event.target.value)}
            placeholder="Filter installed apps…"
            autoCapitalize="none"
          />
          {appFilter ? (
            <button
              type="button"
              className="app-filter-clear"
              onClick={() => setAppFilter("")}
              title="Clear filter"
              aria-label="Clear filter"
            >
              <IconX size={12} />
            </button>
          ) : null}
        </div>
        <button
          type="button"
          className="btn"
          onClick={() => {
            const filterLower = appFilter.trim().toLowerCase();
            const exact = apps.find(
              (app) =>
                app.label.toLowerCase() === filterLower ||
                app.pkg.toLowerCase() === filterLower,
            );
            const target = exact?.pkg ?? appFilter.trim();
            if (!target) return;
            api
              ?.launch({ query: target })
              .then((outcome) =>
                onToast(
                  outcome.ok ? `Opening ${outcome.label ?? target}` : outcome.note ?? "Could not open it",
                  outcome.ok ? "info" : "warn",
                ),
              );
          }}
        >
          Open
        </button>
      </div>

      {visibleApps.length > 0 ? (
        <div className="app-library-grid">
          {visibleApps.map((app) => (
            <button
              key={app.pkg}
              type="button"
              className="app-card"
              title={`${app.label} (${app.pkg})`}
              onClick={() => {
                haptics.trigger(12);
                api?.launch({ package: app.pkg }).then((outcome) => {
                  if (!outcome.ok) onToast(outcome.note ?? "Could not open it", "warn");
                });
              }}
            >
              <div className="app-card__icon" aria-hidden>
                {app.label.trim() ? app.label.trim()[0].toUpperCase() : "A"}
              </div>
              <div className="app-card__info">
                <span className="app-card__title">{app.label}</span>
                <span className="app-card__pkg">{app.pkg.split(".").pop() ?? app.pkg}</span>
              </div>
            </button>
          ))}
        </div>
      ) : (
        <div className="app-library-empty">
          {appFilter ? (
            <>
              <span>No apps match “{appFilter}”</span>
              <button type="button" className="btn btn--ghost" onClick={() => setAppFilter("")}>
                Clear filter
              </button>
            </>
          ) : (
            <>
              <span>No installed apps loaded from TV</span>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => void handleRefreshApps()}
                disabled={refreshingApps}
              >
                {refreshingApps ? "Refreshing…" : "Load apps"}
              </button>
            </>
          )}
        </div>
      )}
    </section>
  );

  const renderRemoteDeck = () => (
    <div className="remote-deck-container">
      {/* Deck Mode Selector */}
      <div className="deck-switcher">
        <button
          type="button"
          className={`deck-switcher__tab${deckMode === "dpad" ? " deck-switcher__tab--active" : ""}`}
          onClick={() => handleDeckChange("dpad")}
        >
          <IconDpad /> D-Pad
        </button>
        <button
          type="button"
          className={`deck-switcher__tab${deckMode === "media" ? " deck-switcher__tab--active" : ""}`}
          onClick={() => handleDeckChange("media")}
        >
          <IconPlay /> Media
        </button>
        <button
          type="button"
          className={`deck-switcher__tab${deckMode === "numpad" ? " deck-switcher__tab--active" : ""}`}
          onClick={() => handleDeckChange("numpad")}
        >
          123 Num
        </button>
        <button
          type="button"
          className={`deck-switcher__tab${deckMode === "custom" ? " deck-switcher__tab--active" : ""}`}
          onClick={() => handleDeckChange("custom")}
        >
          <IconGrid /> Custom
        </button>
      </div>

      {/* Global Navigation Keys */}
      <div className="btn-group" style={{ margin: "10px 0" }}>
        <button type="button" className="btn" onClick={() => handleKey("back")}>
          <IconBack /> Back
        </button>
        <button type="button" className="btn" onClick={() => handleKey("home")}>
          <IconHome /> Home
        </button>
        <button type="button" className="btn" onClick={() => handleKey("recents")}>
          <IconRecents /> Recents
        </button>
      </div>

      {/* Active Deck View */}
      {deckMode === "dpad" ? (
        <>
          <Pad onKey={handleKey} />
          <div className="btn-group" style={{ marginTop: 8 }}>
            <button type="button" className="btn btn--ghost" onClick={() => handleKey("scroll_up")}>
              Scroll up
            </button>
            <button type="button" className="btn btn--ghost" onClick={() => handleKey("quick_settings")}>
              TV Settings
            </button>
            <button type="button" className="btn btn--ghost" onClick={() => handleKey("scroll_down")}>
              Scroll down
            </button>
          </div>
        </>
      ) : null}

      {deckMode === "media" ? (
        <MediaDeck onKey={handleKey} />
      ) : null}

      {deckMode === "numpad" ? (
        <NumpadDeck onDigit={handleNumpadDigit} onKey={handleKey} />
      ) : null}

      {deckMode === "custom" ? (
        <CustomRemoteGrid
          buttons={customButtons}
          apps={apps}
          onUpdateButtons={(next) => {
            setCustomButtons(next);
            saveCustomButtons(next);
          }}
          onKey={handleKey}
          onLaunch={(pkgOrQuery) => {
            haptics.trigger(15);
            api?.launch({ package: pkgOrQuery, query: pkgOrQuery }).then((res) => {
              if (!res.ok) onToast(res.note ?? "Could not launch", "warn");
            });
          }}
          onSearch={(q) => {
            haptics.trigger(15);
            api?.search(q, "auto").then((res) => {
              if (!res.ok) onToast(res.note ?? "Search failed", "warn");
            });
          }}
          onTypeText={(t) => {
            haptics.trigger(15);
            typeText(t, "append", false);
          }}
          onToast={onToast}
          triggerHaptic={() => haptics.trigger(12)}
        />
      ) : null}

      {/* Volume & Playback Row (visible when not already in full media deck) */}
      {deckMode !== "media" ? (
        <section className="rowsplit" style={{ marginTop: 12 }}>
          <div className="card card--tight">
            <p className="card__label">Volume</p>
            <div className="btn-group" style={{ marginTop: 4 }}>
              <button type="button" className="btn" onClick={() => handleKey("volume_down")} aria-label="Volume Down">
                <IconVolDown />
              </button>
              <button type="button" className="btn" onClick={() => handleKey("mute")} aria-label="Mute">
                <IconMute />
              </button>
              <button type="button" className="btn" onClick={() => handleKey("volume_up")} aria-label="Volume Up">
                <IconVolUp />
              </button>
            </div>
          </div>
          <div className="card card--tight">
            <p className="card__label">Playback</p>
            <div className="btn-group" style={{ marginTop: 4 }}>
              <button type="button" className="btn" onClick={() => handleKey("previous")} aria-label="Previous">
                <IconPrev />
              </button>
              <button type="button" className="btn" onClick={() => handleKey("play_pause")} aria-label="Play/Pause">
                <IconPlay />
              </button>
              <button type="button" className="btn" onClick={() => handleKey("next")} aria-label="Next">
                <IconNext />
              </button>
            </div>
          </div>
        </section>
      ) : null}

      {/* Quick custom grid preview below D-pad for fast access without switching tabs */}
      {deckMode !== "custom" && customButtons.length > 0 ? (
        <div style={{ marginTop: 14 }}>
          <CustomRemoteGrid
            buttons={customButtons.slice(0, 4)}
            apps={apps}
            onUpdateButtons={(next) => {
              setCustomButtons(next);
              saveCustomButtons(next);
            }}
            onKey={handleKey}
            onLaunch={(pkgOrQuery) => {
              haptics.trigger(15);
              api?.launch({ package: pkgOrQuery, query: pkgOrQuery }).then((res) => {
                if (!res.ok) onToast(res.note ?? "Could not launch", "warn");
              });
            }}
            onSearch={(q) => {
              haptics.trigger(15);
              api?.search(q, "auto").then((res) => {
                if (!res.ok) onToast(res.note ?? "Search failed", "warn");
              });
            }}
            onTypeText={(t) => {
              haptics.trigger(15);
              typeText(t, "append", false);
            }}
            onToast={onToast}
            triggerHaptic={() => haptics.trigger(12)}
          />
        </div>
      ) : null}
    </div>
  );

  return (
    <div className={`app${isDesktop ? " app--desktop" : ""}`}>
      {/* Top Header */}
      <header className="header">
        <span className={`dot dot--${status}`} aria-hidden title={`Status: ${status}`} />
        <h1 className="header__title">{tv?.name ?? "TV Control"}</h1>
        <div className="header__actions">
          {/* Haptics toggle */}
          <button
            type="button"
            className={`btn btn--ghost btn--icon${haptics.enabled ? " btn--active" : ""}`}
            onClick={haptics.toggle}
            aria-label="Toggle haptic feedback"
            title={haptics.enabled ? "Haptic vibrations ON" : "Haptic vibrations OFF"}
          >
            <IconZap />
          </button>
          {/* PC Keyboard toggle */}
          <button
            type="button"
            className={`btn btn--ghost btn--icon${pcKeyboard ? " btn--active" : ""}`}
            onClick={togglePcKeyboard}
            aria-label="Use this computer's keyboard on the TV"
            aria-pressed={pcKeyboard}
            title="Use keyboard on TV"
          >
            <IconKeyboard />
          </button>
          {/* AI Assistant */}
          <button
            type="button"
            className="btn btn--ghost btn--icon"
            onClick={onOpenAssistant}
            aria-label="Assistant"
            title="AI Assistant"
          >
            <IconSpark />
          </button>
          {/* Bluetooth Devices */}
          <button
            type="button"
            className="btn btn--ghost btn--icon"
            onClick={onOpenBluetooth}
            aria-label="Bluetooth Devices"
            title="Bluetooth Devices & Pairing"
          >
            <IconBluetooth />
          </button>
          {/* WiFi Sideload & File Dropper */}
          <button
            type="button"
            className="btn btn--ghost btn--icon"
            onClick={onOpenSideload}
            aria-label="WiFi Sideload & Files"
            title="WiFi Sideload & File Dropper"
          >
            <IconUpload />
          </button>
          {/* Settings */}
          <button
            type="button"
            className="btn btn--ghost btn--icon"
            onClick={onOpenSettings}
            aria-label="Settings"
            title="Settings & Diagnostics"
          >
            <IconGear />
          </button>
        </div>
      </header>

      {renderNotices()}

      {/* Desktop Mode Layout: 2 Columns */}
      {isDesktop ? (
        <div className="desktop-layout">
          <div className="desktop-layout__col-remote">
            {renderRemoteDeck()}
          </div>
          <div className="desktop-layout__col-console">
            <NowPlayingCard
              nowPlaying={nowPlaying}
              onControl={controlMedia}
              onRefresh={refreshNowPlaying}
              artUrl={api?.mediaArtUrl()}
            />
            {renderOnTvCard()}
            {renderSearchCard()}
            {renderKeyboardCard()}
            {renderAppLauncherCard()}
          </div>
        </div>
      ) : (
        /* Mobile / PWA Mode: Tabbed Ergonomic Interface */
        <div className="mobile-view-container">
          {mobileTab === "remote" && (
            <div className="mobile-tab-content">
              <NowPlayingCard
                nowPlaying={nowPlaying}
                onControl={controlMedia}
                onRefresh={refreshNowPlaying}
                artUrl={api?.mediaArtUrl()}
                compact={deckMode !== "media"}
              />
              {renderOnTvCard()}
              {renderRemoteDeck()}
            </div>
          )}

          {mobileTab === "tv-apps" && (
            <div className="mobile-tab-content">
              {nowPlaying?.active && (
                <NowPlayingCard
                  nowPlaying={nowPlaying}
                  onControl={controlMedia}
                  onRefresh={refreshNowPlaying}
                  artUrl={api?.mediaArtUrl()}
                  compact
                />
              )}
              {renderOnTvCard()}
              {renderSearchCard()}
              {renderAppLauncherCard()}
            </div>
          )}

          {mobileTab === "keyboard" && (
            <div className="mobile-tab-content">
              {nowPlaying?.active && (
                <NowPlayingCard
                  nowPlaying={nowPlaying}
                  onControl={controlMedia}
                  onRefresh={refreshNowPlaying}
                  artUrl={api?.mediaArtUrl()}
                  compact
                />
              )}
              {renderOnTvCard()}
              {renderKeyboardCard()}
            </div>
          )}

          {/* Fixed Bottom Navigation Bar for Mobile PWA */}
          <nav className="mobile-nav" aria-label="Mobile Navigation">
            <button
              type="button"
              className={`mobile-nav__item${mobileTab === "remote" ? " mobile-nav__item--active" : ""}`}
              onClick={() => {
                haptics.trigger(10);
                setMobileTab("remote");
              }}
            >
              <IconDpad />
              <span>Remote</span>
            </button>
            <button
              type="button"
              className={`mobile-nav__item${mobileTab === "tv-apps" ? " mobile-nav__item--active" : ""}`}
              onClick={() => {
                haptics.trigger(10);
                setMobileTab("tv-apps");
              }}
            >
              <IconTv />
              <span>TV & Apps</span>
            </button>
            <button
              type="button"
              className={`mobile-nav__item${mobileTab === "keyboard" ? " mobile-nav__item--active" : ""}`}
              onClick={() => {
                haptics.trigger(10);
                setMobileTab("keyboard");
              }}
            >
              <IconKeyboard />
              <span>Keyboard</span>
            </button>
            <button
              type="button"
              className="mobile-nav__item"
              onClick={() => {
                haptics.trigger(10);
                onOpenAssistant();
              }}
            >
              <IconSpark />
              <span>Assistant</span>
            </button>
          </nav>
        </div>
      )}
    </div>
  );
}

export default RemoteScreen;
