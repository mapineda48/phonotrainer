// didactic.json, schema 1: the one machine-checked definition of the asset's shape.
//
// The extractor checks its own output against it before writing or comparing, the tests
// check the bundled asset against it, and the Kotlin model of `:core:content` mirrors it
// field by field (README.md in this folder has the table). Every field is always present:
// an absent value is written as null, never left out.
//
// Changing it: a new optional field keeps schema 1 only if the Kotlin reader ignores it
// safely; renaming, removing or retyping a field bumps SCHEMA, and the app refuses a
// schema it does not know.

export const SCHEMA = 1;

const string = { type: "string" };
const number = { type: "number" };
const boolean = { type: "boolean" };
const nullable = (of) => ({ nullable: of });
const arrayOf = (of) => ({ array: of });
const mapOf = (of) => ({ map: of });
const object = (fields) => ({ object: fields });

/** `LinkTarget`: "kind" says which, and only that kind's fields follow. */
const linkTarget = {
  tagged: "kind",
  variants: {
    learn: {},
    phenomenon: { label: string },
    ipa: { symbol: nullable(string) },
    practice: { focus: nullable(string) },
    insights: {},
  },
};

const column = object({ key: string, label: string, detail: string });
const gridRow = object({ label: string, detail: string, cells: mapOf(arrayOf(string)) });

export const DIDACTIC_SCHEMA_1 = object({
  schema: { constant: SCHEMA },
  sources: arrayOf(object({ path: string, sha256: string })),
  glossary: mapOf(
    object({
      title: string,
      body: string,
      example: nullable(string),
      learnMore: nullable(linkTarget),
      learnMoreLabel: nullable(string),
    }),
  ),
  symbolPhenomenon: mapOf(string),
  lessons: mapOf(
    object({
      listenFor: string,
      demo: nullable(
        object({ word: string, dictionary: arrayOf(string), said: arrayOf(string), from: string, to: nullable(string) }),
      ),
      spanishTip: nullable(string),
      note: nullable(string),
    }),
  ),
  ipaLayout: object({
    places: arrayOf(column),
    consonantRows: arrayOf(gridRow),
    allophonePlaces: arrayOf(column),
    allophoneRows: arrayOf(gridRow),
    syllabics: arrayOf(string),
    vowelColumns: arrayOf(column),
    vowelRows: arrayOf(gridRow),
    reducedVowels: arrayOf(string),
    glidingGroups: arrayOf(object({ label: string, detail: string, symbols: arrayOf(string) })),
    placed: arrayOf(string),
    unplaced: arrayOf(string),
  }),
  phones: arrayOf(
    object({
      symbol: string,
      name: string,
      example: string,
      cue: string,
      manner: string,
      voiced: boolean,
      gestures: arrayOf(object({ at: number, pose: mapOf(number) })),
      carry: nullable(mapOf(number)),
    }),
  ),
});

const isPlainObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const kindOf = (value) => (value === null ? "null" : Array.isArray(value) ? "array" : typeof value);

function exactFields(value, fields, path, errors) {
  const expected = Object.keys(fields);
  for (const key of Object.keys(value)) if (!expected.includes(key)) errors.push(`${path}.${key}: not in schema 1`);
  for (const key of expected) {
    if (!(key in value)) errors.push(`${path}.${key}: missing`);
    else check(value[key], fields[key], `${path}.${key}`, errors);
  }
}

function check(value, type, path, errors) {
  if ("nullable" in type) {
    if (value !== null) check(value, type.nullable, path, errors);
  } else if ("constant" in type) {
    if (value !== type.constant) errors.push(`${path}: expected ${JSON.stringify(type.constant)}`);
  } else if ("type" in type) {
    const ok = type.type === "number" ? typeof value === "number" && Number.isFinite(value) : typeof value === type.type;
    if (!ok) errors.push(`${path}: expected a ${type.type}, found ${kindOf(value)}`);
  } else if ("array" in type) {
    if (!Array.isArray(value)) errors.push(`${path}: expected an array, found ${kindOf(value)}`);
    else value.forEach((item, i) => check(item, type.array, `${path}[${i}]`, errors));
  } else if ("map" in type) {
    if (!isPlainObject(value)) errors.push(`${path}: expected an object, found ${kindOf(value)}`);
    else for (const [key, item] of Object.entries(value)) check(item, type.map, `${path}[${JSON.stringify(key)}]`, errors);
  } else if ("object" in type) {
    if (!isPlainObject(value)) errors.push(`${path}: expected an object, found ${kindOf(value)}`);
    else exactFields(value, type.object, path, errors);
  } else if ("tagged" in type) {
    if (!isPlainObject(value)) {
      errors.push(`${path}: expected an object, found ${kindOf(value)}`);
      return;
    }
    const variant = type.variants[value[type.tagged]];
    if (!variant) {
      errors.push(`${path}.${type.tagged}: ${JSON.stringify(value[type.tagged])} is not one of ${Object.keys(type.variants).join(", ")}`);
      return;
    }
    exactFields(value, { [type.tagged]: string, ...variant }, path, errors);
  } else {
    throw new Error(`${path}: bad schema node`);
  }
}

/** Every way `value` departs from schema 1 (empty when it conforms). */
export function schemaErrors(value, schema = DIDACTIC_SCHEMA_1) {
  const errors = [];
  check(value, schema, "$", errors);
  return errors;
}
