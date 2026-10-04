/** The head, in midsagittal section: the fixed anatomy the tongue moves inside.
 *
 *  Everything lives in one abstract 100 × 100 space, seen from the left with
 *  the face looking to the **right** (+x = towards the lips, +y = upwards).
 *  One unit ≈ 2 mm on an adult vocal tract, which is what fixes the
 *  proportions: the distance from the upper incisors to the glottis, the height
 *  of the palatal vault, how far the jaw drops. They are not free decorations —
 *  the tongue is placed by casting rays against these outlines, so moving a
 *  point here moves where /t/ or /k/ make contact.
 *
 *  The drawing follows the classic sagittal section of the head (Gray's
 *  *Anatomy*, 1918, fig. 994 — public domain): the hard palate as a shelf of
 *  bone between the mouth and the nose, the nasal cavity with its conchae,
 *  the cervical spine behind the pharynx, the incisors rooted in the maxilla
 *  and in the mandible, the hyoid, the epiglottis and the larynx. The pharynx
 *  is a little shorter than life so that the larynx still fits in the panel.
 *
 *  Only what does NOT move lives here as finished outlines. Tongue, jaw, lips,
 *  velum and epiglottis are computed per frame in `tract.ts` out of the
 *  landmarks declared here.
 */

export type Vec2 = readonly [number, number];

/** Centre the tongue body is cast from (inside the jaw, below the mouth floor).
 *  Every direction of the tongue surface is an angle measured from here. */
export const TONGUE_CENTER: Vec2 = [60, 26];

/**
 * Roof of the tract, from the bottom of the pharynx to the upper incisors.
 *
 * These are landmarks; `TRACT_ROOF` is the smooth curve through them that the
 * tongue is actually clipped against and that gets drawn, so what you see
 * touching is what the geometry calls contact. A phone with maximum raising
 * touches it exactly, and one with less leaves a proportional gap. Between
 * the velar seal and the rear edge of the hard palate this is the underside
 * of the RAISED velum. The list ends AT the incisors on purpose — beyond them
 * there is no wall, only the gap between the teeth, which is what lets the
 * tip come out for /θ/ and /ð/.
 */
export const TRACT_WALL: readonly Vec2[] = [
  [50, 6],     // larynx, just above the glottis
  [47.5, 14],
  [45.8, 24],  // back wall of the pharynx
  [45.5, 34],
  [46, 42],    // where the raised velum seals
  [49, 45.5],
  [53, 47.2],  // rear edge of the hard palate
  [57.5, 48.4],
  [62, 48.6],  // palatal vault
  [67, 47.6],
  [70.5, 46.4],
  [73, 45.2],  // alveolar ridge
  [76, 44],
  [79, 43.5],  // root of the upper incisors
  [80.5, 39],  // tip of the upper incisors
];

/**
 * Lowest position the tongue surface can take: the floor of the mouth and, at
 * the back, a fully advanced tongue root (a wide pharynx).
 *
 * Raising is read between this curve and the roof, so 0 is a tongue lying
 * flat on the floor and 1 is contact. An open /ɑ/ lives near the bottom of that
 * range and a /k/ at the very top of it.
 */
export const TONGUE_FLOOR: readonly Vec2[] = [
  [53, 19],    // fully advanced root: the pharynx at its widest
  [54.5, 22],
  [57, 28],
  [61, 32],
  [66, 33.4],
  [70.5, 34],
  [74, 34.2],  // resting tip, behind the lower incisors
];

/** Where the velum hinges off the hard palate. */
export const VELUM_ROOT: Vec2 = [53.4, 47.1];
/** Velum raised: sealed against the pharyngeal wall, nasal port shut. */
export const VELUM_CLOSED_TIP: Vec2 = [46.6, 42.6];
/** Velum lowered: hanging into the pharynx, air free to reach the nose. */
export const VELUM_OPEN_TIP: Vec2 = [50.6, 37.2];

