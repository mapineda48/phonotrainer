import { describe, expect, it } from "vitest";

import { PHONE_TABLE, bareSymbol, lookupPhone } from "./phones";

/** The closed inventory the backend can emit (`ipa_maps.ENGLISH_INVENTORY`).
 *  A Python test (`tests/test_articulation_coverage.py`) checks this copy has
 *  not drifted: a phone the analyzer emits and the engine cannot draw would be
 *  a hole in the middle of a word. */
const INVENTORY = `aɪ aɪə aɪɚ aʊ b b̚ d dʒ d̚ eə eɪ f h i iə iː j ju k k̚ l l̩ m m̩ n n̩ oʊ oːɹ p p̚ s t
tʃ t̚ u uː v w z æ ð ŋ ŋ̍ ɐ ɑ ɑː ɑːɹ ɒ ɔ ɔɪ ɔː ɔːɹ ə əl ɚ ɛ ɛɹ ɜ ɜː ɝ ɡ ɡ̚ ɪ ɪɹ ɹ ɾ ɾ̃
ʃ ʊ ʊɹ ʌ ʒ ʔ θ ᵻ`
  .split(/\s+/)
  .filter(Boolean);

describe("phone inventory", () => {
  it("can draw every phone the analyzer emits", () => {
    const missing = INVENTORY.filter((symbol) => lookupPhone(symbol) === null);
    expect(missing).toEqual([]);
  });

  it("ignores stress and length: /ˈuː/ and /u/ are the same mouth", () => {
    expect(bareSymbol("ˈuː")).toBe("u");
    expect(lookupPhone("ˈuː")).toBe(lookupPhone("u"));
    expect(lookupPhone("ˌɑː")).toBe(lookupPhone("ɑ"));
  });

  it("has no entry for a symbol outside the inventory", () => {
    // Better a held shape than an invented one: the caller keeps the previous
    // pose instead of drawing a mouth that was never measured.
    expect(lookupPhone("ʕ")).toBeNull();
  });

  it("gives every phone a name, an example and a cue", () => {
    for (const phone of PHONE_TABLE) {
      expect(phone.name.length, phone.symbol).toBeGreaterThan(3);
      expect(phone.example.length, phone.symbol).toBeGreaterThan(1);
      expect(phone.cue.length, phone.symbol).toBeGreaterThan(20);
    }
  });
});

describe("places of articulation", () => {
  const poseOf = (symbol: string) => {
    const phone = lookupPhone(symbol);
    if (!phone) throw new Error(`no articulation for ${symbol}`);
    return phone.gestures[phone.gestures.length - 1].pose;
  };

  it("the alveolar stops raise the tip to the ridge", () => {
    for (const symbol of ["t", "d", "n"]) {
      expect(poseOf(symbol).tip, symbol).toBe(1);
      expect(poseOf(symbol).tipFront, symbol).toBeCloseTo(0.5, 2);
    }
  });

  it("the dental fricatives push the tip further forward than the alveolars", () => {
    expect(poseOf("θ").tipFront).toBeGreaterThan(poseOf("t").tipFront);
    expect(poseOf("ð").tipFront).toBe(poseOf("θ").tipFront);
  });

  it("the velars close with the body of the tongue, not the tip", () => {
    for (const symbol of ["k", "ɡ", "ŋ"]) {
      expect(poseOf(symbol).height, symbol).toBe(1);
      expect(poseOf(symbol).tip, symbol).toBeLessThan(0.2);
    }
  });

  it("the bilabials close the lips and leave the tongue out of it", () => {
    for (const symbol of ["p", "b", "m"]) expect(poseOf(symbol).lipOpen, symbol).toBe(0);
  });

  it("separates /v/ from /b/ where a learner hears them the same", () => {
    // The whole difference is visible: /v/ tucks the lip under the teeth and
    // never closes, /b/ closes both lips and does not tuck.
    expect(poseOf("v").lipTuck).toBe(1);
    expect(poseOf("v").lipOpen).toBeGreaterThan(0);
    expect(poseOf("b").lipTuck).toBe(0);
    expect(poseOf("b").lipOpen).toBe(0);
  });

  it("opens the velum for the nasals and only for them", () => {
    const nasal = PHONE_TABLE.filter((phone) => phone.manner === "nasal");
    expect(nasal.map((phone) => phone.symbol)).toEqual(["m", "n", "ŋ", "n̩", "m̩", "ŋ̍"]);
    for (const phone of nasal) expect(poseOf(phone.symbol).velum, phone.symbol).toBe(1);
    // the nasal flap of "winter" is the one tap that keeps the velum down
    const lowered = PHONE_TABLE.filter(
      (phone) => phone.gestures.length > 0 && poseOf(phone.symbol).velum > 0.5,
    );
    expect(lowered.map((phone) => phone.symbol).sort()).toEqual(
      [...nasal.map((phone) => phone.symbol), "ɾ̃"].sort(),
    );
    expect(lookupPhone("ɾ̃")!.manner).toBe("tap");
  });

  it("voices what should be voiced", () => {
    for (const phone of PHONE_TABLE) {
      if (phone.carry || phone.gestures.length === 0) continue;
      expect(poseOf(phone.symbol).voice, phone.symbol).toBe(phone.voiced ? 1 : 0);
    }
  });
});

