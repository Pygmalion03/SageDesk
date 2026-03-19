import * as React from "react";
import { Github, Menu } from "lucide-react";

import { APP_REPO_URL, APP_SHORT_NAME } from "@/config/app";
import { Button } from "@/components/ui/button";
import { useChatStore } from "@/stores/chatStore";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const { currentSessionId, sessions } = useChatStore();
  const currentSession = sessions.find((session) => session.id === currentSessionId);

  return (
    <header className="sticky top-0 z-20 bg-white">
      <div className="flex h-16 items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            onClick={onToggleSidebar}
            aria-label="切换侧边栏"
            className="text-gray-500 hover:bg-gray-100 lg:hidden"
          >
            <Menu className="h-5 w-5" />
          </Button>
          <p className="text-base font-medium text-gray-900">
            {currentSession?.title || "新对话"}
          </p>
        </div>
        {APP_REPO_URL ? (
          <div className="flex items-center gap-2">
            <a
              href={APP_REPO_URL}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-2 rounded-xl border border-gray-200 px-3 py-1.5 text-sm text-gray-600 transition hover:bg-gray-100 hover:text-gray-900"
              aria-label={`打开 ${APP_SHORT_NAME} 项目仓库`}
            >
              <Github className="h-4 w-4" />
              <span className="font-medium">项目仓库</span>
            </a>
          </div>
        ) : null}
      </div>
    </header>
  );
}
