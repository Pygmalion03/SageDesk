import React from "react";
import ReactDOM from "react-dom/client";

import App from "@/App";
import { APP_NAME } from "@/config/app";
import { useAuthStore } from "@/stores/authStore";
import { useThemeStore } from "@/stores/themeStore";
import "@/styles/globals.css";

useThemeStore.getState().initialize();
useAuthStore.getState().checkAuth();
document.title = APP_NAME;

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
