import { useState, useEffect, useCallback, useRef } from "react";
import type { StorageInfo, FileItem } from "../api";
import {
  IconUpload,
  IconPackage,
  IconTrash,
  IconFolder,
  IconFile,
  IconHardDrive,
  IconRefresh,
} from "../components/icons";
import { Sheet } from "../components/ui";
import type { RemoteState } from "../useRemote";

interface SideloadPanelProps {
  remote: RemoteState;
  onClose: () => void;
  onToast: (message: string, level?: "info" | "warn" | "error") => void;
}

interface UploadProgress {
  filename: string;
  percent: number;
  loaded: number;
  total: number;
}

export function SideloadPanel({ remote, onClose, onToast }: SideloadPanelProps) {
  const { api } = remote;
  const [storage, setStorage] = useState<StorageInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [autoInstall, setAutoInstall] = useState(true);
  const [activeUpload, setActiveUpload] = useState<UploadProgress | null>(null);
  const [installingFile, setInstallingFile] = useState<string | null>(null);
  const [deletingFile, setDeletingFile] = useState<string | null>(null);
  const [filter, setFilter] = useState<"all" | "apk" | "media">("all");
  const [isDragging, setIsDragging] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const fetchStorage = useCallback(async () => {
    if (!api) return;
    try {
      const data = await api.files();
      setStorage(data);
    } catch (e: any) {
      // transient network blip
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [api]);

  useEffect(() => {
    fetchStorage();
  }, [fetchStorage]);

  const handleRefresh = () => {
    setRefreshing(true);
    fetchStorage();
  };

  const handleUploadFiles = async (fileList: FileList | File[]) => {
    if (!api || fileList.length === 0) return;

    for (let i = 0; i < fileList.length; i++) {
      const file = fileList[i];
      const isApk = file.name.endsWith(".apk");
      const shouldInstall = autoInstall && isApk;

      setActiveUpload({
        filename: file.name,
        percent: 0,
        loaded: 0,
        total: file.size,
      });

      try {
        const res = await api.uploadFile(file, {
          installImmediately: shouldInstall,
          onProgress: (percent, loaded, total) => {
            setActiveUpload({
              filename: file.name,
              percent,
              loaded,
              total,
            });
          },
        });

        if (res.ok) {
          if (shouldInstall) {
            onToast(res.message || "APK uploaded and installer launched on TV!", "info");
          } else {
            onToast(`Uploaded ${file.name} to TV`, "info");
          }
        } else {
          onToast(res.error || "Upload failed", "error");
        }
      } catch (err: any) {
        onToast(err.message || "Failed to upload file", "error");
      } finally {
        setActiveUpload(null);
      }
    }

    fetchStorage();
  };

  const handleInstall = async (file: FileItem) => {
    if (!api || installingFile) return;
    setInstallingFile(file.name);
    try {
      const res = await api.installApk(file.name);
      if (res.ok) {
        onToast(res.message || "Installer opened on TV - confirm on screen", "info");
      } else {
        onToast(res.error || "Failed to launch installer", "error");
      }
    } catch (err: any) {
      onToast(err.message || "Could not launch package installer", "error");
    } finally {
      setInstallingFile(null);
    }
  };

  const handleDelete = async (file: FileItem) => {
    if (!api || deletingFile) return;
    if (!window.confirm(`Delete "${file.name}" from TV storage?`)) return;

    setDeletingFile(file.name);
    try {
      const res = await api.deleteFile(file.name);
      if (res.ok) {
        onToast(`Deleted ${file.name}`, "info");
        fetchStorage();
      } else {
        onToast("Could not delete file", "error");
      }
    } catch (err: any) {
      onToast(err.message || "Delete failed", "error");
    } finally {
      setDeletingFile(null);
    }
  };

  const filteredFiles = (storage?.files || []).filter((f) => {
    if (filter === "apk") return f.isApk;
    if (filter === "media") {
      const ext = f.extension.toLowerCase();
      return ["mp4", "mkv", "avi", "mov", "mp3", "flac", "wav", "m4a", "jpg", "png"].includes(ext);
    }
    return true;
  });

  const apkCount = (storage?.files || []).filter((f) => f.isApk).length;
  const mediaCount = (storage?.files || []).filter((f) => {
    const ext = f.extension.toLowerCase();
    return ["mp4", "mkv", "avi", "mov", "mp3", "flac", "wav", "m4a", "jpg", "png"].includes(ext);
  }).length;

  return (
    <Sheet title="WiFi Sideload & File Dropper" onClose={onClose}>
      <div className="sideload-panel">
        {/* Storage Bar Overview */}
        <section className="sideload-storage-card">
          <div className="sideload-storage-header">
            <div className="sideload-storage-info">
              <span className="sideload-storage-icon">
                <IconHardDrive size={18} />
              </span>
              <div>
                <span className="sideload-storage-title">TV Internal Storage</span>
                <p className="sideload-storage-subtitle">
                  {storage
                    ? `${storage.formattedFree} free of ${storage.formattedTotal} (${storage.freePercent}% free)`
                    : "Reading storage..."}
                </p>
              </div>
            </div>
            <button
              type="button"
              className="btn btn--ghost btn--icon"
              title="Refresh files"
              onClick={handleRefresh}
              disabled={refreshing}
            >
              <IconRefresh size={18} />
            </button>
          </div>

          {storage && (
            <div className="sideload-storage-bar-track">
              <div
                className="sideload-storage-bar-fill"
                style={{ width: `${Math.max(4, 100 - storage.freePercent)}%` }}
              />
            </div>
          )}
        </section>

        {/* Dropzone & File Uploader */}
        <section
          className={`sideload-dropzone ${isDragging ? "sideload-dropzone--active" : ""}`}
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragging(true);
          }}
          onDragLeave={() => setIsDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setIsDragging(false);
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
              handleUploadFiles(e.dataTransfer.files);
            }
          }}
          onClick={() => fileInputRef.current?.click()}
        >
          <input
            type="file"
            ref={fileInputRef}
            style={{ display: "none" }}
            multiple
            onChange={(e) => {
              if (e.target.files && e.target.files.length > 0) {
                handleUploadFiles(e.target.files);
              }
              e.target.value = "";
            }}
          />

          <div className="sideload-dropzone-icon">
            <IconUpload size={32} />
          </div>
          <div className="sideload-dropzone-text">
            <h4>Drop APK or media files here</h4>
            <p>or click to browse from your device</p>
          </div>

          <div
            className="sideload-autoinstall-toggle"
            onClick={(e) => e.stopPropagation()}
          >
            <label className="sideload-checkbox-label">
              <input
                type="checkbox"
                checked={autoInstall}
                onChange={(e) => setAutoInstall(e.target.checked)}
              />
              <span>Auto-install APKs on TV after upload</span>
            </label>
          </div>
        </section>

        {/* Active Upload Card */}
        {activeUpload && (
          <div className="sideload-upload-card">
            <div className="sideload-upload-header">
              <span className="sideload-upload-name">{activeUpload.filename}</span>
              <span className="sideload-upload-percent">{activeUpload.percent}%</span>
            </div>
            <div className="sideload-upload-bar-track">
              <div
                className="sideload-upload-bar-fill"
                style={{ width: `${activeUpload.percent}%` }}
              />
            </div>
            <p className="sideload-upload-hint">Sending file over high-speed local Wi-Fi...</p>
          </div>
        )}

        {/* Filter Chips */}
        <div className="sideload-filter-row">
          <button
            type="button"
            className={`sideload-filter-chip ${filter === "all" ? "sideload-filter-chip--active" : ""}`}
            onClick={() => setFilter("all")}
          >
            All Files ({storage?.files.length ?? 0})
          </button>
          <button
            type="button"
            className={`sideload-filter-chip ${filter === "apk" ? "sideload-filter-chip--active" : ""}`}
            onClick={() => setFilter("apk")}
          >
            APKs ({apkCount})
          </button>
          <button
            type="button"
            className={`sideload-filter-chip ${filter === "media" ? "sideload-filter-chip--active" : ""}`}
            onClick={() => setFilter("media")}
          >
            Media ({mediaCount})
          </button>
        </div>

        {/* Files List */}
        <div className="sideload-file-list">
          {loading ? (
            <div className="bt-empty-state">Loading TV files...</div>
          ) : filteredFiles.length === 0 ? (
            <div className="sideload-empty-state">
              <IconFolder size={40} />
              <p>No {filter === "apk" ? "APKs" : filter === "media" ? "media files" : "files"} in TV storage</p>
              <span className="sideload-empty-sub">
                Drop an APK or video above to transfer it directly to your TV.
              </span>
            </div>
          ) : (
            filteredFiles.map((file) => (
              <div key={file.name} className="sideload-file-item">
                <div className="sideload-file-avatar">
                  {file.isApk ? (
                    <IconPackage size={22} />
                  ) : (
                    <IconFile size={22} />
                  )}
                </div>

                <div className="sideload-file-details">
                  <div className="sideload-file-name-row">
                    <span className="sideload-file-title" title={file.name}>
                      {file.appName || file.name}
                    </span>
                    {file.isApk && (
                      <span className="sideload-badge-apk">APK</span>
                    )}
                  </div>
                  <div className="sideload-file-meta">
                    <span>{file.formattedSize}</span>
                    {file.appVersion && <span>• v{file.appVersion}</span>}
                    {file.appName && <span className="sideload-file-realname">• {file.name}</span>}
                  </div>
                </div>

                <div className="sideload-file-actions">
                  {file.isApk && (
                    <button
                      type="button"
                      className="btn btn--primary btn--sm sideload-btn-install"
                      onClick={() => handleInstall(file)}
                      disabled={installingFile === file.name}
                    >
                      {installingFile === file.name ? "Launching..." : "Install"}
                    </button>
                  )}

                  <a
                    href={api?.downloadFileUrl(file.name)}
                    download={file.name}
                    className="btn btn--ghost btn--icon btn--sm"
                    title="Download to device"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <IconFile size={16} />
                  </a>

                  <button
                    type="button"
                    className="btn btn--ghost btn--icon btn--sm sideload-btn-delete"
                    title="Delete file"
                    onClick={() => handleDelete(file)}
                    disabled={deletingFile === file.name}
                  >
                    <IconTrash size={16} />
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </Sheet>
  );
}
