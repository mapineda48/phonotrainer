/** The three.js side: buffers, shaders, camera and draw order. No phonetics here.
 *
 *  A midsagittal section is a flat thing and the camera looks at it straight
 *  on, so contacts read exactly as the geometry computes them. What makes it
 *  look like an anatomical model rather than a diagram is light:
 *
 *  - every part is a cut face with a soft bevel along its free edges, lit from
 *    the upper left, so the tongue and lips read as rounded flesh and the cut
 *    tissue as a sculpted section (the rim normals are what does it);
 *  - the airway is the far wall of a tube seen through the cut, darker along
 *    its middle, with the conchae standing out on the wall of the nose;
 *  - the tongue carries the fan of the genioglossus and cut bone its spongy
 *    marrow inside a cortical rim, drawn procedurally in the shader so they
 *    cost no texture and stay sharp at any size;
 *  - the tongue and velum cast a soft shadow on that far wall;
 *  - outlines are a constant width in pixels, articulators heavier than the
 *    anatomy around them.
 *
 *  Every mesh has a topology decided once: only positions change per frame,
 *  so the mouth moves at 60 fps beside a running audio player without
 *  allocating. Everything that decides *what shape* to draw is in `tract.ts`,
 *  which is pure and tested.
 */

import {
  AIRWAY_SPINE,
  GLOTTIS,
  LIP_LINE_Y,
  NASAL_PATH,
  VIEW_MOUTH,
  type Vec2,
  type ViewBox,
} from "./anatomy";
import { closed, lowerTooth as lowerToothParts, staticOutlines } from "./outlines";
import { mix, type Palette } from "./palette";
import type { Manner } from "./phones";
import { REST_POSE, type Pose } from "./pose";
import {
  EPIGLOTTIS_POINTS,
  LIP_EXPOSED_POINTS,
  LOWER_LIP_POINTS,
  PLACE_POINTS,
  TONGUE_SURFACE_POINTS,
  UPPER_LIP_POINTS,
  VELUM_POINTS,
  VERMILION_EXPOSED_POINTS,
  VERMILION_POINTS,
  buildTract,
  type TractShapes,
} from "./tract";

/* `import type` is erased at compile time, so the types travel with us while
   three itself stays behind the dynamic import below and out of the main
   bundle: nobody downloads a 3D engine until they open the mouth panel. */
import type * as ThreeJS from "three";

type Three = typeof import("three");

/** What the view needs in order to draw one instant. */
export interface Frame {
  pose: Pose;
  manner: Manner | null;
  /** Seconds. Only the airflow animation reads it. */
  time: number;
  /** False when the audio is stopped: the air stops moving with it. */
  active: boolean;
  /** Reduced motion: the articulators still follow the audio — that motion
   *  is the content — but the air, the voicing and the bursts hold still. */
  still?: boolean;
}

export interface TractView {
  render(frame: Frame): void;
  setPalette(palette: Palette): void;
  setSize(width: number, height: number): void;
  dispose(): void;
}

const PARTICLES = 40;
/** Below this gap (≈ 1 mm) the tract counts as closed: no air comes through. */
const CLOSED = 0.35;
/** How long the puff of a released stop stays on screen. */
const BURST_SECONDS = 0.18;

// --- shaders -------------------------------------------------------------------

const RELIEF_VERTEX = /* glsl */ `
  varying vec3 vNormal;
  varying vec2 vWorld;
  void main() {
    vNormal = normal;
    vWorld = position.xy;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

/** Cut face + bevel lighting + optional muscle fibres or spongy bone. */
const RELIEF_FRAGMENT = /* glsl */ `
  uniform vec3 uColor;
  uniform vec3 uShade;
  uniform vec3 uHigh;
  uniform float uRelief;
  uniform float uGloss;
  uniform float uPattern;
  uniform float uPatternAmount;
  uniform vec3 uPatternColor;
  uniform vec2 uOrigin;
  uniform float uOpacity;
  varying vec3 vNormal;
  varying vec2 vWorld;

  vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
  }

  void main() {
    vec3 key = normalize(vec3(-0.42, 0.62, 0.66));
    vec3 n = normalize(vNormal);
    vec3 color = uColor;

    if (uPattern > 0.5 && uPattern < 1.5) {
      // The genioglossus fans out from the chin: fine radial fibres, faded
      // where they would crowd below a pixel.
      vec2 d = vWorld - uOrigin;
      float r = length(d);
      float f = atan(d.y, d.x) * 10.0 + sin(r * 0.45) * 0.12;
      float w = fwidth(f);
      float s = abs(fract(f) - 0.5) * 2.0;
      float fibre = smoothstep(0.62 - w, 0.62 + w, s);
      float fade = (1.0 - smoothstep(0.18, 0.4, w)) * smoothstep(2.5, 7.0, r);
      color = mix(color, uPatternColor, fibre * fade * uPatternAmount);
    } else if (uPattern > 1.5) {
      // Spongy bone: round pores in a lattice, faded out when too small.
      vec2 p = vWorld * 1.05;
      vec2 cell = floor(p);
      vec2 local = fract(p);
      float nearest = 9.0;
      for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
          vec2 g = vec2(float(x), float(y));
          vec2 o = hash2(cell + g) * 0.7 + 0.15;
          vec2 r = g + o - local;
          nearest = min(nearest, dot(r, r));
        }
      }
      float pore = 1.0 - smoothstep(0.06, 0.15, nearest);
      float fade = 1.0 - smoothstep(0.22, 0.5, fwidth(p.x));
      color = mix(color, uPatternColor, pore * fade * uPatternAmount);
    }

    // Bevel: 0 on a face that looks straight at the viewer.
    float lit = dot(n, key) - key.z;
    color = mix(color, uHigh, clamp(lit * 2.4, 0.0, 1.0) * uRelief);
    color = mix(color, uShade, clamp(-lit * 2.1, 0.0, 1.0) * uRelief);
    vec3 h = normalize(key + vec3(0.0, 0.0, 1.0));
    float spec = max(pow(max(dot(n, h), 0.0), 30.0) - pow(h.z, 30.0), 0.0);
    color += spec * uGloss;

    gl_FragColor = vec4(color, uOpacity);
    #include <colorspace_fragment>
  }
