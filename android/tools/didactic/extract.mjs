#!/usr/bin/env node
// PhonoTrainer didactic content extractor (spec S-18 and D-09, architecture 15).
//
// The glossary, the Learn content, the IPA chart layout and the articulation table exist
// only in the web client's TypeScript. The phone shows the same text, so this script
// imports those files READ-ONLY (Node's built-in type stripping, no npm dependency) and
// writes them as one versioned JSON asset for the Android build. `--check` regenerates the
// JSON in memory and fails on any byte difference with the bundled asset: that is the
// drift check `./gradlew :core:content:verifyDidacticContent` runs.
//
// It depends on the shape of the web sources on purpose: anything it does not recognise
// (a new field, a new link target, a new import) fails the run with a message that names
// it, instead of being copied or dropped silently.
//
// Usage (from the repository root or anywhere else; paths are resolved from this file):
//   node android/tools/didactic/extract.mjs            write the asset
//   node android/tools/didactic/extract.mjs --check    exit 1 if the asset is stale
// Options:
//   --root <dir>   repository whose web/src is read (default: the one holding this script)
//   --out <file>   asset to write or check (default: DEFAULT_OUT below)
// Exit status: 0 written or up to date, 1 stale asset (--check), 2 refused or failed.
//
// It never writes under web/: an output path that resolves inside web/ (of this
// repository or of --root, symbolic links followed) is refused before anything is read.

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { register } from "node:module";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

import { SCHEMA, schemaErrors } from "./schema.mjs";

export { SCHEMA };

const SCRIPT = fileURLToPath(import.meta.url);
/** The repository that holds this script (android/tools/didactic/extract.mjs). */
export const REPO = resolve(dirname(SCRIPT), "..", "..", "..");
export const DEFAULT_OUT = join(REPO, "android", "core", "content", "src", "main", "assets", "didactic", "didactic.json");

/** The web inputs (architecture 15.1). Every `.ts` file of the glossary folder is read
 *  too (tests excluded); the resolve hook refuses any import outside this set. */
const GLOSSARY_DIR = "web/src/didactic/glossary";
const FIXED_INPUTS = [
  "web/src/features/learn/content.ts",
  "web/src/features/learn/ipaLayout.ts",
  "web/src/articulation/phones.ts",
  "web/src/articulation/pose.ts",
  "web/src/paths.ts",
];

/** The articulators of `Pose` (web/src/articulation/pose.ts), in the web's order. The app's
 *  model (`Gesture.pose`) depends on exactly these names, so a change fails the run. */
export const POSE_KEYS = [
  "jaw", "lipOpen", "lipRound", "lipTuck", "body", "height", "tip", "tipFront", "root", "velum", "voice",
];

/** `Manner` of web/src/articulation/phones.ts. */
export const MANNERS = [
  "vowel", "diphthong", "stop", "unreleased", "nasal", "fricative", "affricate", "approximant",
  "lateral", "tap", "glottal",
];

const HREF_TABLE =
  '"/learn", "/learn/<change>", "/learn/ipa" with an optional "symbol", "/practice" with an ' +
  'optional "focus", and "/insights"';

const NODE_REQUIRED = "Node 22.18 or newer is required to extract the didactic content (the web build already needs it)";

export class ExtractError extends Error {}

const fail = (where, message) => {
  throw new ExtractError(`${where}: ${message}`);
};

// --- paths -----------------------------------------------------------------------------

/** realpath of a path that may not exist yet: the deepest existing ancestor is resolved
 *  (symbolic links followed) and the rest appended. */
export function realpathLoose(path) {
  const absolute = resolve(path);
  const rest = [];
  let current = absolute;
  for (;;) {
    try {
      return join(realpathSync(current), ...rest.reverse());
    } catch (error) {
      if (error.code !== "ENOENT" && error.code !== "ENOTDIR") throw error;
      const parent = dirname(current);
      if (parent === current) return absolute;
      rest.push(basename(current));
      current = parent;
    }
  }
}

const isInside = (path, folder) => path === folder || path.startsWith(folder.endsWith(sep) ? folder : folder + sep);

/** Why `out` may not be written, or null. It may not be inside the web/ folder of any of
 *  `roots` (the repositories involved), compared after resolving symbolic links. */
