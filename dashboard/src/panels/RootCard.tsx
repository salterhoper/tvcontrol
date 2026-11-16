import { useState } from "react";
import type { RootReport, Api } from "../api";
import { IconCheck, IconChevron, IconDash, IconUnknown } from "../components/icons";

const TONE: Record<RootReport["level"], "ok" | "warn" | "off"> = {
  app: "ok",
  shell: "ok",
  weak: "warn",
  none: "off",
};

/** "Is this TV rooted?" - one button, a plain verdict, and the evidence behind it. */
export function RootCard({ api, onToast }: { api: Api | null; onToast: (message: string, level?: "info" | "warn" | "error") => void }) {
  const [report, setReport] = useState<RootReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);

  async function run() {
    if (!api) return;
    setBusy(true);
    try {
      setReport(await api.rootCheck());
    } catch (error) {
      onToast(error instanceof Error ? error.message : "Could not check root", "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card card--tight">
      <div className="card__head">
        <p className="card__label">Root check</p>
        {report ? <span className={`badge badge--${TONE[report.level]}`}>{report.headline}</span> : null}
      </div>

      {report ? (
        <>
          <p className="legend">{report.verdict}</p>
          <button type="button" className="disclose" onClick={() => setOpen(!open)} aria-expanded={open}>
            {open ? "Hide" : "Show"} {report.checks.length} checks <IconChevron up={open} />
          </button>
          {open ? (
            <ul className="checklist">
              {report.checks.map((check) => (
                <li key={check.id} className="checklist__row">
                  <span
                    className={`checklist__mark checklist__mark--${check.found == null ? "unknown" : check.found ? "yes" : "no"}`}
                  >
                    {check.found == null ? <IconUnknown /> : check.found ? <IconCheck /> : <IconDash />}
                  </span>
                  <span className="checklist__text">
                    <strong>{check.label}</strong>
                    <span>{check.detail}</span>
                  </span>
                </li>
              ))}
            </ul>
          ) : null}
          {report.actions.length > 0 ? (
            <ul className="legend legend--list">
              {report.actions.map((action) => (
                <li key={action}>{action}</li>
              ))}
            </ul>
          ) : null}
        </>
      ) : (
        <p className="legend">
          Looks for a su binary, root managers (Magisk, KernelSU, SuperSU), an adb root daemon and developer-build markers,
          then tries `su` once to see whether apps can really use it.
        </p>
      )}

      <button type="button" className="btn btn--wide" disabled={busy || !api} onClick={() => void run()}>
        {busy ? "Checking…" : report ? "Check again" : "Check if this TV is rooted"}
      </button>
    </section>
  );
}

export default RootCard;