describe("the vowel space", () => {
  const poseOf = (symbol: string) => lookupPhone(symbol)!.gestures[0].pose;

  it("places the vowels the way the chart does", () => {
    // front and high vs back and low: the two axes of the vowel quadrilateral
    expect(poseOf("i").body).toBeGreaterThan(poseOf("u").body);
    expect(poseOf("i").height).toBeGreaterThan(poseOf("ɪ").height);
    expect(poseOf("ɪ").height).toBeGreaterThan(poseOf("ɛ").height);
    expect(poseOf("ɛ").height).toBeGreaterThan(poseOf("æ").height);
    expect(poseOf("ɑ").body).toBeLessThan(poseOf("æ").body);
    expect(poseOf("u").height).toBeGreaterThan(poseOf("ʊ").height);
  });

  it("rounds the back vowels and not the front ones", () => {
    expect(poseOf("u").lipRound).toBeGreaterThan(0.5);
    expect(poseOf("i").lipRound).toBe(0);
    expect(poseOf("æ").lipRound).toBe(0);
  });

  it("opens the jaw as the vowel opens", () => {
    expect(poseOf("æ").jaw).toBeGreaterThan(poseOf("ɛ").jaw);
    expect(poseOf("ɛ").jaw).toBeGreaterThan(poseOf("i").jaw);
  });

  it("puts the reduced /ᵻ/ between /ɪ/ and schwa", () => {
    expect(poseOf("ᵻ").body).toBeLessThan(poseOf("ɪ").body);
    expect(poseOf("ᵻ").body).toBeGreaterThan(poseOf("ə").body);
    expect(poseOf("ᵻ").height).toBeGreaterThan(poseOf("ə").height);
    expect(poseOf("ᵻ").lipRound).toBe(0);
  });

  it("retracts the root for the back vowels, which is where /ɑ/ lives", () => {
    expect(poseOf("ɑ").root).toBeGreaterThan(poseOf("i").root + 0.4);
  });

  it("gives the r-coloured vowels a bunched body and a retracted root", () => {
    for (const symbol of ["ɚ", "ɝ"]) {
      expect(poseOf(symbol).root, symbol).toBeGreaterThan(0.5);
      expect(poseOf(symbol).tip, symbol).toBeGreaterThan(0.3);
      expect(poseOf(symbol).tip, symbol).toBeLessThan(1); // it never touches
    }
  });
});

describe("phones that are a movement", () => {
  it("gives every diphthong two different targets", () => {
    for (const phone of PHONE_TABLE.filter((entry) => entry.manner === "diphthong")) {
      expect(phone.gestures.length, phone.symbol).toBeGreaterThanOrEqual(2);
      const first = phone.gestures[0].pose;
      const last = phone.gestures[phone.gestures.length - 1].pose;
      const travel =
        Math.abs(first.height - last.height) +
        Math.abs(first.body - last.body) +
        Math.abs(first.lipRound - last.lipRound);
      expect(travel, phone.symbol).toBeGreaterThan(0.2);
    }
  });

  it("releases the affricates into their fricative", () => {
    const phone = lookupPhone("tʃ")!;
    expect(phone.gestures[0].pose.tip).toBe(1);                    // closure
    expect(phone.gestures[3].pose.tip).toBeLessThan(1);            // release
    expect(phone.gestures[3].pose.tip).toBe(lookupPhone("ʃ")!.gestures[0].pose.tip);
  });

  it("holds the stops instead of bouncing off the contact", () => {
    const phone = lookupPhone("t")!;
    expect(phone.gestures).toHaveLength(2);
    expect(phone.gestures[0].pose).toBe(phone.gestures[1].pose);
    expect(phone.gestures[1].at - phone.gestures[0].at).toBeGreaterThan(0.3);
  });

  it("holds an unreleased stop shut to the end, with its twin's seal", () => {
    for (const [unreleased, released] of [
      ["p̚", "p"], ["b̚", "b"], ["t̚", "t"], ["d̚", "d"], ["k̚", "k"], ["ɡ̚", "ɡ"],
    ]) {
      const phone = lookupPhone(unreleased)!;
      const twin = lookupPhone(released)!;
      // not "stop": the view draws a burst when a stop opens, and here none is heard
      expect(phone.manner, unreleased).toBe("unreleased");
      expect(phone.voiced, unreleased).toBe(twin.voiced);
      expect(phone.gestures[0].pose, unreleased).toEqual(twin.gestures[0].pose);
      expect(phone.gestures[phone.gestures.length - 1].at, unreleased).toBe(1);
    }
  });

  it("lets /h/ and /ʔ/ borrow the mouth around them", () => {
    for (const symbol of ["h", "ʔ"]) {
      const phone = lookupPhone(symbol)!;
      expect(phone.gestures, symbol).toEqual([]);
      expect(phone.carry, symbol).toEqual({ voice: 0 });
    }
  });
});