export function webOutputRefusal(out, roots) {
  const target = realpathLoose(out);
  for (const root of roots) {
    const web = realpathLoose(join(root, "web"));
    if (isInside(target, web)) {
      return `refusing to use ${out}: it resolves inside ${web}, and the extractor never writes under web/ (the web client belongs to another project)`;
    }
  }
  return null;
}

/** The input files of `root`, relative to it, sorted. */
export function inputFiles(root) {
  const folder = join(root, GLOSSARY_DIR);
  let names;
  try {
    names = readdirSync(folder);
  } catch {
    throw new ExtractError(`${GLOSSARY_DIR} is missing under ${root}`);
  }
  const glossary = names
    .filter((name) => name.endsWith(".ts") && !name.endsWith(".d.ts") && !/\.test\.ts$/.test(name))
    .map((name) => `${GLOSSARY_DIR}/${name}`);
  const files = [...glossary, ...FIXED_INPUTS].sort(compareKeys);
  for (const file of [...files, `${GLOSSARY_DIR}/index.ts`, `${GLOSSARY_DIR}/ipa.ts`]) {
    if (!existsSync(join(root, file))) throw new ExtractError(`${file} is missing under ${root}`);
  }
  return files;
}

// --- loading the web sources --------------------------------------------------------------

function ensureTypeScript(argv) {
  if (process.features.typescript || process.env.PHONO_DIDACTIC_REEXEC === "1") return;
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major > 22 || (major === 22 && minor >= 6)) {
    // Older 22.x and 23.x strip types only behind this flag: run again with it.
    const result = spawnSync(
      process.execPath,
      [...process.execArgv, "--experimental-strip-types", SCRIPT, ...argv],
      { stdio: "inherit", env: { ...process.env, PHONO_DIDACTIC_REEXEC: "1" } },
    );
    process.exit(result.status ?? 2);
  }
  throw new ExtractError(`${NODE_REQUIRED}; this is Node ${process.versions.node}`);
}

/** Type stripping announces itself once per process; that notice is not news here. */
function quietTypeStrippingNotice() {
  const listeners = process.listeners("warning");
  process.removeAllListeners("warning");
  process.on("warning", (warning) => {
    if (warning.name === "ExperimentalWarning" && /type stripping|strip-types/i.test(warning.message)) return;
    for (const listener of listeners) listener(warning);
  });
}

async function loadWeb(root, files) {
  const url = (file) => pathToFileURL(join(root, file)).href;
  register(new URL("./ts-resolve-hook.mjs", import.meta.url), {
    data: {
      webSrcUrl: pathToFileURL(join(root, "web", "src") + sep).href,
      rootUrl: pathToFileURL(root + sep).href,
      allowed: files.map(url),
    },
  });
  const load = async (file, names) => {
    const module = await import(url(file));
    for (const name of names) {
      if (!(name in module)) fail(file, `no longer exports ${name}, which the extractor reads`);
    }
    return module;
  };
  return {
    glossary: await load(`${GLOSSARY_DIR}/index.ts`, ["GLOSSARY"]),
    ipa: await load(`${GLOSSARY_DIR}/ipa.ts`, ["SYMBOL_PHENOMENON"]),
    content: await load("web/src/features/learn/content.ts", ["LESSONS"]),
    layout: await load("web/src/features/learn/ipaLayout.ts", [
      "PLACES", "CONSONANT_ROWS", "ALLOPHONE_PLACES", "ALLOPHONE_ROWS", "SYLLABICS", "VOWEL_COLUMNS",
      "VOWEL_ROWS", "REDUCED_VOWELS", "GLIDING_GROUPS", "placedSymbols", "unplacedSymbols",
    ]),
    phones: await load("web/src/articulation/phones.ts", ["PHONE_TABLE", "lookupPhone", "bareSymbol"]),
    pose: await load("web/src/articulation/pose.ts", ["POSE_KEYS"]),
  };
}

// --- shape checks ------------------------------------------------------------------------

function describe(value) {
  if (value === null) return "null";
  if (Array.isArray(value)) return "an array";
  if (value instanceof Set) return "a Set";
  if (value instanceof Map) return "a Map";
  return typeof value === "object" ? "an object" : `${typeof value} ${JSON.stringify(value)}`;
}

function object(value, where) {
  const plain = value !== null && typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype;
  if (!plain) fail(where, `expected a plain object, found ${describe(value)}`);
  return value;
}

