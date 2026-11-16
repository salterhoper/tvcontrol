import { useState } from "react";
import type { AppEntry, KeyName } from "../api";
import {
  DEFAULT_CUSTOM_BUTTONS,
  saveCustomButtons,
  type ActionType,
  type CustomButton,
  type MacroStep,
} from "../types/remote-builder";
import {
  IconArrowDown,
  IconArrowUp,
  IconEdit,
  IconFilm,
  IconGear,
  IconMusic,
  IconPlay,
  IconPlus,
  IconSearch,
  IconStar,
  IconTrash,
  IconTv,
  IconVolUp,
  IconZap,
} from "./icons";

interface CustomRemoteGridProps {
  buttons: CustomButton[];
  apps: AppEntry[];
  onUpdateButtons: (buttons: CustomButton[]) => void;
  onKey: (key: KeyName) => void;
  onLaunch: (pkgOrQuery: string) => void;
  onSearch: (query: string) => void;
  onTypeText: (text: string) => void;
  onToast: (message: string, level?: "info" | "warn" | "error") => void;
  triggerHaptic?: () => void;
}

export function CustomRemoteGrid({
  buttons,
  apps,
  onUpdateButtons,
  onKey,
  onLaunch,
  onSearch,
  onTypeText,
  onToast,
  triggerHaptic,
}: CustomRemoteGridProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editingBtn, setEditingBtn] = useState<CustomButton | null>(null);
  const [isNew, setIsNew] = useState(false);

  // Form state
  const [label, setLabel] = useState("");
  const [icon, setIcon] = useState<string>("star");
  const [color, setColor] = useState<CustomButton["color"]>("default");
  const [actionType, setActionType] = useState<ActionType>("key");
  const [actionValue, setActionValue] = useState("ok");
  const [macroInput, setMacroInput] = useState("");

  function openCreateModal() {
    setIsNew(true);
    setLabel("");
    setIcon("star");
    setColor("accent");
    setActionType("key");
    setActionValue("ok");
    setMacroInput("");
    setEditingBtn({
      id: `btn-${Date.now()}`,
      label: "",
      actionType: "key",
      actionValue: "ok",
    });
  }

  function openEditModal(btn: CustomButton) {
    setIsNew(false);
    setEditingBtn(btn);
    setLabel(btn.label);
    setIcon(btn.icon ?? "star");
    setColor(btn.color ?? "default");
    setActionType(btn.actionType);
    setActionValue(btn.actionValue);
    if (btn.macroSteps) {
      setMacroInput(
        btn.macroSteps.map((s) => `${s.action}:${s.value}${s.delayMs ? `:${s.delayMs}` : ""}`).join("\n"),
      );
    } else {
      setMacroInput("");
    }
  }

  function saveButton() {
    if (!label.trim()) {
      onToast("Please enter a button label", "warn");
      return;
    }
    let parsedSteps: MacroStep[] | undefined = undefined;
    if (actionType === "macro") {
      parsedSteps = macroInput
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
          const [act, val, d] = line.split(":");
          return {
            action: (act as MacroStep["action"]) || "key",
            value: val || "",
            delayMs: d ? Number.parseInt(d, 10) : 200,
          };
        });
    }

    const updated: CustomButton = {
      id: editingBtn?.id ?? `btn-${Date.now()}`,
      label: label.trim(),
      icon,
      color,
      actionType,
      actionValue: actionValue.trim(),
      macroSteps: parsedSteps,
    };

    let nextList: CustomButton[];
    if (isNew) {
      nextList = [...buttons, updated];
    } else {
      nextList = buttons.map((b) => (b.id === updated.id ? updated : b));
    }
    onUpdateButtons(nextList);
    saveCustomButtons(nextList);
    setEditingBtn(null);
    onToast(isNew ? `Added "${updated.label}" button` : `Updated "${updated.label}"`, "info");
  }

  function deleteButton(id: string) {
    const nextList = buttons.filter((b) => b.id !== id);
    onUpdateButtons(nextList);
    saveCustomButtons(nextList);
    onToast("Button removed", "info");
  }

  function moveButton(index: number, direction: -1 | 1) {
    const target = index + direction;
    if (target < 0 || target >= buttons.length) return;
    const next = [...buttons];
    const temp = next[index];
    next[index] = next[target];
    next[target] = temp;
    onUpdateButtons(next);
    saveCustomButtons(next);
  }

  function resetDefaults() {
    onUpdateButtons(DEFAULT_CUSTOM_BUTTONS);
    saveCustomButtons(DEFAULT_CUSTOM_BUTTONS);
    onToast("Reset remote buttons to defaults", "info");
  }

  async function handleButtonClick(btn: CustomButton) {
    triggerHaptic?.();
    if (btn.actionType === "key") {
      onKey(btn.actionValue as KeyName);
    } else if (btn.actionType === "app") {
      onLaunch(btn.actionValue);
    } else if (btn.actionType === "search") {
      onSearch(btn.actionValue);
    } else if (btn.actionType === "text") {
      onTypeText(btn.actionValue);
    } else if (btn.actionType === "macro" && btn.macroSteps) {
      onToast(`Running macro "${btn.label}"…`, "info");
      for (const step of btn.macroSteps) {
        if (step.action === "key") onKey(step.value as KeyName);
        else if (step.action === "app") onLaunch(step.value);
        else if (step.action === "search") onSearch(step.value);
        else if (step.action === "text") onTypeText(step.value);
        if (step.delayMs && step.delayMs > 0) {
          await new Promise((r) => setTimeout(r, step.delayMs));
        }
      }
    }
  }

  function renderIcon(iconName?: string) {
    switch (iconName) {
      case "tv":
        return <IconTv />;
      case "play":
        return <IconPlay />;
      case "film":
        return <IconFilm />;
      case "music":
        return <IconMusic />;
      case "zap":
        return <IconZap />;
      case "search":
        return <IconSearch />;
      case "gear":
        return <IconGear />;
      case "vol":
        return <IconVolUp />;
      default:
        return <IconStar />;
    }
  }

  return (
    <div className="custom-builder">
      <div className="custom-builder__header">
        <span className="card__label">Custom Remote Buttons</span>
        <div className="row" style={{ marginTop: 0 }}>
          <button
            type="button"
            className={`btn btn--ghost btn--sm${isEditing ? " btn--active" : ""}`}
            onClick={() => setIsEditing(!isEditing)}
          >
            <IconEdit /> {isEditing ? "Done Editing" : "Customize"}
          </button>
          {isEditing ? (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={resetDefaults}
              title="Reset to default buttons"
            >
              Reset
            </button>
          ) : null}
        </div>
      </div>

      <div className="custom-grid">
        {buttons.map((btn, idx) => (
          <div key={btn.id} className="custom-btn-wrapper">
            <button
              type="button"
              className={`custom-btn custom-btn--${btn.color ?? "default"}${isEditing ? " custom-btn--editable" : ""}`}
              onClick={() => {
                if (isEditing) openEditModal(btn);
                else void handleButtonClick(btn);
              }}
            >
              <span className="custom-btn__icon">{renderIcon(btn.icon)}</span>
              <span className="custom-btn__label">{btn.label}</span>
              {isEditing ? <span className="custom-btn__edit-hint"><IconEdit /></span> : null}
            </button>

            {isEditing ? (
              <div className="custom-btn-controls">
                <button
                  type="button"
                  className="btn-mini"
                  disabled={idx === 0}
                  onClick={() => moveButton(idx, -1)}
                  title="Move left/up"
                >
                  <IconArrowUp />
                </button>
                <button
                  type="button"
                  className="btn-mini"
                  disabled={idx === buttons.length - 1}
                  onClick={() => moveButton(idx, 1)}
                  title="Move right/down"
                >
                  <IconArrowDown />
                </button>
                <button
                  type="button"
                  className="btn-mini btn-mini--danger"
                  onClick={() => deleteButton(btn.id)}
                  title="Delete button"
                >
                  <IconTrash />
                </button>
              </div>
            ) : null}
          </div>
        ))}

        {isEditing ? (
          <button
            type="button"
            className="custom-btn custom-btn--add"
            onClick={openCreateModal}
          >
            <IconPlus />
            <span>Add Button</span>
          </button>
        ) : null}
      </div>

      {/* Button Edit/Create Modal */}
      {editingBtn ? (
        <div className="sheet" onClick={() => setEditingBtn(null)} role="dialog">
          <div className="sheet__body" onClick={(e) => e.stopPropagation()}>
            <header className="sheet__header">
              <h2 className="sheet__title">{isNew ? "New Remote Button" : "Edit Remote Button"}</h2>
              <button
                type="button"
                className="btn btn--ghost btn--icon"
                onClick={() => setEditingBtn(null)}
              >
                ✕
              </button>
            </header>

            <div className="sheet__content">
              <div className="form-group">
                <label className="card__label">Button Label</label>
                <input
                  className="field"
                  value={label}
                  onChange={(e) => setLabel(e.target.value)}
                  placeholder="e.g. YouTube, Mute, Trailers"
                />
              </div>

              <div className="form-group">
                <label className="card__label">Action Type</label>
                <select
                  className="field"
                  value={actionType}
                  onChange={(e) => setActionType(e.target.value as ActionType)}
                >
                  <option value="key">TV Key Press (D-pad, Home, Vol, etc.)</option>
                  <option value="app">Launch Installed App</option>
                  <option value="search">Deep-Link Search</option>
                  <option value="macro">Macro / Multi-Step Sequence</option>
                  <option value="text">Send Text to TV</option>
                </select>
              </div>

              {actionType === "key" ? (
                <div className="form-group">
                  <label className="card__label">Key Name</label>
                  <select
                    className="field"
                    value={actionValue}
                    onChange={(e) => setActionValue(e.target.value)}
                  >
                    <option value="home">Home</option>
                    <option value="back">Back</option>
                    <option value="recents">Recents</option>
                    <option value="ok">OK</option>
                    <option value="volume_up">Volume Up</option>
                    <option value="volume_down">Volume Down</option>
                    <option value="mute">Mute</option>
                    <option value="play_pause">Play / Pause</option>
                    <option value="rewind">Rewind 10s</option>
                    <option value="fast_forward">Fast Forward 10s</option>
                    <option value="next">Next Track</option>
                    <option value="previous">Previous Track</option>
                    <option value="stop">Stop</option>
                    <option value="quick_settings">TV Settings</option>
                    <option value="notifications">Notifications</option>
                    <option value="scroll_up">Channel Up / Scroll Up</option>
                    <option value="scroll_down">Channel Down / Scroll Down</option>
                  </select>
                </div>
              ) : null}

              {actionType === "app" ? (
                <div className="form-group">
                  <label className="card__label">Target App</label>
                  {apps.length > 0 ? (
                    <select
                      className="field"
                      value={actionValue}
                      onChange={(e) => {
                        setActionValue(e.target.value);
                        if (!label) {
                          const found = apps.find((a) => a.pkg === e.target.value);
                          if (found) setLabel(found.label);
                        }
                      }}
                    >
                      <option value="">-- Choose Installed App --</option>
                      {apps.map((a) => (
                        <option key={a.pkg} value={a.pkg}>
                          {a.label} ({a.pkg})
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      className="field"
                      value={actionValue}
                      onChange={(e) => setActionValue(e.target.value)}
                      placeholder="e.g. com.netflix.ninja or netflix"
                    />
                  )}
                </div>
              ) : null}

              {actionType === "search" ? (
                <div className="form-group">
                  <label className="card__label">Search Query</label>
                  <input
                    className="field"
                    value={actionValue}
                    onChange={(e) => setActionValue(e.target.value)}
                    placeholder="e.g. 4k ambient nature, trailers"
                  />
                </div>
              ) : null}

              {actionType === "text" ? (
                <div className="form-group">
                  <label className="card__label">Text to Type</label>
                  <input
                    className="field"
                    value={actionValue}
                    onChange={(e) => setActionValue(e.target.value)}
                    placeholder="e.g. common search or text snippet"
                  />
                </div>
              ) : null}

              {actionType === "macro" ? (
                <div className="form-group">
                  <label className="card__label">Macro Steps (one per line: action:value:delayMs)</label>
                  <textarea
                    className="field"
                    rows={4}
                    value={macroInput}
                    onChange={(e) => setMacroInput(e.target.value)}
                    placeholder={"key:home:300\nkey:down:200\nkey:ok:100"}
                  />
                  <p className="hint">
                    Syntax: <code>action:value:delay_ms</code> (e.g. <code>key:mute:150</code>, <code>key:volume_down:100</code>).
                  </p>
                </div>
              ) : null}

              <div className="rowsplit">
                <div className="form-group">
                  <label className="card__label">Color</label>
                  <select
                    className="field"
                    value={color}
                    onChange={(e) => setColor(e.target.value as CustomButton["color"])}
                  >
                    <option value="default">Default Neutral</option>
                    <option value="accent">Teal Accent</option>
                    <option value="red">Red</option>
                    <option value="blue">Blue</option>
                    <option value="green">Green</option>
                    <option value="orange">Orange</option>
                    <option value="purple">Purple</option>
                  </select>
                </div>

                <div className="form-group">
                  <label className="card__label">Icon</label>
                  <select
                    className="field"
                    value={icon}
                    onChange={(e) => setIcon(e.target.value)}
                  >
                    <option value="star">Star</option>
                    <option value="tv">TV</option>
                    <option value="play">Play</option>
                    <option value="film">Film / Movie</option>
                    <option value="music">Music</option>
                    <option value="zap">Zap / Quick</option>
                    <option value="search">Search</option>
                    <option value="gear">Gear</option>
                    <option value="vol">Volume</option>
                  </select>
                </div>
              </div>

              <div className="btn-group" style={{ marginTop: 14 }}>
                <button type="button" className="btn btn--primary" onClick={saveButton}>
                  {isNew ? "Create Button" : "Save Changes"}
                </button>
                <button type="button" className="btn" onClick={() => setEditingBtn(null)}>
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