/**
 * The soft palate as two key shapes, raised and lowered, each a closed outline
 * listed oral side first (root → uvula) and then nasal side (uvula → root).
 * Any position in between is a pointwise blend of the two, which is how
 * articulatory models (VocalTractLab among them) move the velum: the muscle
 * does not hinge like a door, it bunches up when it lifts.
 *
 * The oral side of the raised shape lies on the roof (`TRACT_WALL` between
 * the velar seal and the hard palate), so /k/ seals against what is drawn.
 */
export const VELUM_RAISED: readonly Vec2[] = [
  [53.4, 47.1],  // root, on the rear edge of the hard palate
  [51.2, 46.3],
  [49.0, 45.4],
  [47.4, 43.8],
  [46.5, 42.5],
  [46.3, 41.6],  // uvula, tucked up against the pharyngeal wall
  [45.9, 41.4],
  [45.7, 42.6],
  [45.9, 44.8],
  [46.8, 47.0],
  [48.6, 48.8],
  [51.2, 49.8],
  [54.0, 49.7],  // nasal side of the root
];
export const VELUM_LOWERED: readonly Vec2[] = [
  [53.4, 47.1],
  [52.5, 45.2],
  [51.7, 42.8],
  [51.2, 40.5],
  [51.0, 38.5],
  [50.9, 37.0],  // uvula, hanging free
  [50.1, 36.6],
  [49.2, 37.8],
  [49.0, 40.4],
  [49.2, 43.2],
  [50.0, 46.0],
  [51.6, 48.6],
  [54.0, 49.7],
];

/** Index, in both key shapes, of the lowest point of the uvula: where the
 *  oral side ends and the nasal side begins. */
export const VELUM_UVULA = 6;

/** The jaw turns around the condyle, just in front of the ear. */
export const JAW_PIVOT: Vec2 = [42, 50];
/** Widest opening we draw, in radians. Speech uses ~10–12°, not a yawn. */
export const JAW_MAX_ANGLE = 0.21;

/** Floor of the mouth: the underside of the tongue, carried by the jaw.
 *  It runs below `TONGUE_FLOOR` — the gap between the two is the thickness a
 *  flattened tongue still has. */
export const MOUTH_FLOOR: readonly Vec2[] = [
  [74, 31.5],
  [68, 27.5],
  [62, 24],
  [57, 20],
  [53.5, 16.5],
  [52, 15],
];

/** Where the frenulum ties the underside of the tongue to the floor, in the
 *  jaw's frame. In front of it lies the sublingual floor: the pocket that
 *  opens under a raised tip for /t/ or /l/. */
export const FRENULUM: Vec2 = [70.6, 31.0];

/** Where the lips meet when closed, at rest. Aperture opens around this line. */
export const LIP_LINE_Y = 39.2;
/** How far the lips travel forward when fully rounded. */
export const LIP_PROTRUSION = 3.4;
/** Vertical gap between the lips at maximum aperture. */
export const LIP_MAX_GAP = 12;

/** Larynx: the voicing indicator sits here, at the vocal folds. */
export const GLOTTIS: Vec2 = [51.4, 11.2];

// --- the fixed head ----------------------------------------------------------

/**
 * The fixed head as one silhouette: the back wall of the pharynx, the
 * nasopharynx, the nasal cavity (a pocket that opens only towards the
 * pharynx: the nostrils are to either side of the midline, so in profile the
 * columella closes it), the hard palate between the nose and the mouth, the
 * gum of the upper incisors, the nose, forehead, skull and the back of the
 * neck. The airway is simply where no tissue was drawn: nothing has to
 * compute the shape of the air. The upper lip, which moves, is drawn over the
 * front of it.
 */
