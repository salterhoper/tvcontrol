import { useEffect, useState, useCallback, useRef } from "react";
import type { BluetoothDevice, BluetoothStatus } from "../api";
import {
  IconBluetooth,
  IconGamepad,
  IconHeadphones,
  IconRefresh,
  IconSignal,
  IconTrash,
  IconTv,
} from "../components/icons";
import { Sheet } from "../components/ui";
import type { RemoteState } from "../useRemote";

interface BluetoothPanelProps {
  remote: RemoteState;
  onClose: () => void;
  onToast: (message: string, level?: "info" | "warn" | "error") => void;
}

export function BluetoothPanel({ remote, onClose, onToast }: BluetoothPanelProps) {
  const { api } = remote;
  const [status, setStatus] = useState<BluetoothStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyDevice, setBusyDevice] = useState<string | null>(null);
  const [togglingPower, setTogglingPower] = useState(false);
  const [scanning, setScanning] = useState(false);
  const pollTimerRef = useRef<number | null>(null);

  const fetchStatus = useCallback(async () => {
    if (!api) return;
    try {
      const data = await api.bluetooth();
      setStatus(data);
      setScanning(data.scanning);
    } catch (e) {
      // silently ignore transient network blips
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    fetchStatus();

    // Poll while the sheet is open to capture device discovery and connection states
    pollTimerRef.current = window.setInterval(fetchStatus, 2500);
    return () => {
      if (pollTimerRef.current !== null) {
        window.clearInterval(pollTimerRef.current);
      }
    };
  }, [fetchStatus]);

  const handleToggleState = async () => {
    if (!api || !status || togglingPower) return;
    const next = !status.enabled;
    setTogglingPower(true);
    try {
      const res = await api.setBluetoothState(next);
      if (res.ok) {
        onToast(next ? "Bluetooth turned on" : "Bluetooth turned off", "info");
        await fetchStatus();
      } else {
        onToast(res.error ?? "Could not change Bluetooth state", "warn");
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Failed to toggle Bluetooth", "error");
    } finally {
      setTogglingPower(false);
    }
  };

  const handleToggleScan = async () => {
    if (!api || !status?.enabled) return;
    const shouldStart = !scanning;
    setScanning(shouldStart);
    try {
      const res = await api.scanBluetooth(shouldStart ? "start" : "stop");
      if (res.ok) {
        onToast(shouldStart ? "Scanning for nearby devices…" : "Scan stopped", "info");
        await fetchStatus();
      } else {
        onToast(res.error ?? "Failed to toggle scan", "warn");
        setScanning(!shouldStart);
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Scan failed", "error");
      setScanning(!shouldStart);
    }
  };

  const handleConnect = async (device: BluetoothDevice) => {
    if (!api) return;
    setBusyDevice(device.address);
    try {
      const res = await api.connectBluetooth(device.address);
      if (res.ok) {
        onToast(`Connecting to ${device.name}…`, "info");
        setTimeout(fetchStatus, 1500);
      } else {
        onToast(res.error ?? `Could not connect to ${device.name}`, "warn");
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Connection failed", "error");
    } finally {
      setBusyDevice(null);
    }
  };

  const handleDisconnect = async (device: BluetoothDevice) => {
    if (!api) return;
    setBusyDevice(device.address);
    try {
      const res = await api.disconnectBluetooth(device.address);
      if (res.ok) {
        onToast(`Disconnected from ${device.name}`, "info");
        await fetchStatus();
      } else {
        onToast(res.error ?? `Could not disconnect ${device.name}`, "warn");
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Disconnection failed", "error");
    } finally {
      setBusyDevice(null);
    }
  };

  const handlePair = async (device: BluetoothDevice) => {
    if (!api) return;
    setBusyDevice(device.address);
    try {
      const res = await api.pairBluetooth(device.address);
      if (res.ok) {
        onToast(`Pairing request sent to ${device.name}`, "info");
        setTimeout(fetchStatus, 2000);
      } else {
        onToast(res.error ?? `Could not pair with ${device.name}`, "warn");
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Pairing failed", "error");
    } finally {
      setBusyDevice(null);
    }
  };

  const handleUnpair = async (device: BluetoothDevice) => {
    if (!api) return;
    if (!confirm(`Unpair “${device.name}”?`)) return;
    setBusyDevice(device.address);
    try {
      const res = await api.unpairBluetooth(device.address);
      if (res.ok) {
        onToast(`Unpaired ${device.name}`, "info");
        await fetchStatus();
      } else {
        onToast(res.error ?? `Could not unpair ${device.name}`, "warn");
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Unpair failed", "error");
    } finally {
      setBusyDevice(null);
    }
  };

  const handleLaunchTvPairing = async () => {
    if (!api) return;
    try {
      const res = await api.launchTvPairing();
      if (res.ok) {
        onToast("Opened TV accessory pairing screen", "info");
      } else {
        onToast(res.error ?? "Could not open TV pairing screen", "warn");
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : "Failed to open TV pairing", "error");
    }
  };

  const renderDeviceIcon = (type: string) => {
    switch (type) {
      case "audio":
        return <IconHeadphones size={18} />;
      case "input":
        return <IconGamepad size={18} />;
      default:
        return <IconBluetooth size={18} />;
    }
  };

  return (
    <Sheet title="Bluetooth Devices" onClose={onClose}>
      <div className="bt-panel">
        {/* Adapter Status Card */}
        <div className="card card--tight">
          <div className="card__head">
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <div className="bt-adapter-icon">
                <IconBluetooth size={22} />
              </div>
              <div>
                <p className="card__value" style={{ fontSize: 15 }}>
                  {status?.name ?? "TV Bluetooth"}
                </p>
                <p className="card__sub" style={{ fontSize: 11 }}>
                  {status?.address ? `MAC: ${status.address}` : "Bluetooth Adapter"}
                </p>
              </div>
            </div>

            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <button
                type="button"
                className={`btn btn--sm${status?.enabled ? " btn--active" : ""}`}
                onClick={() => void handleToggleState()}
                disabled={togglingPower || loading}
              >
                {status?.enabled ? "Turn Off" : "Turn On"}
              </button>
            </div>
          </div>

          <div style={{ marginTop: 10, display: "flex", gap: 8 }}>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", gap: 6 }}
              onClick={() => void handleLaunchTvPairing()}
              title="Launch native TV pairing wizard on screen"
            >
              <IconTv /> Pair on TV Screen
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              style={{ padding: "6px 10px" }}
              onClick={() => void fetchStatus()}
              title="Refresh status"
            >
              <IconRefresh size={14} />
            </button>
          </div>
        </div>

        {/* Paired Devices */}
        <section className="card card--tight" style={{ marginTop: 12 }}>
          <div className="card__head">
            <p className="card__label">Paired Devices</p>
            <span className="badge">
              {status?.pairedDevices.length ?? 0} {status?.pairedDevices.length === 1 ? "device" : "devices"}
            </span>
          </div>

          {!status?.enabled ? (
            <div className="bt-empty-hint">Bluetooth is turned off. Enable it above to manage paired devices.</div>
          ) : (status?.pairedDevices.length ?? 0) === 0 ? (
            <div className="bt-empty-hint">No paired devices. Put your audio or gamepad into pairing mode and scan below.</div>
          ) : (
            <div className="bt-device-list">
              {status?.pairedDevices.map((device) => {
                const isBusy = busyDevice === device.address;
                return (
                  <div key={device.address} className="bt-device-item">
                    <div className="bt-device-item__icon bt-device-item__icon--paired" aria-hidden>
                      {renderDeviceIcon(device.deviceType)}
                    </div>
                    <div className="bt-device-item__info">
                      <div className="bt-device-item__name-row">
                        <span className="bt-device-item__name">{device.name}</span>
                        {device.connected ? (
                          <span className="badge badge--ok" style={{ fontSize: 10, padding: "2px 7px" }}>
                            Connected
                          </span>
                        ) : null}
                      </div>
                      <span className="bt-device-item__addr">{device.address}</span>
                    </div>

                    <div className="bt-device-item__actions">
                      {device.connected ? (
                        <button
                          type="button"
                          className="btn btn--sm"
                          onClick={() => void handleDisconnect(device)}
                          disabled={isBusy}
                        >
                          {isBusy ? "…" : "Disconnect"}
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="btn btn--primary btn--sm"
                          onClick={() => void handleConnect(device)}
                          disabled={isBusy}
                        >
                          {isBusy ? "…" : "Connect"}
                        </button>
                      )}
                      <button
                        type="button"
                        className="btn btn--ghost btn--sm"
                        style={{ padding: "6px 8px" }}
                        onClick={() => void handleUnpair(device)}
                        disabled={isBusy}
                        title="Unpair and remove device"
                        aria-label="Unpair device"
                      >
                        <IconTrash />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>

        {/* Scan & Discovered Devices */}
        <section className="card card--tight" style={{ marginTop: 12 }}>
          <div className="card__head">
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <p className="card__label" style={{ margin: 0 }}>
                Nearby Devices
              </p>
              {scanning ? (
                <span className="badge badge--ok bt-scanning-badge">
                  <span className="bt-radar-dot" /> Scanning
                </span>
              ) : null}
            </div>

            <button
              type="button"
              className={`btn btn--sm${scanning ? " btn--active" : " btn--primary"}`}
              onClick={() => void handleToggleScan()}
              disabled={!status?.enabled || loading}
            >
              {scanning ? "Stop Scan" : "Scan for Devices"}
            </button>
          </div>

          {!status?.enabled ? null : (status?.discoveredDevices.length ?? 0) === 0 ? (
            <div className="bt-empty-hint">
              {scanning
                ? "Searching for nearby Bluetooth accessories… Make sure your device is in pairing mode."
                : "Put your headphones, speaker, gamepad, or remote into pairing mode and tap “Scan for Devices”."}
            </div>
          ) : (
            <div className="bt-device-list">
              {status?.discoveredDevices.map((device) => {
                const isBusy = busyDevice === device.address;
                return (
                  <div key={device.address} className="bt-device-item">
                    <div className="bt-device-item__icon bt-device-item__icon--discovered" aria-hidden>
                      {renderDeviceIcon(device.deviceType)}
                    </div>
                    <div className="bt-device-item__info">
                      <span className="bt-device-item__name">{device.name}</span>
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        <span className="bt-device-item__addr">{device.address}</span>
                        {device.rssi ? (
                          <span className="bt-device-rssi">
                            <IconSignal size={12} /> {device.rssi} dBm
                          </span>
                        ) : null}
                      </div>
                    </div>

                    <div className="bt-device-item__actions">
                      <button
                        type="button"
                        className="btn btn--primary btn--sm"
                        onClick={() => void handlePair(device)}
                        disabled={isBusy}
                      >
                        {isBusy ? "Pairing…" : "Pair & Connect"}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      </div>
    </Sheet>
  );
}