`;

const SEGMENTS_MAX = 16;

/** The airway: darkest along the middle of the tube, lighter where its wall
 *  curves up towards the cut. */
const CAVITY_FRAGMENT = /* glsl */ `
  uniform vec3 uColor;
  uniform vec3 uDeep;
  uniform vec4 uSegments[${SEGMENTS_MAX}];
  uniform int uCount;
  varying vec2 vWorld;

  float segment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
  }

  void main() {
    float d = 1e6;
    for (int i = 0; i < ${SEGMENTS_MAX}; i++) {
      if (i >= uCount) break;
      d = min(d, segment(vWorld, uSegments[i].xy, uSegments[i].zw));
    }
    float depth = exp(-d * d / (2.0 * 3.4 * 3.4));
    gl_FragColor = vec4(mix(uColor, uDeep, depth), 1.0);
    #include <colorspace_fragment>
  }
`;

const ALPHA_VERTEX = /* glsl */ `
  attribute float alpha;
  varying float vAlpha;
  void main() {
    vAlpha = alpha;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const ALPHA_FRAGMENT = /* glsl */ `
  uniform vec3 uColor;
  uniform float uOpacity;
  varying float vAlpha;
  void main() {
    gl_FragColor = vec4(uColor, vAlpha * uOpacity);
    #include <colorspace_fragment>
  }
`;

// --- polygon helpers -------------------------------------------------------------

const signedArea = (points: readonly Vec2[]): number => {
  let area = 0;
  for (let i = 0; i < points.length; i += 1) {
    const [ax, ay] = points[i];
    const [bx, by] = points[(i + 1) % points.length];
    area += ax * by - bx * ay;
  }
  return area / 2;
};

/** Outward unit normals at each vertex of a closed polygon. */
function outwardNormals(points: readonly Vec2[], out: Float32Array): void {
  const n = points.length;
  const sign = signedArea(points) >= 0 ? 1 : -1;
  for (let i = 0; i < n; i += 1) {
    const [px, py] = points[(i - 1 + n) % n];
    const [nx, ny] = points[(i + 1) % n];
    let dx = nx - px;
    let dy = ny - py;
    const length = Math.hypot(dx, dy) || 1;
    dx /= length;
    dy /= length;
    // Counter-clockwise: the outside is on the right of the direction of travel.
    out[i * 2] = dy * sign;
    out[i * 2 + 1] = -dx * sign;
  }
}

/** How far one can go inwards from vertex i before leaving the polygon. */
function depthInside(points: readonly Vec2[], i: number, nx: number, ny: number): number {
  const n = points.length;
  const [ox, oy] = points[i];
  const dx = -nx;
  const dy = -ny;
  let best = Infinity;
  for (let j = 0; j < n; j += 1) {
    if (j === i || (j + 1) % n === i) continue;
    const [ax, ay] = points[j];
    const [bx, by] = points[(j + 1) % n];
    const ex = bx - ax;
    const ey = by - ay;
    const denominator = dx * ey - dy * ex;
    if (Math.abs(denominator) < 1e-9) continue;
    const qx = ax - ox;
    const qy = ay - oy;
    const t = (qx * ey - qy * ex) / denominator;
    const u = (qx * dy - qy * dx) / denominator;
    if (t > 0.02 && u >= 0 && u <= 1 && t < best) best = t;
  }
  return best;
}

interface SolidOptions {
  /** Width of the bevel (or of the cortical rim), in units. */
  rim: number;
  /** "each": re-triangulated every frame (it changes shape); "once": the
   *  shape only moves, so the first triangulation stays valid. */
  fill: "each" | "once";
  /** How steep the bevel is. Zero draws the rim flat (a cortical band). */
  tilt?: number;
}

/**
 * A closed shape drawn as a cut face with a rim: the fill, and a band just
 * inside the outline whose normals tilt outwards — a bevel when lit, or a
 * cortical rim when the band gets a material of its own.
 */
class Solid {
  readonly fillMesh: ThreeJS.Mesh;
  readonly rimMesh: ThreeJS.Mesh;
  private readonly count: number;
  private readonly positions: Float32Array;
  private readonly normals: Float32Array;
  private readonly position: ThreeJS.BufferAttribute;
  private readonly normal: ThreeJS.BufferAttribute;
  private readonly fillIndex: ThreeJS.BufferAttribute;
  private readonly fillGeometry: ThreeJS.BufferGeometry;
  private readonly rimGeometry: ThreeJS.BufferGeometry;
  private readonly edge: Float32Array;
  private readonly widths: Float32Array;
  private readonly blurred: Float32Array;
  private readonly contour: ThreeJS.Vector2[];
  private triangulated = false;
  private readonly THREE: Three;

  constructor(
    THREE: Three,
    count: number,
    fill: ThreeJS.Material,
    rim: ThreeJS.Material,
    order: number,
    private readonly options: SolidOptions,
  ) {
    this.THREE = THREE;
    this.count = count;
    // [0, n): the fill, facing the viewer. [n, 2n): the outline, normals
    // tilted outwards. [2n, 3n): the inner edge of the rim, facing the viewer.
    this.positions = new Float32Array(count * 3 * 3);
    this.normals = new Float32Array(count * 3 * 3);
    for (let i = 0; i < count * 3; i += 1) this.normals[i * 3 + 2] = 1;
    this.position = new THREE.BufferAttribute(this.positions, 3);
    this.normal = new THREE.BufferAttribute(this.normals, 3);
    this.edge = new Float32Array(count * 2);
    this.widths = new Float32Array(count);
    this.blurred = new Float32Array(count);
    this.contour = Array.from({ length: count }, () => new THREE.Vector2());

    this.fillGeometry = new THREE.BufferGeometry();
    this.fillGeometry.setAttribute("position", this.position);
    this.fillGeometry.setAttribute("normal", this.normal);
    this.fillIndex = new THREE.BufferAttribute(new Uint16Array(Math.max(count - 2, 1) * 3), 1);
    this.fillGeometry.setIndex(this.fillIndex);

    this.rimGeometry = new THREE.BufferGeometry();
    this.rimGeometry.setAttribute("position", this.position);
    this.rimGeometry.setAttribute("normal", this.normal);
    const rimIndex: number[] = [];
    for (let i = 0; i < count; i += 1) {
      const a = count + i;
      const b = count + ((i + 1) % count);
      const c = 2 * count + i;
      const d = 2 * count + ((i + 1) % count);
      rimIndex.push(a, b, c, b, d, c);
    }
    this.rimGeometry.setIndex(rimIndex);

    this.fillMesh = new THREE.Mesh(this.fillGeometry, fill);
    this.rimMesh = new THREE.Mesh(this.rimGeometry, rim);
    this.fillMesh.renderOrder = order;
    this.rimMesh.renderOrder = order + 0.1;
    this.fillMesh.frustumCulled = false;
    this.rimMesh.frustumCulled = false;
  }

  /** `exposure` (0..1 per point) scales the rim: 0 where the shape grows out
   *  of another one and has no edge of its own. */
  update(points: readonly Vec2[], exposure?: ArrayLike<number>): void {
    const n = this.count;
    const { rim, tilt = 1.5 } = this.options;
    outwardNormals(points, this.edge);
    const widths = this.widths;
    const sign = signedArea(points) >= 0 ? 1 : -1;
    for (let i = 0; i < n; i += 1) {
      const e = exposure ? exposure[i] : 1;
      if (e <= 0 || rim <= 0) {
        widths[i] = 0;
        continue;
      }
      const nx = this.edge[i * 2];
      const ny = this.edge[i * 2 + 1];
      // At a concave corner the inner edge of the rim would fold over itself:
      // narrow it there, in proportion to how sharply the outline turns in.
      const [ax, ay] = points[(i - 1 + n) % n];
      const [bx, by] = points[i];
      const [cx, cy] = points[(i + 1) % n];
      const turn = ((bx - ax) * (cy - by) - (by - ay) * (cx - bx)) * sign;
      const bend = turn < 0 ? Math.max(0.25, 1 - (-turn / (Math.hypot(bx - ax, by - ay) * Math.hypot(cx - bx, cy - by) || 1)) * 2.5) : 1;
      widths[i] = Math.min(rim * e * bend, 0.42 * depthInside(points, i, nx, ny));
    }
    // A width that jumps from one point to the next draws a crease: blur it.
    for (let pass = 0; pass < 2; pass += 1) {
      for (let i = 0; i < n; i += 1) this.blurred[i] = widths[i];
      for (let i = 0; i < n; i += 1) {
        const around = (this.blurred[(i - 1 + n) % n] + this.blurred[(i + 1) % n]) / 2;
        widths[i] = Math.min(this.blurred[i], 0.5 * this.blurred[i] + 0.5 * around);
      }
    }
    for (let i = 0; i < n; i += 1) {
      const [x, y] = points[i];
      const nx = this.edge[i * 2];
      const ny = this.edge[i * 2 + 1];
      const e = exposure ? exposure[i] : 1;
      const width = widths[i];
      this.positions.set([x, y, 0], i * 3);
      this.positions.set([x, y, 0], (n + i) * 3);
      this.positions.set([x - nx * width, y - ny * width, 0], (2 * n + i) * 3);
      const t = tilt * e;
      const length = Math.hypot(nx * t, ny * t, 1);
      this.normals.set([(nx * t) / length, (ny * t) / length, 1 / length], (n + i) * 3);
      this.contour[i].set(x, y);
    }
    this.position.needsUpdate = true;
    this.normal.needsUpdate = true;

    if (this.options.fill === "each" || !this.triangulated) {
      const faces = this.THREE.ShapeUtils.triangulateShape(this.contour.slice(), []);
      const index = this.fillIndex.array as Uint16Array;
      index.fill(0);
      let k = 0;
      for (const face of faces) {
        if (k + 3 > index.length) break;
        index[k] = face[0];
        index[k + 1] = face[1];
        index[k + 2] = face[2];
        k += 3;
      }
      this.fillIndex.needsUpdate = true;
      this.triangulated = true;
    }
  }

  get meshes(): ThreeJS.Mesh[] {
    return [this.fillMesh, this.rimMesh];
  }

  dispose(): void {
    this.fillGeometry.dispose();
    this.rimGeometry.dispose();
  }
}

/**
 * A line with real thickness: WebGL cannot widen a line primitive. The width
 * is in pixels, converted to units by the caller, so an outline is as crisp
 * on a thumbnail as on the lab page. Open strokes taper at both ends.
 */
class Stroke {
  readonly mesh: ThreeJS.Mesh;
  private readonly positions: Float32Array;
  private readonly geometry: ThreeJS.BufferGeometry;
  private readonly count: number;

  constructor(
    THREE: Three,
    count: number,
    material: ThreeJS.Material,
    order: number,
    private readonly loop: boolean,
  ) {
    this.count = count;
    this.positions = new Float32Array(count * 2 * 3);
    this.geometry = new THREE.BufferGeometry();
    this.geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3));
    const index: number[] = [];
    const segments = loop ? count : count - 1;
    for (let i = 0; i < segments; i += 1) {
      const a = i;
      const b = (i + 1) % count;
      index.push(a, count + a, b, b, count + a, count + b);
    }
    this.geometry.setIndex(index);
    this.mesh = new THREE.Mesh(this.geometry, material);
    this.mesh.renderOrder = order;
    this.mesh.frustumCulled = false;
  }

  /** `widths` scales the width per point (0..1), on top of the taper. */
  update(points: readonly Vec2[], width: number, widths?: ArrayLike<number>): void {
    const n = this.count;
    for (let i = 0; i < n; i += 1) {
      const previous = this.loop ? points[(i - 1 + n) % n] : points[Math.max(i - 1, 0)];
      const next = this.loop ? points[(i + 1) % n] : points[Math.min(i + 1, n - 1)];
      const dx = next[0] - previous[0];
      const dy = next[1] - previous[1];
      const length = Math.hypot(dx, dy) || 1;
      let w = (width / 2) * (widths ? widths[i] : 1);
      if (!this.loop) {
        const end = Math.min(i, n - 1 - i);
        if (end < 3) w *= 0.35 + 0.65 * (end / 3);
      }
      const nx = (-dy / length) * w;
      const ny = (dx / length) * w;
      this.positions.set([points[i][0] + nx, points[i][1] + ny, 0], i * 3);
      this.positions.set([points[i][0] - nx, points[i][1] - ny, 0], (n + i) * 3);
    }
    this.geometry.attributes.position.needsUpdate = true;
  }

  dispose(): void {
    this.geometry.dispose();
  }
}