export const HEAD: readonly Vec2[] = [
  [48.6, 0],       // posterior wall of the airway, at the bottom of the panel
  [49.2, 3],
  ...TRACT_WALL.slice(0, 5), // larynx → back wall of the pharynx → velar seal
  [46.0, 44.8],    // the wall the raised velum presses against
  [46.7, 48.2],
  [48.4, 51.4],    // nasopharynx (adenoid pad)
  [51.2, 54.8],
  [54.4, 58.8],    // sphenoid face
  [58.6, 62.4],
  [64.0, 64.2],    // roof of the nasal cavity, under the cribriform plate
  [71.0, 64.6],
  [77.0, 63.4],
  [81.6, 61.0],    // underside of the nasal bones
  [85.6, 57.4],
  [88.4, 54.2],
  [90.4, 52.4],    // front of the nasal vestibule
  [89.0, 51.2],
  [86.0, 51.3],    // floor of the vestibule
  [80.0, 51.9],
  [72.0, 52.1],    // floor of the nasal cavity
  [64.0, 52.0],
  [58.6, 51.5],
  [55.6, 50.6],
  [54.0, 49.6],    // rear edge of the hard palate, where the velum takes over
  ...TRACT_WALL.slice(6, 14), // hard palate → alveolar ridge → incisors (oral side)
  [79.6, 43.9],
  [81.4, 45.0],    // gum, on the lip side of the incisors
  [82.6, 46.4],
  [83.6, 48.0],    // behind the upper lip
  [87.0, 48.8],
  [90.4, 48.6],    // subnasale
  [92.6, 49.3],    // columella
  [94.6, 50.2],
  [95.6, 51.4],
  [95.6, 52.8],    // tip of the nose
  [94.6, 54.8],
  [92.6, 57.8],
  [90.6, 61.0],    // nasal bridge
  [88.9, 64.2],
  [87.7, 66.9],    // nasion
  [88.3, 70.0],
  [88.5, 73.4],    // glabella
  [87.2, 78.6],
  [84.4, 83.6],    // forehead
  [78.2, 88.6],
  [69.0, 92.0],
  [58.0, 92.6],
  [47.0, 90.4],
  [37.5, 84.4],
  [30.4, 75.0],
  [26.4, 63.5],
  [25.6, 52.5],
  [27.2, 42.5],    // back of the head
  [29.6, 32.0],
  [31.6, 20.5],
  [32.8, 10.0],
  [33.6, 0],       // back of the neck
];

/** The maxilla in section: the bony shelf of the hard palate, thickening at
 *  the front into the alveolar process that holds the upper incisor. */
export const MAXILLA: readonly Vec2[] = [
  [54.2, 48.7],    // posterior nasal spine
  [57.6, 49.3],
  [62.0, 49.5],
  [67.0, 48.7],
  [70.6, 47.6],
  [73.4, 46.6],
  [75.8, 46.0],
  [77.6, 45.4],    // lingual crest of the incisor socket
  [79.0, 46.2],
  [80.8, 46.4],
  [82.0, 45.6],    // labial crest
  [82.4, 47.2],
  [82.9, 49.2],
  [85.2, 50.6],    // anterior nasal spine
  [82.0, 51.2],
  [76.0, 51.3],
  [70.0, 51.5],
  [64.0, 51.5],
  [58.4, 51.0],
  [55.2, 50.0],
];

/** Upper central incisor, crown and root. Its lingual face is the stretch of
 *  `TRACT_WALL` from the cingulum to the edge, so the tip of the tongue
 *  touches the tooth that is drawn. */
export const UPPER_INCISOR: readonly Vec2[] = [
  [80.55, 38.9],   // incisal edge
  [81.3, 40.2],
  [81.85, 42.0],
  [82.0, 43.6],
  [81.95, 44.6],   // labial cemento-enamel junction
  [81.6, 46.6],
  [80.9, 48.6],
  [79.9, 50.2],    // apex of the root
  [79.0, 49.6],
  [78.5, 47.8],
  [78.2, 45.8],
  [78.2, 44.0],    // lingual cemento-enamel junction
  [79.0, 43.3],    // cingulum
  [79.75, 41.2],
  [80.25, 39.6],
];

/** Lower central incisor, in the jaw's own frame (before rotation). */
export const LOWER_TEETH: readonly Vec2[] = [
  [79.4, 38.5],    // incisal edge
  [80.0, 37.6],
  [80.45, 35.8],
  [80.7, 34.0],
  [80.8, 32.8],    // labial cemento-enamel junction
  [80.6, 30.6],
  [80.0, 28.4],
  [79.2, 26.8],    // apex
  [78.5, 28.2],
  [78.1, 30.4],
  [77.9, 32.9],    // lingual cemento-enamel junction
  [78.3, 34.6],    // cingulum
  [78.5, 36.6],
  [78.85, 38.0],
]

