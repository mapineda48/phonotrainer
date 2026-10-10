// Tests of the didactic extractor. Run with Node's own runner (no npm dependency):
//   node --test android/tools/didactic/test/
// Every run that writes uses a temporary copy of the web inputs and a temporary output;
// the repository's web/ and its bundled asset are only read.

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { after, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import {
  DEFAULT_OUT,
  POSE_KEYS,
  REPO,
  canonicalJson,
  changedKeys,
  inputFiles,
  linkTarget,
  webOutputRefusal,
} from "../extract.mjs";
import { schemaErrors } from "../schema.mjs";

const SCRIPT = fileURLToPath(new URL("../extract.mjs", import.meta.url));
const scratch = mkdtempSync(join(tmpdir(), "phono-didactic-"));
after(() => rmSync(scratch, { recursive: true, force: true }));

let copies = 0;

/** A temporary repository holding a copy of the extractor's web inputs (and nothing else:
 *  not even web/package.json, which the resolve hook does not need). */
function copyInputs(edit) {
  const root = join(scratch, `repo-${++copies}`);
  for (const file of inputFiles(REPO)) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    cpSync(join(REPO, file), join(root, file));
  }
  if (edit) edit(root);
  return root;
}

function replaceIn(root, file, from, to) {
  const path = join(root, file);
  const before = readFileSync(path, "utf8");
  assert.ok(before.includes(from), `${file} no longer contains ${JSON.stringify(from)}; update the test`);
  writeFileSync(path, before.replace(from, to));
}

function run(...args) {
  const result = spawnSync(process.execPath, [SCRIPT, ...args], { encoding: "utf8" });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

const out = (name) => join(scratch, "out", `${name}.json`);
const sha256 = (path) => createHash("sha256").update(readFileSync(path)).digest("hex");

describe("drift check", () => {
  it("passes on the bundled asset", () => {
    const result = run("--check");
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /is up to date with web\/src/);
  });

  it("passes on a faithful copy of the inputs", () => {
    const result = run("--check", "--root", copyInputs());
    assert.equal(result.status, 0, result.stderr);
  });

  it("fails on one edited string and names the changed key", () => {
    const root = copyInputs((r) =>
      replaceIn(r, "web/src/didactic/glossary/terms.ts", "The short, relaxed vowel [ə]", "The short relaxed vowel [ə]"),
    );
    const result = run("--check", "--root", root);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /out of date with web\/src/);
    assert.match(result.stderr, /~ glossary\.schwa\.body/);
    assert.match(result.stderr, /~ sources\[\d+\]\.sha256/);
  });

  it("re-runs itself with type stripping when the Node it runs on has it off", () => {
    const result = spawnSync(process.execPath, ["--no-experimental-strip-types", SCRIPT, "--check"], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /is up to date/);
  });

  it("fails when the bundled file is missing", () => {
    const result = run("--check", "--out", out("missing"));
    assert.equal(result.status, 1);
    assert.match(result.stderr, /does not exist/);
  });

  it("reports formatting-only differences", () => {
    const path = out("reformatted");
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(JSON.parse(readFileSync(DEFAULT_OUT, "utf8"))));
    const result = run("--check", "--out", path);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /formatting only/);
  });
});

describe("generation", () => {
  it("is deterministic and canonical, and equals the bundled asset for the same inputs", () => {
    const root = copyInputs();
    assert.equal(run("--root", root, "--out", out("first")).status, 0);
    assert.equal(run("--root", root, "--out", out("second")).status, 0);
    const first = readFileSync(out("first"), "utf8");
    assert.equal(first, readFileSync(out("second"), "utf8"));
    assert.equal(first, readFileSync(DEFAULT_OUT, "utf8"));
    assert.ok(first.endsWith("}\n") && !first.includes("\r"));
    assert.equal(canonicalJson(JSON.parse(first)), first, "sorted keys, two-space indent");
  });

  it("leaves the repository's web/ untouched", () => {
    const asset = JSON.parse(readFileSync(DEFAULT_OUT, "utf8"));
    for (const { path, sha256: expected } of asset.sources) assert.equal(sha256(join(REPO, path)), expected, path);
    const git = spawnSync("git", ["status", "--porcelain", "--", "web"], { cwd: REPO, encoding: "utf8" });
    if (git.status === 0) assert.equal(git.stdout, "", "web/ has changes");
  });
});