/**
 * A soft shape: a core at full alpha with a feathered band around it fading
 * to nothing. The shadow the tongue casts on the far wall, and the glow on the
 * place of articulation, are both this.
 */
class Feather {
  readonly mesh: ThreeJS.Mesh;
  private readonly positions: Float32Array;
  private readonly geometry: ThreeJS.BufferGeometry;
  private readonly fillIndex: ThreeJS.BufferAttribute;
  private readonly edge: Float32Array;
  private readonly contour: ThreeJS.Vector2[];
  private readonly THREE: Three;

  constructor(THREE: Three, private readonly count: number, material: ThreeJS.Material, order: number) {
    this.THREE = THREE;
    this.positions = new Float32Array(count * 2 * 3);
    const alpha = new Float32Array(count * 2);
    alpha.fill(1, 0, count);
    this.geometry = new THREE.BufferGeometry();
    this.geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3));
    this.geometry.setAttribute("alpha", new THREE.BufferAttribute(alpha, 1));
    const fill = Math.max(count - 2, 1) * 3;
    this.fillIndex = new THREE.BufferAttribute(new Uint16Array(fill + count * 6), 1);
    const index = this.fillIndex.array as Uint16Array;
    for (let i = 0; i < count; i += 1) {
      const a = i;
      const b = (i + 1) % count;
      index.set([a, b, count + a, b, count + b, count + a], fill + i * 6);
    }
    this.geometry.setIndex(this.fillIndex);
    this.edge = new Float32Array(count * 2);
    this.contour = Array.from({ length: count }, () => new THREE.Vector2());
    this.mesh = new THREE.Mesh(this.geometry, material);
    this.mesh.renderOrder = order;
    this.mesh.frustumCulled = false;
  }

  /** `core` (0..1 per point) fades the shape itself, not only its rim. */
  update(points: readonly Vec2[], offset: Vec2, feather: number, shrink = 0, core?: ArrayLike<number>): void {
    const n = this.count;
    outwardNormals(points, this.edge);
    if (core) {
      const alpha = this.geometry.attributes.alpha as ThreeJS.BufferAttribute;
      for (let i = 0; i < n; i += 1) alpha.setX(i, core[i]);
      alpha.needsUpdate = true;
    }
    for (let i = 0; i < n; i += 1) {
      const nx = this.edge[i * 2];
      const ny = this.edge[i * 2 + 1];
      const x = points[i][0] + offset[0] - nx * shrink;
      const y = points[i][1] + offset[1] - ny * shrink;
      this.positions.set([x, y, 0], i * 3);
      this.positions.set([x + nx * feather, y + ny * feather, 0], (n + i) * 3);
      this.contour[i].set(x, y);
    }
    this.geometry.attributes.position.needsUpdate = true;
    const faces = this.THREE.ShapeUtils.triangulateShape(this.contour.slice(), []);
    const index = this.fillIndex.array as Uint16Array;
    const fill = Math.max(n - 2, 1) * 3;
    index.fill(0, 0, fill);
    let k = 0;
    for (const face of faces) {
      if (k + 3 > fill) break;
      index.set(face, k);
      k += 3;
    }
    this.fillIndex.needsUpdate = true;
  }

  dispose(): void {
    this.geometry.dispose();
  }
}

