import type { ReactNode } from "react";

/** Small stroke icons, drawn once so the UI does not depend on emoji fonts (a TV's browser has none). */
function Icon({ children, size = 20 }: { children: ReactNode; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {children}
    </svg>
  );
}

export const IconSpark = () => (
  <Icon>
    <path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z" />
    <path d="M19 16l.7 1.8L21.5 18.5l-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7z" />
  </Icon>
);

export const IconGear = () => (
  <Icon>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z" />
  </Icon>
);

export const IconBack = () => (
  <Icon>
    <path d="M15 5l-7 7 7 7" />
  </Icon>
);

export const IconHome = () => (
  <Icon>
    <path d="M4 11l8-7 8 7v8a1 1 0 01-1 1h-4v-6H9v6H5a1 1 0 01-1-1z" />
  </Icon>
);

export const IconRecents = () => (
  <Icon>
    <rect x="5" y="5" width="14" height="14" rx="3" />
  </Icon>
);

export const IconVolDown = () => (
  <Icon>
    <path d="M4 10v4h3.5L12 18V6L7.5 10z" />
    <path d="M16 10.5a2.5 2.5 0 010 3" />
  </Icon>
);

export const IconVolUp = () => (
  <Icon>
    <path d="M4 10v4h3.5L12 18V6L7.5 10z" />
    <path d="M16 9a4 4 0 010 6M18.5 6.5a7.5 7.5 0 010 11" />
  </Icon>
);

export const IconMute = () => (
  <Icon>
    <path d="M4 10v4h3.5L12 18V6L7.5 10z" />
    <path d="M16 9.5l5 5M21 9.5l-5 5" />
  </Icon>
);

export const IconPlay = () => (
  <Icon>
    <path d="M8 5.5v13l10-6.5z" fill="currentColor" />
  </Icon>
);

export const IconPrev = () => (
  <Icon>
    <path d="M7 5v14M18 6v12l-9-6z" />
  </Icon>
);

export const IconNext = () => (
  <Icon>
    <path d="M17 5v14M6 6v12l9-6z" />
  </Icon>
);

export const IconCheck = () => (
  <Icon size={16}>
    <path d="M5 12.5l4.5 4.5L19 7.5" />
  </Icon>
);

export const IconDash = () => (
  <Icon size={16}>
    <path d="M6 12h12" />
  </Icon>
);

export const IconUnknown = () => (
  <Icon size={16}>
    <path d="M9.5 9a2.5 2.5 0 114 2c-.9.6-1.5 1.1-1.5 2.2M12 17h.01" />
  </Icon>
);