/** A plain object with exactly these fields (optional ones may be absent). */
function fields(value, where, required, optional = []) {
  object(value, where);
  for (const key of Object.keys(value)) {
    if (!required.includes(key) && !optional.includes(key)) {
      fail(where, `unexpected field "${key}": the extractor does not know it (teach it, and the app's model, first)`);
    }
  }
  for (const key of required) if (value[key] === undefined) fail(where, `missing field "${key}"`);
  return value;
}

function text(value, where, { empty = false } = {}) {
  if (typeof value !== "string") fail(where, `expected a string, found ${describe(value)}`);
  if (!empty && value.trim() === "") fail(where, "is empty");
  return value;
}

const optionalText = (value, where) => (value === undefined ? null : text(value, where));

function list(value, where) {
  if (!Array.isArray(value)) fail(where, `expected an array, found ${describe(value)}`);
  return value;
}

const texts = (value, where) => list(value, where).map((item, i) => text(item, `${where}[${i}]`));

function number(value, where) {
  if (typeof value !== "number" || !Number.isFinite(value)) fail(where, `expected a finite number, found ${describe(value)}`);
  return value;
}

function bool(value, where) {
  if (typeof value !== "boolean") fail(where, `expected a boolean, found ${describe(value)}`);
  return value;
}

/** Map a plain object's entries in key order. */
function entries(value, where, map) {
  const out = {};
  for (const key of Object.keys(object(value, where)).sort(compareKeys)) {
    if (key.trim() === "") fail(where, "has an empty key");
    out[key] = map(value[key], `${where}[${JSON.stringify(key)}]`, key);
  }
  return out;
}

// --- links -------------------------------------------------------------------------------

/** A web `href` as an app link target (architecture 15.1). Anything outside the table
 *  fails: the phone has no route for it. */
export function linkTarget(href, where, { changes, symbols }) {
  const unknown = (why) =>
    fail(where, `unknown href "${href}"${why ? ` (${why})` : ""}; the app maps only ${HREF_TABLE} (architecture 15.1)`);
  text(href, where);
  if (!href.startsWith("/") || href.startsWith("//")) unknown("not a path inside the app");
  const base = "http://app.invalid";
  let url;
  try {
    url = new URL(href, base);
  } catch {
    unknown("not a valid path");
  }
  if (url.origin !== base || url.hash !== "" || href.includes("#")) unknown("not a plain app path");
  const names = [...url.searchParams.keys()];
  const only = (...allowed) => {
    for (const name of names) if (!allowed.includes(name)) unknown(`unexpected query parameter "${name}"`);
    if (new Set(names).size !== names.length) unknown("repeated query parameter");
  };
  const param = (name) => {
    const value = url.searchParams.get(name);
    if (value === "") unknown(`empty "${name}"`);
    return value;
  };
  const change = (label) => {
    if (!changes.has(label)) unknown(`"${label}" is not one of the changes of LESSONS`);
    return label;
  };

  const path = url.pathname;
  if (path === "/learn") {
    only();
    return { kind: "learn" };
  }
  if (path === "/learn/ipa") {
    only("symbol");
    const symbol = param("symbol");
    if (symbol !== null && !symbols.has(symbol)) unknown(`"${symbol}" is not in the articulation table`);
    return { kind: "ipa", symbol };
  }
  if (path === "/practice") {
    only("focus");
    const focus = param("focus");
    return { kind: "practice", focus: focus === null ? null : change(focus) };
  }
  if (path === "/insights") {
    only();
    return { kind: "insights" };
  }
  const phenomenon = /^\/learn\/([^/]+)$/.exec(path);
  if (phenomenon) {
    only();
    let label;
    try {
      label = decodeURIComponent(phenomenon[1]);
    } catch {
      unknown("badly encoded change name");
    }
    return { kind: "phenomenon", label: change(label) };
  }
  return unknown();
}

// --- building the asset ----------------------------------------------------------------------