describe("the bundled asset (schema 1)", () => {
  const asset = JSON.parse(readFileSync(DEFAULT_OUT, "utf8"));

  it("conforms to schema 1 (schema.mjs), every field present", () => {
    assert.deepEqual(schemaErrors(asset), []);
  });

  it("has the schema header and its sources", () => {
    assert.equal(asset.schema, 1);
    assert.deepEqual(Object.keys(asset).sort(), [
      "glossary", "ipaLayout", "lessons", "phones", "schema", "sources", "symbolPhenomenon",
    ]);
    assert.deepEqual(asset.sources.map((s) => s.path), inputFiles(REPO));
    for (const source of asset.sources) assert.match(source.sha256, /^[0-9a-f]{64}$/);
  });

  it("finds schwa in the glossary (F-14-AC3) with every entry complete", () => {
    assert.equal(asset.glossary.schwa.title, "Schwa");
    assert.match(asset.glossary.schwa.body, /\[ə\]/);
    for (const [key, entry] of Object.entries(asset.glossary)) {
      assert.deepEqual(Object.keys(entry).sort(), ["body", "example", "learnMore", "learnMoreLabel", "title"], key);
      assert.ok(entry.title && entry.body, key);
    }
    assert.deepEqual(asset.glossary["ipa:ɾ"].learnMore, { kind: "phenomenon", label: "flapping" });
    assert.deepEqual(asset.glossary["metric:schwa_share"].learnMore, { kind: "ipa", symbol: "ə" });
    assert.deepEqual(asset.glossary.phenomenon.learnMore, { kind: "learn" });
    assert.equal(asset.glossary.family.learnMoreLabel, null);
    assert.equal(asset.glossary["ipa:iː"].title, asset.glossary["ipa:i"].title, "length-marked aliases");
  });

  it("has a lesson for each of the 16 changes, with the Spanish-speaker tip (F-15-AC3)", () => {
    assert.equal(Object.keys(asset.lessons).length, 16);
    assert.match(asset.lessons.vowel_reduction.spanishTip, /Spanish/);
    assert.equal(asset.lessons.word_elision.demo, null);
    assert.equal(asset.lessons.t_deletion.demo.to, null);
    for (const label of Object.values(asset.symbolPhenomenon)) assert.ok(asset.lessons[label], label);
  });

  it("has the IPA layout and the articulation table", () => {
    const layout = asset.ipaLayout;
    assert.deepEqual(layout.allophonePlaces.map((p) => p.key), ["bilabial", "alveolar", "velar", "glottal"]);
    assert.ok(layout.vowelColumns.every((c) => c.detail === ""));
    assert.ok(layout.glidingGroups.every((g) => g.label && g.symbols.length > 0));
    assert.deepEqual(layout.unplaced, []);
    const symbols = new Set(asset.phones.map((p) => p.symbol));
    for (const symbol of layout.placed) assert.ok(symbols.has(symbol), symbol);
    for (const phone of asset.phones) {
      for (const gesture of phone.gestures) assert.deepEqual(Object.keys(gesture.pose).sort(), [...POSE_KEYS].sort());
      if (phone.carry !== null) assert.ok(Object.keys(phone.carry).every((k) => POSE_KEYS.includes(k)));
    }
    assert.deepEqual(asset.phones.find((p) => p.symbol === "h").carry, { voice: 0 });
  });

  it("holds no machine path", () => {
    const text = readFileSync(DEFAULT_OUT, "utf8");
    assert.doesNotMatch(text, /\/home\/|\/Users\/|[A-Z]:\\\\/);
  });
});