/** Round sprite (the airflow, and the halo on a closure), painted once. */
function softTexture(THREE: Three): ThreeJS.CanvasTexture {
  const canvas = document.createElement("canvas");
  canvas.width = 64;
  canvas.height = 64;
  const context = canvas.getContext("2d");
  if (context) {
    const gradient = context.createRadialGradient(32, 32, 0, 32, 32, 32);
    gradient.addColorStop(0, "rgba(255,255,255,1)");
    gradient.addColorStop(0.45, "rgba(255,255,255,0.55)");
    gradient.addColorStop(1, "rgba(255,255,255,0)");
    context.fillStyle = gradient;
    context.fillRect(0, 0, 64, 64);
  }
  return new THREE.CanvasTexture(canvas);
}

function pointOnPath(path: readonly Vec2[], t: number): Vec2 {
  const position = Math.min(Math.max(t, 0), 1) * (path.length - 1);
  const index = Math.min(Math.floor(position), path.length - 2);
  const local = position - index;
  const a = path[index];
  const b = path[index + 1];
  return [a[0] + (b[0] - a[0]) * local, a[1] + (b[1] - a[1]) * local];
}

/** Exposure of each lip point: the free surface, then the seam with the face,
 *  with a short ramp so the bevel does not stop dead. */
function lipExposure(count: number): Float32Array {
  const out = new Float32Array(count);
  for (let i = 0; i < count; i += 1) {
    const end = Math.min(i, LIP_EXPOSED_POINTS - 1 - i);
    out[i] = i >= LIP_EXPOSED_POINTS ? 0 : Math.min(1, (end + 1) / 3);
  }
  return out;
}

/** Exposure by distance from a seam: zero on it, full a little away. */
function seamExposure(points: readonly Vec2[], seam: Vec2, reach: number, out: Float32Array): Float32Array {
  points.forEach(([x, y], i) => {
    const d = Math.hypot(x - seam[0], y - seam[1]);
    out[i] = Math.min(1, Math.max(0, (d - reach * 0.5) / reach));
  });
  return out;
}

/**
 * Build the view over `canvas`.
 *
 * Returns null when there is no WebGL — an old browser, a blocked context,
 * jsdom under the tests — and the caller then draws the same geometry as plain
 * SVG rather than showing an empty box.
 */