function glossaryEntry(entry, where, known) {
  fields(entry, where, ["title", "body"], ["example", "learnMore"]);
  let learnMore = null;
  let learnMoreLabel = null;
  if (entry.learnMore !== undefined) {
    const link = fields(entry.learnMore, `${where}.learnMore`, ["href"], ["label"]);
    learnMore = linkTarget(link.href, `${where}.learnMore.href`, known);
    learnMoreLabel = optionalText(link.label, `${where}.learnMore.label`);
  }
  return {
    title: text(entry.title, `${where}.title`),
    body: text(entry.body, `${where}.body`),
    example: optionalText(entry.example, `${where}.example`),
    learnMore,
    learnMoreLabel,
  };
}

function lesson(value, where, lookupPhone) {
  fields(value, where, ["listenFor"], ["demo", "spanishTip", "note"]);
  return {
    listenFor: text(value.listenFor, `${where}.listenFor`),
    demo: value.demo === undefined ? null : mouthDemo(value.demo, `${where}.demo`, lookupPhone),
    spanishTip: optionalText(value.spanishTip, `${where}.spanishTip`),
    note: optionalText(value.note, `${where}.note`),
  };
}

function mouthDemo(value, where, lookupPhone) {
  fields(value, where, ["word", "dictionary", "said", "from", "to"]);
  const phone = (symbol, at) => {
    text(symbol, at);
    if (lookupPhone(symbol) === null) fail(at, `"${symbol}" is not in the articulation table (web/src/articulation/phones.ts)`);
    return symbol;
  };
  const dictionary = list(value.dictionary, `${where}.dictionary`).map((s, i) => phone(s, `${where}.dictionary[${i}]`));
  const said = list(value.said, `${where}.said`).map((s, i) => phone(s, `${where}.said[${i}]`));
  const from = phone(value.from, `${where}.from`);
  const to = value.to === null ? null : phone(value.to, `${where}.to`);
  if (!dictionary.includes(from)) fail(`${where}.from`, `"${from}" is not in the dictionary form`);
  if (to !== null && !said.includes(to)) fail(`${where}.to`, `"${to}" is not in what was said`);
  return { word: text(value.word, `${where}.word`), dictionary, said, from, to };
}

function columns(value, where) {
  return list(value, where).map((column, i) => {
    const at = `${where}[${i}]`;
    fields(column, at, ["key", "label"], ["detail"]);
    return {
      key: text(column.key, `${at}.key`),
      label: text(column.label, `${at}.label`),
      // The web leaves some columns without a description; "" means none, as in the rows.
      detail: column.detail === undefined ? "" : text(column.detail, `${at}.detail`, { empty: true }),
    };
  });
}

function gridRows(value, where, columnKeys) {
  return list(value, where).map((row, i) => {
    const at = `${where}[${i}]`;
    fields(row, at, ["label", "detail", "cells"]);
    return {
      label: text(row.label, `${at}.label`),
      detail: text(row.detail, `${at}.detail`, { empty: true }),
      cells: entries(row.cells, `${at}.cells`, (symbols, cellAt, key) => {
        if (!columnKeys.has(key)) fail(cellAt, `"${key}" is not one of this grid's columns`);
        return texts(symbols, cellAt);
      }),
    };
  });
}

function ipaLayout(m, file) {
  const at = (name) => `${file} ${name}`;
  const places = columns(m.PLACES, at("PLACES"));
  const placeOf = new Map(places.map((place) => [place.key, place]));
  const allophonePlaces = texts(m.ALLOPHONE_PLACES, at("ALLOPHONE_PLACES")).map((key, i) => {
    const place = placeOf.get(key);
    if (!place) fail(`${at("ALLOPHONE_PLACES")}[${i}]`, `"${key}" is not one of PLACES`);
    return place;
  });
  const vowelColumns = columns(m.VOWEL_COLUMNS, at("VOWEL_COLUMNS"));
  if (!(m.REDUCED_VOWELS instanceof Set) && !Array.isArray(m.REDUCED_VOWELS)) {
    fail(at("REDUCED_VOWELS"), `expected a Set or an array, found ${describe(m.REDUCED_VOWELS)}`);
  }
  if (typeof m.placedSymbols !== "function" || typeof m.unplacedSymbols !== "function") {
    fail(file, "placedSymbols and unplacedSymbols must be functions");
  }
  return {
    places,
    consonantRows: gridRows(m.CONSONANT_ROWS, at("CONSONANT_ROWS"), new Set(placeOf.keys())),
    allophonePlaces,
    allophoneRows: gridRows(m.ALLOPHONE_ROWS, at("ALLOPHONE_ROWS"), new Set(allophonePlaces.map((p) => p.key))),
    syllabics: texts(m.SYLLABICS, at("SYLLABICS")),
    vowelColumns,
    vowelRows: gridRows(m.VOWEL_ROWS, at("VOWEL_ROWS"), new Set(vowelColumns.map((c) => c.key))),
    reducedVowels: texts([...m.REDUCED_VOWELS], at("REDUCED_VOWELS")),
    glidingGroups: list(m.GLIDING_GROUPS, at("GLIDING_GROUPS")).map((group, i) => {
      const where = `${at("GLIDING_GROUPS")}[${i}]`;
      fields(group, where, ["label", "detail", "symbols"]);
      return {
        label: text(group.label, `${where}.label`),
        detail: text(group.detail, `${where}.detail`, { empty: true }),
        symbols: texts(group.symbols, `${where}.symbols`),
      };
    }),
    placed: texts(m.placedSymbols(), `${file} placedSymbols()`),
    unplaced: texts(m.unplacedSymbols(), `${file} unplacedSymbols()`),
  };
}