describe("strictness", () => {
  it("fails on an unknown href", () => {
    const root = copyInputs((r) =>
      replaceIn(r, "web/src/didactic/glossary/terms.ts", 'learnMore: { href: paths.learn(), label: "Learn all of them" }', 'learnMore: { href: "/library", label: "Learn all of them" }'),
    );
    const result = run("--root", root, "--out", out("href"));
    assert.equal(result.status, 2);
    assert.match(result.stderr, /unknown href "\/library"/);
    assert.equal(existsSync(out("href")), false);
  });

  it("fails on a field it does not know", () => {
    const root = copyInputs((r) =>
      replaceIn(r, "web/src/features/learn/content.ts", "  word_elision: {\n", '  word_elision: {\n    video: "x",\n'),
    );
    const result = run("--root", root, "--out", out("field"));
    assert.equal(result.status, 2);
    assert.match(result.stderr, /LESSONS\["word_elision"\]: unexpected field "video"/);
  });

  it("fails on a demo symbol outside the articulation table", () => {
    const root = copyInputs((r) =>
      replaceIn(r, "web/src/features/learn/content.ts", 'said: ["d", "ə", "z"]', 'said: ["d", "ʘ", "z"]'),
    );
    const result = run("--root", root, "--out", out("symbol"));
    assert.equal(result.status, 2);
    assert.match(result.stderr, /"ʘ" is not in the articulation table/);
  });

  it("fails when a web input imports a file it does not declare", () => {
    const root = copyInputs((r) => {
      mkdirSync(join(r, "web/src/lib"), { recursive: true });
      writeFileSync(join(r, "web/src/lib/extra.ts"), "export const EXTRA = 1;\n");
      replaceIn(r, "web/src/features/learn/content.ts", "export interface MouthDemo", 'import { EXTRA } from "../../lib/extra";\nexport const X = EXTRA;\n\nexport interface MouthDemo');
    });
    const result = run("--root", root, "--out", out("import"));
    assert.equal(result.status, 2);
    assert.match(result.stderr, /imports web\/src\/lib\/extra\.ts, which is not one of the extractor's declared inputs/);
  });

  it("fails when a web input imports a package", () => {
    const root = copyInputs((r) =>
      replaceIn(r, "web/src/paths.ts", "const enc = encodeURIComponent;", 'import "react";\nconst enc = encodeURIComponent;'),
    );
    const result = run("--root", root, "--out", out("package"));
    assert.equal(result.status, 2);
    assert.match(result.stderr, /imports "react", which is not one of the extractor's inputs/);
  });

  it("maps exactly the href table of architecture 15.1", () => {
    const known = { changes: new Set(["flapping", "vowel_reduction"]), symbols: new Set(["ə", "ɾ"]) };
    const map = (href) => linkTarget(href, "test", known);
    assert.deepEqual(map("/learn"), { kind: "learn" });
    assert.deepEqual(map("/learn/flapping"), { kind: "phenomenon", label: "flapping" });
    assert.deepEqual(map("/learn/ipa"), { kind: "ipa", symbol: null });
    assert.deepEqual(map("/learn/ipa?symbol=%C9%99"), { kind: "ipa", symbol: "ə" });
    assert.deepEqual(map("/practice"), { kind: "practice", focus: null });
    assert.deepEqual(map("/practice?focus=vowel_reduction"), { kind: "practice", focus: "vowel_reduction" });
    assert.deepEqual(map("/insights"), { kind: "insights" });
    for (const bad of [
      "/", "/library", "/learn/", "/learn/unknown_change", "/learn/ipa?symbol=x", "/learn/ipa?symbol=",
      "/learn/ipa?glyph=ə", "/practice?focus=nope", "/insights?phenomenon=flapping", "/learn#top",
      "https://example.com/learn", "//example.com/learn", "learn", "/analysis/job/w/1/2", "/settings",
    ]) {
      assert.throws(() => map(bad), /unknown href/, bad);
    }
  });
});

describe("never under web/", () => {
  it("refuses an output inside web/ and writes nothing", () => {
    const root = copyInputs();
    const target = join(root, "web", "src", "didactic.json");
    const result = run("--root", root, "--out", target);
    assert.equal(result.status, 2);
    assert.match(result.stderr, /never writes under web\//);
    assert.equal(existsSync(target), false);
  });

  it("refuses an output that reaches web/ through a symbolic link", () => {
    const root = copyInputs();
    symlinkSync(join(root, "web", "src"), join(root, "link"));
    const target = join(root, "link", "nested", "didactic.json");
    const result = run("--root", root, "--out", target);
    assert.equal(result.status, 2);
    assert.match(result.stderr, /never writes under web\//);
    assert.equal(existsSync(join(root, "web", "src", "nested")), false);
  });

  it("refuses this repository's web/ whatever the root (checked without running)", () => {
    assert.match(webOutputRefusal(join(REPO, "web", "src", "x.json"), [REPO]), /never writes under web\//);
    assert.match(webOutputRefusal(join(REPO, "web"), [REPO]), /never writes under web\//);
    assert.equal(webOutputRefusal(join(REPO, "webapp", "x.json"), [REPO]), null);
    assert.equal(webOutputRefusal(DEFAULT_OUT, [REPO]), null);
  });
});

describe("schema 1", () => {
  it("reports what departs from it", () => {
    const asset = JSON.parse(readFileSync(DEFAULT_OUT, "utf8"));
    asset.glossary.schwa.extra = 1;
    delete asset.glossary.schwa.example;
    asset.glossary.phone.learnMore = { kind: "library" };
    asset.glossary.ipa.learnMore = { kind: "ipa" };
    asset.lessons.flapping.demo.to = 3;
    asset.phones[0].gestures[0].pose.jaw = "open";
    asset.schema = 2;
    assert.deepEqual(schemaErrors(asset).sort(), [
      "$.glossary[\"ipa\"].learnMore.symbol: missing",
      "$.glossary[\"phone\"].learnMore.kind: \"library\" is not one of learn, phenomenon, ipa, practice, insights",
      "$.glossary[\"schwa\"].example: missing",
      "$.glossary[\"schwa\"].extra: not in schema 1",
      "$.lessons[\"flapping\"].demo.to: expected a string, found number",
      "$.phones[0].gestures[0].pose[\"jaw\"]: expected a number, found string",
      "$.schema: expected 1",
    ]);
  });
});

describe("helpers", () => {
  it("writes keys in string order, integer-like keys included", () => {
    assert.equal(canonicalJson({ b: 1, 10: [], a: { d: null, c: "é" } }), '{\n  "10": [],\n  "a": {\n    "c": "é",\n    "d": null\n  },\n  "b": 1\n}\n');
    assert.throws(() => canonicalJson({ a: Number.NaN }), /cannot be written/);
    assert.throws(() => canonicalJson({ a: undefined }), /cannot be written/);
  });

  it("names changed, added and removed keys", () => {
    const changes = changedKeys(
      { glossary: { schwa: { body: "a" }, old: {} }, phones: [1, 2] },
      { glossary: { schwa: { body: "b" }, "ipa:ə": {} }, phones: [1] },
    );
    assert.deepEqual(changes, ['+ glossary["ipa:ə"]', "- glossary.old", "~ glossary.schwa.body", "- phones[1]"]);
  });
});