/** Where the enamel stops, as indices into each incisor outline: the crown
 *  is drawn as enamel, the rest as root. */
export const UPPER_CROWN = { from: 11, to: 4 } as const;
export const LOWER_CROWN = { from: 10, to: 4 } as const;

/** The mandible, cut through the symphysis, in the jaw's frame: a teardrop of
 *  bone from the incisor sockets down to the chin. */
export const MANDIBLE: readonly Vec2[] = [
  [77.6, 32.8],    // lingual crest
  [76.9, 30.0],
  [76.4, 26.8],    // genial tubercles: where the genioglossus starts
  [76.6, 23.4],
  [77.9, 20.4],
  [80.2, 19.0],    // lower border
  [82.6, 19.4],
  [84.2, 21.4],
  [84.9, 24.2],    // pogonion
  [84.6, 27.0],
  [83.4, 29.6],
  [82.3, 32.4],    // labial crest
  [80.8, 33.6],
  [78.8, 33.4],
]

/** Where the fibres of the genioglossus fan out from, in the jaw's frame:
 *  the tongue's muscle is drawn radiating from here, as in the dissections. */
export const GENIOGLOSSUS_ORIGIN: Vec2 = [76.0, 25.8];

/**
 * Tissue of the lower jaw, in its own frame: the gum, the floor of the mouth,
 * the front wall of the larynx, the neck and the chin. It is carried by the
 * jaw with a weight that fades towards the neck, so the outline of the head
 * stays closed when the mouth opens.
 */
export const LOWER_FACE: readonly Vec2[] = [
  [83.4, 33.4],    // behind the lower lip
  [82.0, 34.6],
  [81.0, 34.7],    // gum, lip side of the incisor
  [77.6, 34.2],    // gum, tongue side
  [75.4, 33.0],
  [72.8, 31.9],    // sublingual floor
  FRENULUM,
  [68.0, 27.5],    // from here back: the same curve the tongue sits on
  [62.0, 24.0],
  [57.0, 20.0],
  [53.5, 16.5],
  [52.4, 14.0],    // vallecula, in front of the epiglottis
  [52.6, 11.0],
  [53.2, 6.0],     // front wall of the larynx and the trachea
  [53.4, 0],
  [57.4, 0],
  [58.2, 3.6],     // front of the neck
  [60.4, 6.6],     // where the neck meets the underside of the chin
  [66.0, 10.0],
  [72.4, 13.2],
  [78.4, 15.8],
  [83.4, 17.2],    // menton
  [86.6, 19.4],
  [88.0, 22.2],
  [88.5, 25.0],    // point of the chin
  [87.7, 27.6],
  [86.0, 29.6],    // labiomental fold: the lower lip starts here
  [84.6, 31.0],
]

/** Hyoid bone, in the jaw's frame (it rides partly on the jaw). */
export const HYOID: readonly Vec2[] = [
  [54.6, 15.0],
  [56.0, 16.2],
  [58.0, 16.0],
  [58.8, 14.8],
  [57.6, 13.8],
  [55.4, 13.8],
];

/** Thyroid cartilage: the front of the larynx (the Adam's apple). */
export const THYROID: readonly Vec2[] = [
  [53.6, 12.6],
  [55.2, 12.4],
  [56.2, 8.0],
  [56.2, 1.0],
  [54.6, 1.0],
  [54.2, 7.0],
];

/** The epiglottis at rest: a leaf of cartilage rising from the larynx behind
 *  the root of the tongue, lingual face first (base → tip), then laryngeal. */
export const EPIGLOTTIS: readonly Vec2[] = [
  [52.2, 13.4],
  [52.2, 16.8],
  [51.7, 20.2],
  [51.0, 23.2],
  [50.2, 25.4],
  [49.5, 26.2],    // tip
  [49.1, 25.4],
  [49.7, 23.0],
  [50.3, 20.0],
  [50.8, 16.8],
  [51.2, 13.6],
]
/** The epiglottis bends back from its base: index ranges of each face. */
export const EPIGLOTTIS_LINGUAL = 5;