export async function createTractView(
  canvas: HTMLCanvasElement,
  palette: Palette,
  view: ViewBox = VIEW_MOUTH,
): Promise<TractView | null> {
  const THREE = await import("three");

  let renderer: ThreeJS.WebGLRenderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  } catch {
    return null;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

  const scene = new THREE.Scene();
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
  camera.position.z = 10;

  // --- materials ------------------------------------------------------------
  // No depth test: in a section, draw order is the only layering there is,
  // and renderOrder states it outright. Every material is "transparent" so
  // that they all go through the one sorted pass: three.js draws opaque
  // objects first whatever their renderOrder, which put the tongue's shadow
  // and the half-opaque outlines on top of the tongue. Double-sided because
  // outlines are authored for reading, not for winding order.
  const base = { depthTest: false, depthWrite: false, side: THREE.DoubleSide, transparent: true };

  interface ReliefLook {
    color: string;
    relief: number;
    gloss?: number;
    shade?: string;
    pattern?: 0 | 1 | 2;
    patternColor?: string;
    patternAmount?: number;
  }

  const relief = (): ThreeJS.ShaderMaterial =>
    new THREE.ShaderMaterial({
      ...base,
      vertexShader: RELIEF_VERTEX,
      fragmentShader: RELIEF_FRAGMENT,
      uniforms: {
        uColor: { value: new THREE.Color() },
        uShade: { value: new THREE.Color() },
        uHigh: { value: new THREE.Color() },
        uRelief: { value: 0 },
        uGloss: { value: 0 },
        uPattern: { value: 0 },
        uPatternAmount: { value: 0 },
        uPatternColor: { value: new THREE.Color() },
        uOrigin: { value: new THREE.Vector2() },
        uOpacity: { value: 1 },
      },
    });

  const looks = new Map<ThreeJS.ShaderMaterial, (p: Palette) => ReliefLook>();
  const material = (look: (p: Palette) => ReliefLook): ThreeJS.ShaderMaterial => {
    const made = relief();
    looks.set(made, look);
    return made;
  };

  const tissue = material((p) => ({ color: p.tissue, relief: p.light ? 0.55 : 0.6, gloss: 0.02 }));
  const conchaMat = material((p) => ({ color: p.concha, relief: 0.55 }));
  const marrowMat = material((p) => ({
    color: p.marrow, relief: 0, pattern: 2, patternColor: mix(p.marrow, p.tissue, p.light ? 0.85 : 0.7), patternAmount: 0.75,
  }));
  const cortex = material((p) => ({ color: p.bone, relief: 0.25 }));
  const enamel = material((p) => ({ color: p.bone, relief: 0.5, gloss: p.light ? 0.08 : 0.06 }));
  const dentine = material((p) => ({ color: p.dentine, relief: 0.2 }));
  const cartilage = material((p) => ({ color: p.cartilage, relief: 0.6, gloss: 0.04 }));
  const lipMat = material((p) => ({ color: mix(p.lip, p.tissue, 0.45), relief: 0.75, gloss: 0.03 }));
  const vermilionMat = material((p) => ({ color: p.lip, relief: 0.9, gloss: p.light ? 0.1 : 0.08 }));
  const foldMat = material((p) => ({ color: p.lip, relief: 0.7 }));
  const velumMat = material((p) => ({ color: mix(p.lip, p.tissue, 0.3), relief: 0.85, gloss: 0.05 }));
  const tongueMat = material((p) => ({
    color: p.tongue, shade: p.tongueDeep, relief: 1, gloss: p.light ? 0.1 : 0.08,
    pattern: 1, patternColor: p.tongueDeep, patternAmount: p.light ? 0.08 : 0.1,
  }));

  const cavity = new THREE.ShaderMaterial({
    ...base,
    vertexShader: RELIEF_VERTEX,
    fragmentShader: CAVITY_FRAGMENT,
    uniforms: {
      uColor: { value: new THREE.Color() },
      uDeep: { value: new THREE.Color() },
      uSegments: { value: Array.from({ length: SEGMENTS_MAX }, () => new THREE.Vector4()) },
      uCount: { value: 0 },
    },
  });
  {
    let k = 0;
    for (const branch of AIRWAY_SPINE) {
      for (let i = 0; i < branch.length - 1 && k < SEGMENTS_MAX; i += 1, k += 1) {
        const [ax, ay] = branch[i];
        const [bx, by] = branch[i + 1];
        (cavity.uniforms.uSegments.value[k] as ThreeJS.Vector4).set(ax, ay, bx, by);
      }
    }
    cavity.uniforms.uCount.value = k;
  }

  const alphaMaterial = (): ThreeJS.ShaderMaterial =>
    new THREE.ShaderMaterial({
      ...base,
      vertexShader: ALPHA_VERTEX,
      fragmentShader: ALPHA_FRAGMENT,
      uniforms: { uColor: { value: new THREE.Color() }, uOpacity: { value: 1 } },
    });
  const shadowMat = alphaMaterial();

  const flat = (opacity = 1): ThreeJS.MeshBasicMaterial =>
    new THREE.MeshBasicMaterial({ ...base, opacity });
  const lines = {
    strong: flat(0.9),   // articulators
    skin: flat(0.75),    // the profile and the walls of the airway
    fine: flat(0.45),    // bone and teeth inside the tissue
    faint: flat(0.28),   // conchae, seen through the airway
  };
  const accent = flat(0.85);
  const placeMat = flat(0.6);
  const halo = new THREE.MeshBasicMaterial({ ...base, opacity: 0.2 });
  const burstMat = flat(0.8);
  const rippleMat = flat(0.6);

  const add = (...meshes: ThreeJS.Object3D[]) => {
    for (const mesh of meshes) scene.add(mesh);
  };

  // --- the airway and what is seen through it ---------------------------------
  const sample = buildTract(REST_POSE);
  const airway = new Solid(THREE, sample.airway.length, cavity, cavity, 0, { rim: 0, fill: "each" });
  add(...airway.meshes);

  const statics: { stroke: Stroke; points: Vec2[]; px: number; material: ThreeJS.Material }[] = [];
  const staticSolid = (
    points: Vec2[],
    fill: ThreeJS.Material,
    rim: ThreeJS.Material,
    order: number,
    options: Partial<SolidOptions> & { rim: number },
    stroke?: { px: number; material: ThreeJS.Material },
  ): Solid => {
    const solid = new Solid(THREE, points.length, fill, rim, order, { fill: "once", ...options });
    solid.update(points);
    add(...solid.meshes);
    if (stroke) {
      const line = new Stroke(THREE, points.length, stroke.material, order + 0.5, true);
      statics.push({ stroke: line, points, px: stroke.px, material: stroke.material });
      add(line.mesh);
    }
    return solid;
  };

  const outlines = staticOutlines();
  const solids: Solid[] = [airway];
  for (const concha of outlines.conchae) {
    solids.push(staticSolid(concha, conchaMat, conchaMat, 1, { rim: 0.9 }, { px: 0.8, material: lines.faint }));
  }

  // Shadows on the far wall, under everything that is cut.
  const tongueShadow = new Feather(THREE, TONGUE_SURFACE_POINTS * 2 - 1, shadowMat, 2);
  const velumShadow = new Feather(THREE, VELUM_POINTS, shadowMat, 2);
  add(tongueShadow.mesh, velumShadow.mesh);

  // --- the fixed head ----------------------------------------------------------
  solids.push(staticSolid(outlines.head, tissue, tissue, 3, { rim: 1.3 }, { px: 1.2, material: lines.skin }));
  for (const vertebra of outlines.vertebrae) {
    solids.push(staticSolid(vertebra, marrowMat, cortex, 4, { rim: 0.55, tilt: 0.4 }, { px: 0.8, material: lines.fine }));
  }
  solids.push(staticSolid(outlines.nasalBone, marrowMat, cortex, 4, { rim: 0.4, tilt: 0.4 }, { px: 0.8, material: lines.fine }));
  solids.push(staticSolid(outlines.maxilla, marrowMat, cortex, 6, { rim: 0.45, tilt: 0.4 }, { px: 0.8, material: lines.fine }));
  const upperTooth = outlines.upperTooth;
  solids.push(staticSolid(upperTooth.outline, dentine, dentine, 7, { rim: 0.3 }, { px: 0.8, material: lines.fine }));
  solids.push(staticSolid(upperTooth.crown, enamel, enamel, 15.7, { rim: 0.6 }, { px: 1, material: lines.fine }));

  // --- everything that moves ---------------------------------------------------
  const jawCount = closed(sample.jawTissue).length;
  const jaw = new Solid(THREE, jawCount, tissue, tissue, 8, { rim: 1.2, fill: "once" });
  const jawLine = new Stroke(THREE, jawCount, lines.skin, 8.5, true);
  const thyroidCount = closed(sample.thyroid, 3).length;
  const thyroid = new Solid(THREE, thyroidCount, cartilage, cartilage, 9, { rim: 0.4, fill: "once" });
  const thyroidLine = new Stroke(THREE, thyroidCount, lines.fine, 9.5, true);
  const hyoidCount = closed(sample.hyoid, 3).length;
  const hyoid = new Solid(THREE, hyoidCount, marrowMat, cortex, 9, { rim: 0.4, tilt: 0.4, fill: "once" });
  const hyoidLine = new Stroke(THREE, hyoidCount, lines.fine, 9.5, true);
  const mandibleCount = closed(sample.mandible).length;
  const mandible = new Solid(THREE, mandibleCount, marrowMat, cortex, 10, { rim: 0.55, tilt: 0.4, fill: "once" });
  const mandibleLine = new Stroke(THREE, mandibleCount, lines.fine, 10.5, true);
  const lowerToothCount = lowerToothParts(sample.lowerTeeth);
  const lowerRoot = new Solid(THREE, lowerToothCount.outline.length, dentine, dentine, 11, { rim: 0.3, fill: "once" });
  const lowerRootLine = new Stroke(THREE, lowerToothCount.outline.length, lines.fine, 11.5, true);
  const lowerCrown = new Solid(THREE, lowerToothCount.crown.length, enamel, enamel, 15.6, { rim: 0.6, fill: "once" });
  const lowerCrownLine = new Stroke(THREE, lowerToothCount.crown.length, lines.fine, 15.65, true);

  solids.push(staticSolid(outlines.folds, foldMat, foldMat, 12, { rim: 0.4 }, { px: 0.8, material: lines.fine }));
  const epiglottis = new Solid(THREE, EPIGLOTTIS_POINTS, cartilage, cartilage, 13, { rim: 0.45, fill: "each" });
  const epiglottisLine = new Stroke(THREE, EPIGLOTTIS_POINTS, lines.skin, 13.5, false);
  const velum = new Solid(THREE, VELUM_POINTS, velumMat, velumMat, 14, { rim: 1.0, fill: "each" });
  const velumLine = new Stroke(THREE, VELUM_POINTS, lines.strong, 14.5, false);
  const tongueCount = TONGUE_SURFACE_POINTS * 2 - 1;
  const tongue = new Solid(THREE, tongueCount, tongueMat, tongueMat, 15, { rim: 1.7, tilt: 1.7, fill: "each" });
  const tongueLine = new Stroke(THREE, tongueCount, lines.strong, 15.5, false);
  const upperLip = new Solid(THREE, UPPER_LIP_POINTS, lipMat, lipMat, 16, { rim: 1.1, fill: "each" });
  const lowerLip = new Solid(THREE, LOWER_LIP_POINTS, lipMat, lipMat, 16, { rim: 1.1, fill: "each" });
  const upperVermilion = new Solid(THREE, VERMILION_POINTS, vermilionMat, vermilionMat, 16.2, { rim: 0.9, fill: "each" });
  const lowerVermilion = new Solid(THREE, VERMILION_POINTS, vermilionMat, vermilionMat, 16.2, { rim: 0.9, fill: "each" });
  const upperLipLine = new Stroke(THREE, LIP_EXPOSED_POINTS, lines.strong, 16.5, false);
  const lowerLipLine = new Stroke(THREE, LIP_EXPOSED_POINTS, lines.strong, 16.5, false);
  const moving = [
    jaw, thyroid, hyoid, mandible, lowerRoot, lowerCrown, epiglottis, velum, tongue,
    upperLip, lowerLip, upperVermilion, lowerVermilion,
  ];
  const movingLines = [
    jawLine, thyroidLine, hyoidLine, mandibleLine, lowerRootLine, lowerCrownLine,
    epiglottisLine, velumLine, tongueLine, upperLipLine, lowerLipLine,
  ];
  for (const part of moving) add(...part.meshes);
  for (const line of movingLines) add(line.mesh);

  const lipExposed = lipExposure(UPPER_LIP_POINTS);
  const vermilionExposed = Float32Array.from({ length: VERMILION_POINTS }, (_, i) =>
    i < VERMILION_EXPOSED_POINTS ? Math.min(1, (Math.min(i, VERMILION_EXPOSED_POINTS - 1 - i) + 1) / 3) : 0,
  );
  const velumExposed = new Float32Array(VELUM_POINTS);
  const epiglottisExposed = new Float32Array(EPIGLOTTIS_POINTS);
  // The tongue: full bevel on the surface, the tip and the underside of the
  // blade; fading out behind the frenulum, where it is one with the floor.
  const tongueExposed = new Float32Array(tongueCount);
  const tongueWidths = new Float32Array(tongueCount);
  for (let i = 0; i < tongueCount; i += 1) {
    const underside = i - (TONGUE_SURFACE_POINTS - 1);
    const fromRoot = Math.min(i, tongueCount - 1 - i);
    const floorFade = underside > 0 ? 1 - Math.min(1, Math.max(0, (underside - 12) / 8)) : 1;
    tongueExposed[i] = Math.min(1, fromRoot / 4) * (0.35 + 0.65 * floorFade);
    tongueWidths[i] = 0.55 + 0.45 * floorFade;
  }

  // --- what the phone is doing, made visible ---------------------------------
  const texture = softTexture(THREE);
  halo.map = texture;
  // The place of articulation: the stretch of roof the tongue is working
  // against, underlined in the accent colour.
  const placeLine = new Stroke(THREE, PLACE_POINTS, placeMat, 17, false);
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(7, 7), halo);
  glow.renderOrder = 17.5;
  const ring = new THREE.Mesh(new THREE.RingGeometry(1.8, 2.0, 64), accent);
  ring.renderOrder = 18;
  const burst = new THREE.Mesh(new THREE.RingGeometry(0.9, 1.12, 48), burstMat);
  burst.renderOrder = 18;
  const ripples = [0, 1].map(() => {
    const mesh = new THREE.Mesh(new THREE.RingGeometry(1.0, 1.22, 40, 1, 0.25 * Math.PI, 0.5 * Math.PI), rippleMat.clone());
    mesh.renderOrder = 18;
    mesh.position.set(GLOTTIS[0], GLOTTIS[1] + 0.4, 0);
    return mesh;
  });
  for (const mesh of [glow, ring, burst, ...ripples]) mesh.frustumCulled = false;
  add(placeLine.mesh, glow, ring, burst, ...ripples);

  const flowPositions = new Float32Array(PARTICLES * 3);
  const flowGeometry = new THREE.BufferGeometry();
  flowGeometry.setAttribute("position", new THREE.BufferAttribute(flowPositions, 3));
  const flowMaterial = new THREE.PointsMaterial({
    size: 1.8,
    map: texture,
    transparent: true,
    opacity: 0.7,
    depthTest: false,
    depthWrite: false,
  });
  const flow = new THREE.Points(flowGeometry, flowMaterial);
  flow.renderOrder = 19;
  flow.frustumCulled = false;
  scene.add(flow);

  /** Each particle carries its own offset so the stream does not pulse in step. */
  const seeds = Array.from({ length: PARTICLES }, (_, i) => ({
    phase: i / PARTICLES,
    lane: (i % 7) / 6 - 0.5,
    speed: 0.75 + ((i * 37) % 11) / 20,
  }));

  let previous: { gap: number; manner: Manner | null; at: Vec2 } | null = null;
  let burstAt = -1;
  let burstWhere: Vec2 = [0, 0];
  /** Units per CSS pixel: strokes are specified in pixels. */
  let unit = view.height / 240;

  const restroke = () => {
    for (const { stroke, points, px } of statics) stroke.update(points, px * unit);
  };

  const setSize = (width: number, height: number): void => {
    const w = Math.max(1, Math.floor(width));
    const h = Math.max(1, Math.floor(height));
    renderer.setSize(w, h, false);
    const aspect = w / h;
    let halfWidth = view.width / 2;
    let halfHeight = view.height / 2;
    if (aspect > view.width / view.height) halfWidth = halfHeight * aspect;
    else halfHeight = halfWidth / aspect;
    camera.left = view.x - halfWidth;
    camera.right = view.x + halfWidth;
    camera.top = view.y + halfHeight;
    camera.bottom = view.y - halfHeight;
    camera.updateProjectionMatrix();
    unit = (2 * halfHeight) / h;
    // Points are sized in pixels by the renderer: keep the air a few units wide.
    flowMaterial.size = Math.max(3, Math.min(14, 1.7 / unit));
    restroke();
  };
  setSize(300, 240);

  /** Where the air goes, and how hard, for this manner. */
  function updateFlow(frame: Frame, tract: TractShapes): void {
    const manner = frame.manner;
    const nasal = frame.pose.velum > 0.5;
    const shut = tract.constriction.gap < CLOSED;
    const noisy = manner === "fricative" || manner === "affricate" || manner === "glottal";
    flow.visible = frame.active && frame.pose.voice + (noisy ? 1 : 0) > 0 && !(shut && !nasal);
    if (!flow.visible) return;

    const exit: Vec2 = [95, LIP_LINE_Y - frame.pose.jaw * 2.2];
    const from: Vec2 = noisy ? tract.constriction.point : [GLOTTIS[0], GLOTTIS[1] + 5];
    const speed = noisy ? 0.55 : 0.3;
    const jitter = noisy ? 2.2 : 0.6;
    // Reduced motion: the stream is drawn where it goes, but does not travel.
    const time = frame.still ? 0 : frame.time;

    for (let i = 0; i < PARTICLES; i += 1) {
      const seed = seeds[i];
      const t = (time * speed * seed.speed + seed.phase) % 1;
      if (nasal) {
        const point = pointOnPath(NASAL_PATH, t);
        flowPositions[i * 3] = point[0];
        flowPositions[i * 3 + 1] = point[1] + seed.lane * 1.2;
        continue;
      }
      flowPositions[i * 3] = from[0] + (exit[0] - from[0]) * t;
      flowPositions[i * 3 + 1] =
        from[1] +
        (exit[1] - from[1]) * t +
        seed.lane * (1.2 + jitter * t) +
        (noisy && !frame.still ? Math.sin((time * 9 + seed.phase * 11) * Math.PI) * jitter * t : 0);
    }
    flowGeometry.attributes.position.needsUpdate = true;
    flowMaterial.opacity = noisy ? 0.75 : 0.45;
  }

  function render(frame: Frame): void {
    const tract = buildTract(frame.pose);
    tongueMat.uniforms.uOrigin.value.set(tract.muscleOrigin[0], tract.muscleOrigin[1]);

    airway.update(tract.airway);

    const jawOutline = closed(tract.jawTissue);
    jaw.update(jawOutline);
    jawLine.update(jawOutline, 1.2 * unit);
    const thyroidOutline = closed(tract.thyroid, 3);
    thyroid.update(thyroidOutline);
    thyroidLine.update(thyroidOutline, 0.8 * unit);
    const hyoidOutline = closed(tract.hyoid, 3);
    hyoid.update(hyoidOutline);
    hyoidLine.update(hyoidOutline, 0.8 * unit);
    const mandibleOutline = closed(tract.mandible);
    mandible.update(mandibleOutline);
    mandibleLine.update(mandibleOutline, 0.8 * unit);
    const lowerTooth = lowerToothParts(tract.lowerTeeth);
    lowerRoot.update(lowerTooth.outline);
    lowerRootLine.update(lowerTooth.outline, 0.8 * unit);
    lowerCrown.update(lowerTooth.crown);
    lowerCrownLine.update(lowerTooth.crown, 1 * unit);

    epiglottis.update(tract.epiglottis, seamExposure(tract.epiglottis, [52.0, 12.8], 1.2, epiglottisExposed));
    epiglottisLine.update(tract.epiglottis, 1.1 * unit, epiglottisExposed);

    const velumSeam: Vec2 = [53.6, 48.0];
    velum.update(tract.velum, seamExposure(tract.velum, velumSeam, 1.6, velumExposed));
    velumLine.update(tract.velum, 1.5 * unit, velumExposed);
    velumShadow.update(tract.velum, [0.45, -0.7], 1.8, 0.2);

    tongue.update(tract.tongue, tongueExposed);
    tongueLine.update(tract.tongue, 1.7 * unit, tongueWidths);
    tongueShadow.update(tract.tongue, [0.5, -0.8], 2.4, 0.3);

    upperLip.update(tract.upperLip, lipExposed);
    lowerLip.update(tract.lowerLip, lipExposed);
    upperVermilion.update(tract.upperVermilion, vermilionExposed);
    lowerVermilion.update(tract.lowerVermilion, vermilionExposed);
    upperLipLine.update(tract.upperLip.slice(0, LIP_EXPOSED_POINTS), 1.5 * unit);
    lowerLipLine.update(tract.lowerLip.slice(0, LIP_EXPOSED_POINTS), 1.5 * unit);

    // The tightest point of the tract, shown once it is tight enough to be the
    // thing you are hearing; for the tongue, the stretch of roof it meets is
    // lit as well — that stretch is the place of articulation.
    const gap = tract.constriction.gap;
    const tight = gap < 5 ? 1 - gap / 5 : 0;
    const [cx, cy] = tract.constriction.point;
    glow.visible = tight > 0.05;
    ring.visible = glow.visible;
    glow.position.set(cx, cy, 0);
    ring.position.set(cx, cy, 0);
    const scale = 0.6 + tight * 0.45;
    glow.scale.set(scale, scale, 1);
    ring.scale.set(scale, scale, 1);
    halo.opacity = 0.06 + tight * 0.16;
    accent.opacity = 0.3 + tight * 0.6;
    placeLine.mesh.visible = tract.constriction.where === "tongue" && tight > 0.3;
    if (placeLine.mesh.visible) {
      placeLine.update(tract.place, 3.2 * unit);
      placeMat.opacity = 0.2 + 0.6 * tight;
    }

    // A stop is only audible when it opens: mark the release where it happened.
    const manner = frame.manner;
    const wasStop =
      previous?.manner === "stop" || previous?.manner === "affricate" || previous?.manner === "tap";
    if (previous && wasStop && previous.gap < 0.5 && gap > 1.4) {
      burstAt = frame.time;
      burstWhere = previous.at;
    }
    const since = frame.time - burstAt;
    burst.visible = burstAt > 0 && since >= 0 && since < BURST_SECONDS;
    if (burst.visible) {
      const progress = since / BURST_SECONDS;
      const grow = frame.still ? 2.2 : 1 + progress * 4;
      burst.position.set(burstWhere[0], burstWhere[1], 0);
      burst.scale.set(grow, grow, 1);
      burstMat.opacity = 0.8 * (1 - progress);
    }
    previous = { gap, manner, at: [cx, cy] };

    // Voicing: ripples off the vocal folds while there is voice, gone without.
    const voice = frame.pose.voice;
    ripples.forEach((mesh, index) => {
      mesh.visible = voice > 0.25;
      if (!mesh.visible) return;
      const phase = frame.active && !frame.still ? (frame.time * 3.2 + index * 0.5) % 1 : 0.35 + index * 0.4;
      const grow = 1 + phase * 1.6;
      mesh.scale.set(grow, grow, 1);
      (mesh.material as ThreeJS.MeshBasicMaterial).opacity = voice * 0.75 * (1 - phase * 0.8);
    });

    updateFlow(frame, tract);
    renderer.render(scene, camera);
  }

  function setPalette(next: Palette): void {
    for (const [made, look] of looks) {
      const value = look(next);
      const u = made.uniforms;
      u.uColor.value.set(value.color);
      // Highlights go towards white and shading towards the deep tone (or
      // the outline colour), gently in dark mode where everything is close.
      u.uHigh.value.set(mix(value.color, "#ffffff", next.light ? 0.55 : 0.32));
      u.uShade.value.set(value.shade ?? mix(value.color, next.light ? "#3a2f28" : "#000000", next.light ? 0.4 : 0.55));
      u.uRelief.value = value.relief;
      u.uGloss.value = value.gloss ?? 0;
      u.uPattern.value = value.pattern ?? 0;
      u.uPatternAmount.value = value.patternAmount ?? 0;
      u.uPatternColor.value.set(value.patternColor ?? value.color);
    }
    cavity.uniforms.uColor.value.set(next.cavity);
    cavity.uniforms.uDeep.value.set(next.cavityDeep);
    shadowMat.uniforms.uColor.value.set("#000000");
    shadowMat.uniforms.uOpacity.value = next.light ? 0.16 : 0.42;
    placeMat.color.set(next.accent);
    for (const material of Object.values(lines)) material.color.set(next.line);
    accent.color.set(next.accent);
    halo.color.set(next.accent);
    burstMat.color.set(next.accent);
    for (const mesh of ripples) (mesh.material as ThreeJS.MeshBasicMaterial).color.set(next.accent);
    flowMaterial.color.set(next.flow);
  }

  setPalette(palette);

  return {
    render,
    setSize,
    setPalette,
    dispose() {
      for (const part of [...solids, ...moving]) part.dispose();
      for (const { stroke } of statics) stroke.dispose();
      for (const line of movingLines) line.dispose();
      for (const feather of [tongueShadow, velumShadow]) feather.dispose();
      placeLine.dispose();
      for (const mesh of [glow, ring, burst, ...ripples]) {
        mesh.geometry.dispose();
      }
      for (const mesh of ripples) (mesh.material as ThreeJS.Material).dispose();
      flowGeometry.dispose();
      texture.dispose();
      for (const made of looks.keys()) made.dispose();
      for (const made of [cavity, shadowMat, placeMat, accent, halo, burstMat, rippleMat, flowMaterial]) {
        made.dispose();
      }
      for (const made of Object.values(lines)) made.dispose();
      renderer.dispose();
    },
  };
}
