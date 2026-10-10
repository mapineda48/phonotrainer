// Module resolve hook for extract.mjs, registered with `module.register`.
//
// The web sources import each other without extensions ("./pose"), the way Vite and
// TypeScript resolve them. Node does not, so this hook maps an extension-less relative
// import made by a web file to the `.ts` file next to it.
//
// It is also the extractor's fence: a web file may only import the files the extractor
// declares as its inputs (architecture 15.1). A new import (another web file, an npm
// package, a Node built-in) fails the run with a message that names it, so a change in
// the web's import graph is noticed instead of being followed silently.
//
// It never writes anything; it only answers "where is this module".

/** File URL prefix of `<root>/web/src/`, set by `initialize`. */
let webSrc = null;
/** File URLs the web files may import. */
let allowed = new Set();
/** For messages: file URL prefix of `<root>/`. */
let rootUrl = "";

export async function initialize(data) {
  webSrc = data.webSrcUrl;
  rootUrl = data.rootUrl;
  allowed = new Set(data.allowed);
}

const shown = (url) => (url.startsWith(rootUrl) ? decodeURI(url.slice(rootUrl.length)) : url);

const isRelative = (specifier) => specifier.startsWith("./") || specifier.startsWith("../");

const hasExtension = (url) => /\.[a-z]+$/i.test(new URL(url).pathname.split("/").pop());

const FIX =
  "update the input table in android/tools/didactic/extract.mjs (and architecture 15.1) " +
  "after checking what it brings in, then extract again.";

export async function resolve(specifier, context, nextResolve) {
  const parent = context.parentURL ?? "";
  const fromWeb = webSrc !== null && parent.startsWith(webSrc);

  if (!fromWeb) {
    const resolved = await nextResolve(specifier, context);
    if (webSrc === null || !resolved.url.startsWith(webSrc)) return resolved;
    return webModule(resolved.url, `${shown(resolved.url)} is loaded`);
  }

  if (!isRelative(specifier)) {
    throw new Error(
      `${shown(parent)} imports "${specifier}", which is not one of the extractor's inputs ` +
        `(the web files it reads import nothing but each other); ${FIX}`,
    );
  }
  let url = new URL(specifier, parent).href;
  if (!hasExtension(url)) url = `${url}.ts`;
  return webModule(url, `${shown(parent)} imports ${shown(url)}`);
}

function webModule(url, what) {
  if (!allowed.has(url)) {
    throw new Error(`${what}, which is not one of the extractor's declared inputs; ${FIX}`);
  }
  // The web package says "type": "module"; stating the format here makes a copy of the
  // sources without that package.json (the drift test's temporary tree) load the same way.
  return { url, format: "module-typescript", shortCircuit: true };
}
