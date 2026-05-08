export function resolveMarkdownImageSrc(src: string | null | undefined, apiBaseUrl = readApiBaseUrl()) {
  if (!src) {
    return src;
  }

  const value = src.trim();
  if (!value) {
    return src;
  }

  if (value.startsWith(MEDIA_PREVIEW_PATH)) {
    return `${normalizeApiBaseUrl(apiBaseUrl)}${value}`;
  }

  if (isBrowserLoadableImageSrc(value)) {
    return value;
  }

  if (shouldProxyImageSrc(value)) {
    return `${normalizeApiBaseUrl(apiBaseUrl)}${MEDIA_PREVIEW_PATH}?uri=${encodeURIComponent(value)}`;
  }

  return value;
}

const MEDIA_PREVIEW_PATH = "/rag/media/preview";

function readApiBaseUrl() {
  const meta = import.meta as ImportMeta & { env?: Record<string, string | undefined> };
  return meta.env?.VITE_API_BASE_URL || "";
}

function normalizeApiBaseUrl(apiBaseUrl: string) {
  return apiBaseUrl.replace(/\/+$/, "");
}

function isBrowserLoadableImageSrc(src: string) {
  return (
    src.startsWith("http://") ||
    src.startsWith("https://") ||
    src.startsWith("data:") ||
    src.startsWith("blob:") ||
    src.startsWith("/api/")
  );
}

function shouldProxyImageSrc(src: string) {
  return (
    src.startsWith("s3://") ||
    src.startsWith("file:/") ||
    isWindowsAbsolutePath(src) ||
    isServerOrWorkspacePath(src)
  );
}

function isWindowsAbsolutePath(src: string) {
  return /^[A-Za-z]:[\\/]/.test(src);
}

function isServerOrWorkspacePath(src: string) {
  if (src.startsWith("//")) {
    return false;
  }
  return src.startsWith("/") || /^[./]*(bootstrap|frontend|resources|scripts|data)[\\/]/.test(src);
}