function pose(value, where, { partial }) {
  object(value, where);
  if (partial) fields(value, where, [], POSE_KEYS);
  else fields(value, where, POSE_KEYS);
  if (partial && Object.keys(value).length === 0) fail(where, "is empty");
  const out = {};
  for (const key of Object.keys(value)) out[key] = number(value[key], `${where}.${key}`);
  return out;
}

function phone(value, where) {
  fields(value, where, ["symbol", "name", "example", "cue", "manner", "voiced", "gestures"], ["carry"]);
  const manner = text(value.manner, `${where}.manner`);
  if (!MANNERS.includes(manner)) fail(`${where}.manner`, `"${manner}" is not one of ${MANNERS.join(", ")}`);
  return {
    symbol: text(value.symbol, `${where}.symbol`),
    name: text(value.name, `${where}.name`),
    example: text(value.example, `${where}.example`),
    cue: text(value.cue, `${where}.cue`),
    manner,
    voiced: bool(value.voiced, `${where}.voiced`),
    gestures: list(value.gestures, `${where}.gestures`).map((gesture, i) => {
      const at = `${where}.gestures[${i}]`;
      fields(gesture, at, ["at", "pose"]);
      const time = number(gesture.at, `${at}.at`);
      if (time < 0 || time > 1) fail(`${at}.at`, `${time} is outside 0..1`);
      return { at: time, pose: pose(gesture.pose, `${at}.pose`, { partial: false }) };
    }),
    carry: value.carry === undefined ? null : pose(value.carry, `${where}.carry`, { partial: true }),
  };
}

/** The asset, as a plain JSON value. */
export function buildContent(root, files, web) {
  const phonesFile = "web/src/articulation/phones.ts";
  const poseKeys = list(web.pose.POSE_KEYS, "web/src/articulation/pose.ts POSE_KEYS");
  if ([...poseKeys].sort().join() !== [...POSE_KEYS].sort().join()) {
    fail("web/src/articulation/pose.ts POSE_KEYS", `the articulators changed (${poseKeys.join(", ")}); the app's Gesture model knows ${POSE_KEYS.join(", ")}`);
  }
  // The app looks a phone up the way lookupPhone does: stress and length marks removed.
  const { bareSymbol, lookupPhone } = web.phones;
  if (typeof bareSymbol !== "function" || typeof lookupPhone !== "function") {
    fail(phonesFile, "bareSymbol and lookupPhone must be functions");
  }
  if (bareSymbol("ˈaːˌ") !== "a" || bareSymbol("t̚ɾ̃n̩") !== "t̚ɾ̃n̩") {
    fail(`${phonesFile} bareSymbol`, "no longer removes exactly the marks ˈ ˌ ː, which the app's phone lookup mirrors");
  }

  const phones = list(web.phones.PHONE_TABLE, `${phonesFile} PHONE_TABLE`).map((p, i) =>
    phone(p, `${phonesFile} PHONE_TABLE[${i}]`),
  );
  const symbols = new Set();
  for (const { symbol } of phones) {
    if (symbols.has(symbol)) fail(`${phonesFile} PHONE_TABLE`, `"${symbol}" appears twice`);
    symbols.add(symbol);
  }

  const contentFile = "web/src/features/learn/content.ts";
  const lessons = entries(web.content.LESSONS, `${contentFile} LESSONS`, (value, where) =>
    lesson(value, where, lookupPhone),
  );
  const changes = new Set(Object.keys(lessons));

  const ipaFile = `${GLOSSARY_DIR}/ipa.ts`;
  const symbolPhenomenon = entries(web.ipa.SYMBOL_PHENOMENON, `${ipaFile} SYMBOL_PHENOMENON`, (label, where, symbol) => {
    if (!symbols.has(symbol)) fail(where, `"${symbol}" is not in the articulation table`);
    text(label, where);
    if (!changes.has(label)) fail(where, `"${label}" is not one of the changes of LESSONS`);
    return label;
  });

  const glossary = entries(web.glossary.GLOSSARY, `${GLOSSARY_DIR}/index.ts GLOSSARY`, (entry, where) =>
    glossaryEntry(entry, where, { changes, symbols }),
  );

  return {
    schema: SCHEMA,
    sources: files.map((file) => ({
      path: file,
      sha256: createHash("sha256").update(readFileSync(join(root, file))).digest("hex"),
    })),
    glossary,
    symbolPhenomenon,
    lessons,
    ipaLayout: ipaLayout(web.layout, "web/src/features/learn/ipaLayout.ts"),
    phones,
  };
}

