const trimEnv = (value?: string) => value?.trim() ?? "";

export const APP_NAME = trimEnv(import.meta.env.VITE_APP_NAME) || "SageDesk Knowledge Assistant";
export const APP_SHORT_NAME = trimEnv(import.meta.env.VITE_APP_SHORT_NAME) || "SageDesk";
export const APP_TAGLINE = trimEnv(import.meta.env.VITE_APP_TAGLINE) || "Knowledge Assistant";
export const APP_REPO_URL = trimEnv(import.meta.env.VITE_APP_REPO_URL);
export const APP_DOCS_URL = trimEnv(import.meta.env.VITE_APP_DOCS_URL);
export const APP_COMMUNITY_URL = trimEnv(import.meta.env.VITE_APP_COMMUNITY_URL);
