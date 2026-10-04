/** Browse this computer as a tree (restricted by the backend to $HOME and the allowed
 *  folders). Folders load when opened; pressing a file picks it ("media" mode), pressing
 *  a folder that already holds an analysis picks that folder ("dir" mode). Full
 *  keyboard support comes from React Aria's Tree: arrows move and open, Enter picks. */

import {
  ArrowUp,
  ChevronRight,
  FileAudio,
  FileVideo,
  Folder,
  FolderCheck,
  FolderOpen,
  LoaderCircle,
} from "lucide-react";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Button as AriaButton, Tree, TreeItem, TreeItemContent, type Key } from "react-aria-components";

import { api } from "../../api";
import { fmtBytes } from "../../lib/format";
import type { Browse, BrowseEntry } from "../../types";
import { cn, IconButton, Notice } from "../../ui";

const VIDEO_EXT = /\.(mp4|mkv|webm|mov|avi|m4v)$/i;

type Loaded = Browse | "loading" | { error: string };

interface Props {
  /** "media": pick a video or audio file · "dir": pick a folder that holds analysis.json */
  mode: "media" | "dir";
  onPick: (path: string) => void;
}

const message = (err: unknown) => (err instanceof Error ? err.message : String(err));

export function PathBrowser({ mode, onPick }: Props) {
  const [root, setRoot] = useState<Loaded>("loading");
  const [children, setChildren] = useState<Record<string, Loaded>>({});
  const [expanded, setExpanded] = useState<Set<Key>>(new Set());

  const openRoot = useCallback(async (path?: string) => {
    setRoot("loading");
    setChildren({});
    setExpanded(new Set());
    try {
      setRoot(await api.browse(path));
    } catch (err) {
      setRoot({ error: message(err) });
    }
  }, []);

  useEffect(() => {
    void openRoot();
  }, [openRoot]);

  const load = async (path: string) => {
    setChildren((current) => ({ ...current, [path]: "loading" }));
    try {
      const listing = await api.browse(path);
      setChildren((current) => ({ ...current, [path]: listing }));
    } catch (err) {
      setChildren((current) => ({ ...current, [path]: { error: message(err) } }));
    }
  };

  const changeExpanded = (keys: Set<Key>) => {
    for (const key of keys) {
      const path = String(key);
      if (!expanded.has(key) && children[path] === undefined) void load(path);
    }
    setExpanded(new Set(keys));
  };

  if (root === "loading") {
    return (
      <p className="flex items-center gap-2 text-sm text-ink-2" role="status">
        <LoaderCircle size={16} aria-hidden="true" className="motion-ok:animate-spin" />
        Loading the folders…
      </p>
    );
  }
  if ("error" in root) {
    return (
      <Notice tone="caution" title="This folder cannot be opened">
        {root.error}
      </Notice>
    );
  }

  const renderListing = (listing: Loaded, parent: string): ReactNode[] => {
    if (listing === "loading") return [placeholder(`${parent}::loading`, "Loading…", true)];
    if ("error" in listing) return [placeholder(`${parent}::error`, `Cannot open: ${listing.error}`)];
    const dirs = listing.dirs.map((dir) => folderItem(dir));
    const files = mode === "media" ? listing.files.map((file) => fileItem(file)) : [];
    const items = [...dirs, ...files];
    if (items.length === 0) {
      items.push(
        placeholder(
          `${parent}::empty`,
          mode === "media" ? "There is no video or audio here." : "No folders here.",
        ),
      );
    }
    return items;
  };

  const folderItem = (dir: BrowseEntry) => {
    const pickable = mode === "dir" && Boolean(dir.has_analysis);
    const isOpen = expanded.has(dir.path);
    const Icon = pickable ? FolderCheck : isOpen ? FolderOpen : Folder;
    return (
      <TreeItem
        key={dir.path}
        id={dir.path}
        textValue={dir.name}
        hasChildItems
        // a plain folder toggles open on press (React Aria's default); one holding
        // results is picked instead (it still opens with the chevron or the arrows)
        onAction={pickable ? () => onPick(dir.path) : undefined}
        className={rowClass}
      >
        <TreeItemContent>
          {({ isExpanded, level }) => (
            <Row level={level}>
              <AriaButton
                slot="chevron"
                className="flex size-6 shrink-0 items-center justify-center rounded-control text-ink-2 outline-none hover:bg-surface"
              >
                <ChevronRight
                  size={16}
                  aria-hidden="true"
                  className={cn("transition-transform duration-(--dur-fast)", isExpanded && "rotate-90")}
                />
              </AriaButton>
              <Icon size={18} aria-hidden="true" className="shrink-0 text-ink-2" />
              <span className="truncate">{dir.name}</span>
              {pickable && (
                <span className="ml-auto shrink-0 text-xs font-medium text-ink-2">
                  has results · press to import
                </span>
              )}
            </Row>
          )}
        </TreeItemContent>
        {/* always present, even collapsed and unloaded: React Aria only treats a row as
            expandable when it has child rows (the placeholder is never shown closed) */}
        {renderListing(children[dir.path] ?? "loading", dir.path)}
      </TreeItem>
    );
  };

  const fileItem = (file: BrowseEntry) => {
    const Icon = VIDEO_EXT.test(file.name) ? FileVideo : FileAudio;
    return (
      <TreeItem
        key={file.path}
        id={file.path}
        textValue={file.name}
        onAction={() => onPick(file.path)}
        className={rowClass}
      >
        <TreeItemContent>
          {({ level }) => (
            <Row level={level}>
              <span className="size-6 shrink-0" aria-hidden="true" />
              <Icon size={18} aria-hidden="true" className="shrink-0 text-ink-2" />
              <span className="truncate">{file.name}</span>
              <span className="ml-auto shrink-0 text-xs tabular-nums text-ink-muted">
                {fmtBytes(file.size)}
              </span>
            </Row>
          )}
        </TreeItemContent>
      </TreeItem>
    );
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        {root.parent && (
          <IconButton
            icon={ArrowUp}
            label="Up one folder"
            size="sm"
            onPress={() => void openRoot(root.parent!)}
          />
        )}
        <span className="min-w-0 break-all font-mono text-xs text-ink-2">{root.path}</span>
      </div>
      <Tree
        aria-label={mode === "media" ? "Folders and media files" : "Folders with results"}
        expandedKeys={expanded}
        onExpandedChange={changeExpanded}
        className="relative max-h-80 overflow-auto rounded-card bg-surface p-1 shadow-[inset_0_0_0_1px_var(--line-strong)] outline-none"
      >
        {renderListing(root, root.path)}
      </Tree>
      <p className="text-xs text-ink-muted">
        {mode === "media"
          ? "Open folders with the arrow keys or the chevron; press a file to choose it."
          : "Folders marked “has results” contain an analysis.json; press one to import it."}
      </p>
    </div>
  );
}

const rowClass =
  "group cursor-default rounded-control text-sm text-ink outline-none data-[focused]:bg-surface-2 data-[hovered]:bg-surface-2 data-[disabled]:text-ink-muted";

function Row({ level, children }: { level: number; children: ReactNode }) {
  return (
    <div className="flex min-h-9 items-center gap-2 py-1 pr-2" style={{ paddingLeft: `${(level - 1) * 1.25 + 0.25}rem` }}>
      {children}
    </div>
  );
}

function placeholder(id: string, text: string, loading = false) {
  return (
    <TreeItem key={id} id={id} textValue={text} isDisabled className={rowClass}>
      <TreeItemContent>
        {({ level }) => (
          <Row level={level}>
            <span className="size-6 shrink-0" aria-hidden="true" />
            {loading && <LoaderCircle size={16} aria-hidden="true" className="motion-ok:animate-spin text-ink-2" />}
            <span className="text-ink-muted">{text}</span>
          </Row>
        )}
      </TreeItemContent>
    </TreeItem>
  );
}
