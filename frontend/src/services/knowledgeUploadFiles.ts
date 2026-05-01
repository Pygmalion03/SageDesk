const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "gif", "bmp", "webp"]);
const DOCUMENT_EXTENSIONS = new Set(["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "markdown"]);
const SUPPORTED_EXTENSIONS = new Set([...IMAGE_EXTENSIONS, ...DOCUMENT_EXTENSIONS]);

export const SUPPORTED_UPLOAD_ACCEPT = [
  ".pdf",
  ".doc",
  ".docx",
  ".xls",
  ".xlsx",
  ".ppt",
  ".pptx",
  ".txt",
  ".md",
  ".markdown",
  ".png",
  ".jpg",
  ".jpeg",
  ".gif",
  ".bmp",
  ".webp"
].join(",");

export interface SupportedUploadFileResult {
  supported: File[];
  rejected: File[];
}

export function filterSupportedUploadFiles(files: Iterable<File>): SupportedUploadFileResult {
  const supported: File[] = [];
  const rejected: File[] = [];

  for (const file of files) {
    if (isSupportedUploadFile(file)) {
      supported.push(file);
    } else {
      rejected.push(file);
    }
  }

  return { supported, rejected };
}

export function isSupportedUploadFile(file: File): boolean {
  return SUPPORTED_EXTENSIONS.has(getUploadFileExtension(file));
}

export function isPreviewableUploadImage(file: File): boolean {
  return file.type.startsWith("image/") || IMAGE_EXTENSIONS.has(getUploadFileExtension(file));
}

export function getUploadFileDisplayName(file: File): string {
  const relativePath = getUploadFileRelativePath(file);
  return relativePath || file.name;
}

function getUploadFileRelativePath(file: File): string {
  const withPath = file as File & { webkitRelativePath?: string };
  return withPath.webkitRelativePath || "";
}

function getUploadFileExtension(file: File): string {
  const name = file.name || getUploadFileRelativePath(file);
  const lastDot = name.lastIndexOf(".");
  return lastDot >= 0 ? name.slice(lastDot + 1).toLowerCase() : "";
}