/** The vocal folds, seen from the side: a lip of tissue across the airway. */
export const VOCAL_FOLDS: readonly Vec2[] = [
  [49.2, 11.4],
  [50.6, 11.7],
  [52.4, 11.5],
  [51.2, 10.9],
  [50.0, 10.9],
];

/** Cervical vertebrae C1–C5, behind the pharynx. */
export const VERTEBRAE: readonly (readonly Vec2[])[] = [
  // C1, the anterior arch of the atlas
  [[42.2, 46.2], [43.6, 45.6], [44.6, 47.0], [44.4, 49.0], [43.0, 49.6], [42.0, 48.4]],
  // C2, the body of the axis and its dens rising behind the atlas
  [
    [37.0, 33.8], [43.6, 34.2], [44.0, 38.2], [43.4, 41.8], [41.8, 43.6],
    [41.6, 48.6], [40.4, 50.6], [39.2, 48.8], [38.8, 44.0], [37.2, 41.6], [36.6, 37.2],
  ],
  [[36.8, 24.4], [44.0, 24.8], [44.3, 28.4], [44.2, 31.8], [37.0, 32.0], [36.6, 28.2]],
  [[37.2, 15.0], [44.4, 15.4], [44.8, 19.0], [44.6, 22.4], [37.4, 22.8], [37.0, 19.0]],
  [[37.6, 5.6], [45.0, 6.0], [45.4, 9.6], [45.2, 13.0], [37.8, 13.4], [37.4, 9.6]],
];

/** The nasal bones, at the top of the bridge of the nose. */
export const NASAL_BONE: readonly Vec2[] = [
  [86.6, 66.4],
  [87.8, 66.2],
  [89.2, 63.4],
  [90.2, 61.0],
  [89.4, 60.8],
  [87.8, 63.4],
  [86.2, 65.6],
];

/** The three conchae on the lateral wall of the nose, seen through the
 *  airway: scrolls of bone and mucosa the air is spread over. */
export const CONCHAE: readonly (readonly Vec2[])[] = [
  // inferior: the longest, its free edge curling down towards the floor
  [[57.0, 54.2], [59.4, 56.8], [65.0, 57.8], [72.0, 57.6], [78.0, 56.6], [81.4, 55.0], [80.6, 53.8], [77.4, 54.0], [71.0, 53.7], [64.0, 53.3], [59.6, 53.2]],
  // middle
  [[57.6, 58.8], [60.4, 61.0], [66.0, 61.8], [71.6, 61.4], [75.8, 60.0], [75.0, 58.8], [71.0, 58.8], [64.6, 58.4], [60.0, 58.0]],
  // superior
  [[56.8, 61.8], [59.0, 63.2], [62.6, 63.6], [66.2, 62.8], [64.0, 62.0], [60.0, 61.6]],
]

/** Middle of the nasal airway, from the velopharyngeal port to the nostril:
 *  the road the air takes for /m/, /n/, /ŋ/. */
export const NASAL_PATH: readonly Vec2[] = [
  [48.0, 41.0],
  [48.4, 46.0],
  [50.6, 50.4],
  [55.0, 52.4],
  [62.0, 52.6],
  [70.0, 52.7],
  [78.0, 52.6],
  [85.0, 52.4],
  [90.0, 51.4],
  [93.2, 49.6],
  [94.4, 46.6],
];

/**
 * Centre line of the airway, for the shading of the inside of the head: the
 * far wall of a tube is darkest along its middle. Two branches, oral and
 * nasal, as segments.
 */
export const AIRWAY_SPINE: readonly (readonly Vec2[])[] = [
  [[51.0, 0], [49.6, 12], [49.0, 24], [49.6, 34], [52.0, 40], [58.0, 43.0], [68.0, 43.2], [78.0, 41.0], [86.0, 39.0], [92.0, 38.6]],
  [[48.6, 43.0], [50.4, 49.2], [54.4, 54.4], [63.0, 57.2], [74.0, 57.6], [83.0, 55.4], [89.6, 52.4]],
];