// --- writing ----------------------------------------------------------------------------------

/** Object keys in JavaScript string order (UTF-16 code units), the same on every run. */
function compareKeys(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Deterministic JSON: sorted keys, two-space indent, LF, trailing newline. Does not rely
 *  on JavaScript's property order (integer-like keys would jump ahead). */
export function canonicalJson(value) {
  const write = (item, indent, where) => {
    if (item === null) return "null";
    switch (typeof item) {
      case "string":
        return JSON.stringify(item);
      case "number":
        if (!Number.isFinite(item)) throw new ExtractError(`${where}: ${item} cannot be written as JSON`);
        return JSON.stringify(item);
      case "boolean":
        return item ? "true" : "false";
      case "object": {
        const inner = `${indent}  `;
        if (Array.isArray(item)) {
          if (item.length === 0) return "[]";
          const lines = item.map((element, i) => inner + write(element, inner, `${where}[${i}]`));
          return `[\n${lines.join(",\n")}\n${indent}]`;
        }
        const keys = Object.keys(item).sort(compareKeys);
        if (keys.length === 0) return "{}";
        const lines = keys.map((key) => `${inner}${JSON.stringify(key)}: ${write(item[key], inner, `${where}.${key}`)}`);
        return `{\n${lines.join(",\n")}\n${indent}}`;
      }
      default:
        throw new ExtractError(`${where}: ${typeof item} cannot be written as JSON`);
    }
  };
  return `${write(value, "", "$")}\n`;
}

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const step = (path, key) =>
  typeof key === "number"
    ? `${path}[${key}]`
    : /^[A-Za-z_$][\w$]*$/.test(key)
      ? (path ? `${path}.${key}` : key)
      : `${path}[${JSON.stringify(key)}]`;

/** Where two JSON values differ: "~ path" changed, "+ path" only in `fresh` (new in the web
 *  sources), "- path" only in `bundled` (gone from them). */
export function changedKeys(bundled, fresh, path = "", out = []) {
  if (isObject(bundled) && isObject(fresh)) {
    const keys = [...new Set([...Object.keys(bundled), ...Object.keys(fresh)])].sort(compareKeys);
    for (const key of keys) {
      const at = step(path, key);
      if (!(key in fresh)) out.push(`- ${at}`);
      else if (!(key in bundled)) out.push(`+ ${at}`);
      else changedKeys(bundled[key], fresh[key], at, out);
    }
  } else if (Array.isArray(bundled) && Array.isArray(fresh)) {
    for (let i = 0; i < Math.max(bundled.length, fresh.length); i++) {
      const at = step(path, i);
      if (i >= fresh.length) out.push(`- ${at}`);
      else if (i >= bundled.length) out.push(`+ ${at}`);
      else changedKeys(bundled[i], fresh[i], at, out);
    }
  } else if (!Object.is(bundled, fresh)) {
    out.push(`~ ${path || "(the whole file)"}`);
  }
  return out;
}

const shown = (path) => {
  const relativePath = relative(process.cwd(), path);
  return relativePath && !relativePath.startsWith("..") ? relativePath : path;
};

function summary(content) {
  return (
    `${Object.keys(content.glossary).length} glossary entries, ${Object.keys(content.lessons).length} lessons, ` +
    `${content.phones.length} phones, ${content.sources.length} source files`
  );
}

function check(out, json, content) {
  let bundled;
  try {
    bundled = readFileSync(out);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    console.error(`${shown(out)} does not exist. Run node android/tools/didactic/extract.mjs to create it.`);
    return 1;
  }
  if (bundled.equals(Buffer.from(json, "utf8"))) {
    console.log(`${shown(out)} is up to date with web/src (${summary(content)}).`);
    return 0;
  }
  let changes;
  try {
    changes = changedKeys(JSON.parse(bundled.toString("utf8")), JSON.parse(json));
  } catch {
    changes = ["~ (the bundled file is not valid JSON)"];
  }
  if (changes.length === 0) {
    changes = ["~ (formatting only: the extractor writes sorted keys, two-space indent, LF and a final newline)"];
  }
  const LIMIT = 60;
  console.error(`${shown(out)} is out of date with web/src: ${changes.length} change(s).`);
  for (const line of changes.slice(0, LIMIT)) console.error(`  ${line}`);
  if (changes.length > LIMIT) console.error(`  … and ${changes.length - LIMIT} more`);
  console.error("Run node android/tools/didactic/extract.mjs, review the asset's diff and commit it with the web change.");
  return 1;
}

function write(out, json, content) {
  mkdirSync(dirname(out), { recursive: true });
  const temporary = join(dirname(out), `.${basename(out)}.${process.pid}.tmp`);
  try {
    writeFileSync(temporary, json, { encoding: "utf8", flag: "wx" });
    renameSync(temporary, out);
  } finally {
    rmSync(temporary, { force: true });
  }
  console.log(`Wrote ${shown(out)} (${summary(content)}).`);
  return 0;
}

// --- command line ---------------------------------------------------------------------------

const USAGE = `Usage: node android/tools/didactic/extract.mjs [--check] [--root <repository>] [--out <file>]
  --check         regenerate in memory; exit 1 if the asset differs from it
  --root <dir>    repository whose web/src is read (default: the one holding this script)
  --out <file>    asset to write or check (default: ${relative(REPO, DEFAULT_OUT)})`;

export async function main(argv) {
  let args;
  try {
    ({ values: args } = parseArgs({
      args: argv,
      options: {
        check: { type: "boolean" },
        root: { type: "string" },
        out: { type: "string" },
        help: { type: "boolean", short: "h" },
      },
      strict: true,
      allowPositionals: false,
    }));
  } catch (error) {
    console.error(`${error.message}\n${USAGE}`);
    return 2;
  }
  if (args.help) {
    console.log(USAGE);
    return 0;
  }

  const out = resolve(args.out ?? DEFAULT_OUT);
  const rootPath = resolve(args.root ?? REPO);
  // First of all, before anything is read: never anything under web/.
  const refusal = webOutputRefusal(out, [REPO, rootPath]);
  if (refusal) throw new ExtractError(refusal);
  if (!existsSync(rootPath)) throw new ExtractError(`${rootPath} does not exist`);
  const root = realpathSync(rootPath);

  ensureTypeScript(argv);
  quietTypeStrippingNotice();
  const files = inputFiles(root);
  const web = await loadWeb(root, files);
  const content = buildContent(root, files, web);
  const departures = schemaErrors(content);
  if (departures.length > 0) {
    throw new ExtractError(`the extracted content does not match schema ${SCHEMA} (schema.mjs):\n  ${departures.slice(0, 20).join("\n  ")}`);
  }
  const json = canonicalJson(content);
  return args.check ? check(out, json, content) : write(out, json, content);
}

const invokedDirectly = (() => {
  try {
    return process.argv[1] !== undefined && realpathSync(process.argv[1]) === realpathSync(SCRIPT);
  } catch {
    return false;
  }
})();

if (invokedDirectly) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    const known = error instanceof ExtractError;
    console.error(`didactic extractor: ${known ? error.message : (error?.stack ?? String(error))}`);
    process.exitCode = 2;
  }
}