export const IconChevron = ({ up = false }: { up?: boolean }) => (
  <Icon size={16}>
    <path d={up ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6"} />
  </Icon>
);

export const IconKeyboard = () => (
  <Icon>
    <rect x="2.5" y="6" width="19" height="12" rx="2.5" />
    <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10" />
  </Icon>
);

export const IconMic = ({ active = false }: { active?: boolean }) => (
  <Icon>
    <rect x="9" y="3" width="6" height="11" rx="3" fill={active ? "currentColor" : "none"} />
    <path d="M19 10v1a7 7 0 01-14 0v-1M12 18v4M8 22h8" />
  </Icon>
);

export const IconPlus = () => (
  <Icon size={16}>
    <path d="M12 5v14M5 12h14" />
  </Icon>
);

export const IconTrash = ({ size = 16 }: { size?: number } = {}) => (
  <Icon size={size}>
    <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2M10 11v6M14 11v6" />
  </Icon>
);

export const IconEdit = () => (
  <Icon size={16}>
    <path d="M12 20h9M16.5 3.5a2.121 2.121 0 013 3L7 19l-4 1 1-4L16.5 3.5z" />
  </Icon>
);

export const IconTv = () => (
  <Icon>
    <rect x="2" y="7" width="20" height="15" rx="2" />
    <polyline points="17 2 12 7 7 2" />
  </Icon>
);

export const IconStar = () => (
  <Icon size={16}>
    <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
  </Icon>
);

export const IconFilm = () => (
  <Icon>
    <rect x="2" y="2" width="20" height="20" rx="2.18" />
    <line x1="7" y1="2" x2="7" y2="22" />
    <line x1="17" y1="2" x2="17" y2="22" />
    <line x1="2" y1="12" x2="22" y2="12" />
    <line x1="2" y1="7" x2="7" y2="7" />
    <line x1="2" y1="17" x2="7" y2="17" />
    <line x1="17" y1="17" x2="22" y2="17" />
    <line x1="17" y1="7" x2="22" y2="7" />
  </Icon>
);

export const IconMusic = ({ size = 20 }: { size?: number } = {}) => (
  <Icon size={size}>
    <path d="M9 18V5l12-2v13" />
    <circle cx="6" cy="18" r="3" />
    <circle cx="18" cy="16" r="3" />
  </Icon>
);

export const IconZap = () => (
  <Icon size={16}>
    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
  </Icon>
);

export const IconSearch = () => (
  <Icon size={16}>
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  </Icon>
);

export const IconRewind = ({ size = 20 }: { size?: number } = {}) => (
  <Icon size={size}>
    <polygon points="11 19 2 12 11 5 11 19" />
    <polygon points="22 19 13 12 22 5 22 19" />
  </Icon>
);

export const IconFastForward = ({ size = 20 }: { size?: number } = {}) => (
  <Icon size={size}>
    <polygon points="13 19 22 12 13 5 13 19" />
    <polygon points="2 19 11 12 2 5 2 19" />
  </Icon>
);

export const IconStop = () => (
  <Icon>
    <rect x="4" y="4" width="16" height="16" rx="2" />
  </Icon>
);

export const IconGrid = () => (
  <Icon size={18}>
    <rect x="3" y="3" width="7" height="7" rx="1" />
    <rect x="14" y="3" width="7" height="7" rx="1" />
    <rect x="14" y="14" width="7" height="7" rx="1" />
    <rect x="3" y="14" width="7" height="7" rx="1" />
  </Icon>
);

export const IconDpad = () => (
  <Icon size={18}>
    <rect x="9" y="3" width="6" height="18" rx="2" />
    <rect x="3" y="9" width="18" height="6" rx="2" />
    <circle cx="12" cy="12" r="2" fill="currentColor" />
  </Icon>
);

export const IconArrowUp = () => (
  <Icon size={14}>
    <path d="M12 19V5M5 12l7-7 7 7" />
  </Icon>
);

export const IconArrowDown = () => (
  <Icon size={14}>
    <path d="M12 5v14M19 12l-7 7-7-7" />
  </Icon>
);

export const IconCast = () => (
  <Icon>
    <path d="M2 16.1A5 5 0 017.9 22M2 12.05A9 9 0 0111.95 22M2 8a13 13 0 0113.9 14M2 20h.01" />
    <path d="M21 8V5a2 2 0 00-2-2H5a2 2 0 00-2 2v1" />
    <path d="M21 12v7a2 2 0 01-2 2h-3" />
  </Icon>
);

export const IconRefresh = ({ size = 15 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
  </Icon>
);

export const IconX = ({ size = 14 }: { size?: number }) => (
  <Icon size={size}>
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </Icon>
);

export const IconBluetooth = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M6.5 6.5l11 11L12 23V1l5.5 5.5-11 11" />
  </Icon>
);

export const IconHeadphones = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M3 18v-6a9 9 0 0 1 18 0v6" />
    <path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z" />
  </Icon>
);

export const IconGamepad = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <line x1="6" y1="12" x2="10" y2="12" />
    <line x1="8" y1="10" x2="8" y2="14" />
    <line x1="15" y1="13" x2="15.01" y2="13" />
    <line x1="18" y1="11" x2="18.01" y2="11" />
    <rect x="2" y="6" width="20" height="12" rx="6" />
  </Icon>
);

export const IconSignal = ({ size = 16 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M2 20h.01" />
    <path d="M7 20v-4" />
    <path d="M12 20v-8" />
    <path d="M17 20V8" />
    <path d="M22 20V4" />
  </Icon>
);

export const IconUpload = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
  </Icon>
);

export const IconPackage = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <line x1="16.5" y1="9.4" x2="7.5" y2="4.21" />
    <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
    <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
    <line x1="12" y1="22.08" x2="12" y2="12" />
  </Icon>
);

export const IconFolder = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
  </Icon>
);

export const IconFile = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </Icon>
);

export const IconHardDrive = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <line x1="22" y1="12" x2="2" y2="12" />
    <path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
    <line x1="6" y1="16" x2="6.01" y2="16" />
    <line x1="10" y1="16" x2="10.01" y2="16" />
  </Icon>
);

export const IconPause = ({ size = 20 }: { size?: number }) => (
  <Icon size={size}>
    <rect x="6" y="4" width="4" height="16" rx="1" fill="currentColor" />
    <rect x="14" y="4" width="4" height="16" rx="1" fill="currentColor" />
  </Icon>
);