export interface ViewBox {
  /** Centre of the framing. */
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Framing for a panel: nose, lips, chin and the whole tract, with the top of
 *  the skull cropped away. In a box wider than it is tall — which the side
 *  panel is — framing the entire head would leave the mouth, the part anyone
 *  is actually looking at, half the size it could be. */
export const VIEW_MOUTH: ViewBox = { x: 71, y: 38, width: 60, height: 60 };

/** The whole profile, for anywhere there is room for it (the lab page). */
export const VIEW_HEAD: ViewBox = { x: 61, y: 46, width: 82, height: 84 };

/**
 * Names for the parts, for the optional labels. Each is anchored on a point
 * that is inside its part in every pose (the tongue's is low in its body), and
 * says on which side of the anchor its text goes.
 */
export interface AnatomyLabel {
  id: string;
  text: string;
  at: Vec2;
  side: "left" | "right" | "above" | "below";
}

export const LABELS: readonly AnatomyLabel[] = [
  { id: "nasal", text: "nasal cavity", at: [69, 54.6], side: "above" },
  { id: "palate", text: "hard palate", at: [64, 50.2], side: "above" },
  { id: "velum", text: "soft palate", at: [51.6, 46.4], side: "left" },
  { id: "ridge", text: "alveolar ridge", at: [74.6, 45.4], side: "above" },
  { id: "teeth", text: "teeth", at: [81.0, 41.4], side: "right" },
  { id: "lips", text: "lips", at: [90.6, 42.6], side: "right" },
  { id: "tongue", text: "tongue", at: [64.0, 30.0], side: "below" },
  { id: "jaw", text: "jaw", at: [81.6, 24.6], side: "right" },
  { id: "pharynx", text: "pharynx", at: [47.6, 31.0], side: "left" },
  { id: "larynx", text: "vocal folds", at: [51.4, 11.4], side: "left" },
];

// --- geometry helpers --------------------------------------------------------

/** Linear interpolation, the workhorse of every pose blend here. */
export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;

export const lerpPoint = (a: Vec2, b: Vec2, t: number): Vec2 => [
  lerp(a[0], b[0], t),
  lerp(a[1], b[1], t),
];

/**
 * First crossing of the ray `origin + t·dir` (t > 0) with a polyline.
 *
 * This is what ties the tongue to the anatomy: every surface sample is cast
 * from `TONGUE_CENTER` and stopped at the wall, so "fully raised" means
 * "touching the roof exactly here" and not a number that happens to look right.
 * `null` when the ray escapes the outline (in front of the incisors there is no
 * wall, and that is what lets the tip come out between the teeth).
 */
export function rayHit(origin: Vec2, angle: number, path: readonly Vec2[]): number | null {
  const dx = Math.cos(angle);
  const dy = Math.sin(angle);
  let best: number | null = null;
  for (let i = 0; i < path.length - 1; i += 1) {
    const [ax, ay] = path[i];
    const [bx, by] = path[i + 1];
    const ex = bx - ax;
    const ey = by - ay;
    const denominator = dx * ey - dy * ex;
    if (Math.abs(denominator) < 1e-9) continue; // parallel
    const ox = ax - origin[0];
    const oy = ay - origin[1];
    const t = (ox * ey - oy * ex) / denominator;     // along the ray
    const u = (ox * dy - oy * dx) / denominator;     // along the segment
    if (t <= 0 || u < 0 || u > 1) continue;
    if (best === null || t < best) best = t;
  }
  return best;
}

/** Rotate a point around a pivot (the jaw, and everything it carries). */
export function rotateAround(point: Vec2, pivot: Vec2, angle: number): Vec2 {
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  const dx = point[0] - pivot[0];
  const dy = point[1] - pivot[1];
  return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
}

/**
 * Catmull-Rom through the control points, sampled to `count` points.
 *
 * The outlines above are landmarks, not drawings: a straight-line join makes
 * the palate look like a folded sheet. The spline passes through every one of
 * them, so the landmarks stay exact.
 */
export function smoothPath(points: readonly Vec2[], count: number): Vec2[] {
  if (points.length < 2) return points.map((p) => [p[0], p[1]]);
  const out: Vec2[] = [];
  const segments = points.length - 1;
  for (let i = 0; i < count; i += 1) {
    const position = (i / (count - 1)) * segments;
    const index = Math.min(Math.floor(position), segments - 1);
    const t = position - index;
    const p0 = points[Math.max(index - 1, 0)];
    const p1 = points[index];
    const p2 = points[index + 1];
    const p3 = points[Math.min(index + 2, points.length - 1)];
    const t2 = t * t;
    const t3 = t2 * t;
    const at = (a: number, b: number, c: number, d: number) =>
      0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    out.push([at(p0[0], p1[0], p2[0], p3[0]), at(p0[1], p1[1], p2[1], p3[1])]);
  }
  return out;
}

/** One centripetal Catmull-Rom segment between p1 and p2 (Barry–Goldman).
 *  Centripetal parametrisation never overshoots into a loop or a cusp, which
 *  is what kept the uniform spline from being usable on tight anatomy (the
 *  tip of a tooth, the uvula). */
function centripetal(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, t: number): Vec2 {
  const knot = (a: Vec2, b: Vec2) => Math.max(Math.hypot(b[0] - a[0], b[1] - a[1]) ** 0.5, 1e-4);
  const t0 = 0;
  const t1 = t0 + knot(p0, p1);
  const t2 = t1 + knot(p1, p2);
  const t3 = t2 + knot(p2, p3);
  const u = t1 + (t2 - t1) * t;
  const blend = (a: Vec2, b: Vec2, ta: number, tb: number): Vec2 => {
    const w = (u - ta) / (tb - ta);
    return [a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w];
  };
  const a1 = blend(p0, p1, t0, t1);
  const a2 = blend(p1, p2, t1, t2);
  const a3 = blend(p2, p3, t2, t3);
  const b1 = blend(a1, a2, t0, t2);
  const b2 = blend(a2, a3, t1, t3);
  return blend(b1, b2, t1, t2);
}

/** Smooth curve through `points`, `perSegment` samples between landmarks.
 *  Closed outlines wrap around; open ones keep both ends exactly. */
export function spline(points: readonly Vec2[], perSegment: number, closed = false): Vec2[] {
  const n = points.length;
  if (n < 3) return points.map((p) => [p[0], p[1]]);
  const out: Vec2[] = [];
  const at = (i: number): Vec2 => {
    if (closed) return points[((i % n) + n) % n];
    if (i < 0) {
      // Mirror the first point so the curve leaves it heading for the second.
      return [2 * points[0][0] - points[1][0], 2 * points[0][1] - points[1][1]];
    }
    if (i >= n) {
      return [2 * points[n - 1][0] - points[n - 2][0], 2 * points[n - 1][1] - points[n - 2][1]];
    }
    return points[i];
  };
  const segments = closed ? n : n - 1;
  for (let i = 0; i < segments; i += 1) {
    for (let s = 0; s < perSegment; s += 1) {
      out.push(centripetal(at(i - 1), at(i), at(i + 1), at(i + 2), s / perSegment));
    }
  }
  if (!closed) out.push([points[n - 1][0], points[n - 1][1]]);
  return out;
}

/** The same curve resampled to `count` points evenly spaced along its length:
 *  a fixed count is what lets the renderer allocate once. */
export function resample(points: readonly Vec2[], count: number, closed = false): Vec2[] {
  const path = closed ? [...points, points[0]] : points;
  const lengths = [0];
  for (let i = 1; i < path.length; i += 1) {
    lengths.push(lengths[i - 1] + Math.hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1]));
  }
  const total = lengths[lengths.length - 1] || 1;
  const out: Vec2[] = [];
  const steps = closed ? count : count - 1;
  let segment = 0;
  for (let i = 0; i < count; i += 1) {
    const target = (i / steps) * total;
    while (segment < path.length - 2 && lengths[segment + 1] < target) segment += 1;
    const span = lengths[segment + 1] - lengths[segment] || 1;
    const t = Math.min(Math.max((target - lengths[segment]) / span, 0), 1);
    out.push(lerpPoint(path[segment], path[segment + 1], t));
  }
  return out;
}

/** The smooth roof the tongue is clipped against and that gets drawn. */
export const TRACT_ROOF: readonly Vec2[] = spline(TRACT_WALL, 4);
