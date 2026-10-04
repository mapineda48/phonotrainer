/** The three.js side: buffers, camera and draw order. No phonetics here.
 *
 *  A midsagittal section is a flat thing and it is drawn flat; what three.js
 *  contributes is the scene graph, geometry that lives on the GPU and a few
 *  hundred coordinates rewritten per frame without allocating, which is what
 *  lets the tongue move at 60 fps beside a running audio player. Every mesh
 *  has a topology decided once: only positions change.
 *
 *  Everything that decides *what shape* to draw is in `tract.ts`, which is
 *  pure and tested. This file only knows how to put it on screen.
 */

import {
  EYE,
  GLOTTIS,
  LIP_LINE_Y,
  LOWER_FACE,
  MANDIBLE_SECTION,
  NASAL_CAVITY,
  VIEW_MOUTH,
  UPPER_FACE,
  UPPER_TEETH,
  type Vec2,
  type ViewBox,
} from "./anatomy";
import type { Palette } from "./palette";
import type { Manner } from "./phones";
import type { Pose } from "./pose";
import {
  LOWER_LIP_POINTS,
  TONGUE_SURFACE_POINTS,
  UPPER_LIP_POINTS,
  VELUM_POINTS,
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
}

export interface TractView {
  render(frame: Frame): void;
  setPalette(palette: Palette): void;
  setSize(width: number, height: number): void;
  dispose(): void;
}

const PARTICLES = 34;
/** Below this gap (≈ 1 mm) the tract counts as closed: no air comes through. */
const CLOSED = 0.35;
/** How long the puff of a released stop stays on screen. */
const BURST_SECONDS = 0.16;

/** Centre line of the nasal cavity: the road the air takes for /m/, /n/, /ŋ/. */
const NASAL_PATH: Vec2[] = (() => {
  const half = NASAL_CAVITY.length / 2;
  const out: Vec2[] = [];
  for (let i = 0; i < half; i += 1) {
    const top = NASAL_CAVITY[i];
    const bottom = NASAL_CAVITY[NASAL_CAVITY.length - 1 - i];
    out.push([(top[0] + bottom[0]) / 2, (top[1] + bottom[1]) / 2]);
  }
  return out;
})();

function pointOnPath(path: readonly Vec2[], t: number): Vec2 {
  const position = Math.min(Math.max(t, 0), 1) * (path.length - 1);
  const index = Math.min(Math.floor(position), path.length - 2);
  const local = position - index;
  const a = path[index];
  const b = path[index + 1];
  return [a[0] + (b[0] - a[0]) * local, a[1] + (b[1] - a[1]) * local];
}

/** Repeats the first point at the end, so a closed outline can be stroked as
 *  an open polyline without leaving a gap at the seam. */
const closedOutline = (points: readonly Vec2[]): Vec2[] => [...points, points[0]];

/**
 * Offsets a polyline to either side so it can be drawn as a line with real
 * thickness: WebGL cannot widen a line primitive, and this is the tongue's own
 * contour, the one edge that has to stay crisp.
 */
function offsetLine(points: readonly Vec2[], width: number): { top: Vec2[]; bottom: Vec2[] } {
  const top: Vec2[] = [];
  const bottom: Vec2[] = [];
  for (let i = 0; i < points.length; i += 1) {
    const previous = points[Math.max(i - 1, 0)];
    const next = points[Math.min(i + 1, points.length - 1)];
    const dx = next[0] - previous[0];
    const dy = next[1] - previous[1];
    const length = Math.hypot(dx, dy) || 1;
    const nx = (-dy / length) * width;
    const ny = (dx / length) * width;
    top.push([points[i][0] + nx, points[i][1] + ny]);
    bottom.push([points[i][0] - nx, points[i][1] - ny]);
  }
  return { top, bottom };
}

/** A polygon whose shape changes but whose point count does not: triangulated
 *  once as a fan from its centroid, then only the coordinates are rewritten. */
class FanMesh {
  readonly mesh: ThreeJS.Mesh;
  private readonly positions: Float32Array;
  private readonly geometry: ThreeJS.BufferGeometry;

  constructor(THREE: Three, count: number, material: ThreeJS.Material, order: number) {
    this.positions = new Float32Array((count + 1) * 3);
    this.geometry = new THREE.BufferGeometry();
    this.geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3));
    const index: number[] = [];
    for (let i = 0; i < count; i += 1) index.push(0, i + 1, ((i + 1) % count) + 1);
    this.geometry.setIndex(index);
    this.mesh = new THREE.Mesh(this.geometry, material);
    this.mesh.renderOrder = order;
    this.mesh.frustumCulled = false;
  }

  update(points: readonly Vec2[]): void {
    let cx = 0;
    let cy = 0;
    for (const [x, y] of points) {
      cx += x;
      cy += y;
    }
    this.positions[0] = cx / points.length;
    this.positions[1] = cy / points.length;
    for (let i = 0; i < points.length; i += 1) {
      this.positions[(i + 1) * 3] = points[i][0];
      this.positions[(i + 1) * 3 + 1] = points[i][1];
    }
    this.geometry.attributes.position.needsUpdate = true;
  }

  dispose(): void {
    this.geometry.dispose();
  }
}

/** Two rows of points stitched into one strip: the tongue between its surface
 *  and its underside, and any stroke between its two offsets. */
class StripMesh {
  readonly mesh: ThreeJS.Mesh;
  private readonly positions: Float32Array;
  private readonly geometry: ThreeJS.BufferGeometry;

  constructor(
    THREE: Three,
    count: number,
    material: ThreeJS.Material,
    order: number,
    colors?: [string, string],
  ) {
    this.positions = new Float32Array(count * 2 * 3);
    this.geometry = new THREE.BufferGeometry();
    this.geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3));
    if (colors) {
      this.geometry.setAttribute(
        "color",
        new THREE.BufferAttribute(new Float32Array(count * 2 * 3), 3),
      );
      this.recolor(THREE, colors[0], colors[1]);
    }
    const index: number[] = [];
    for (let i = 0; i < count - 1; i += 1) {
      index.push(i, count + i, i + 1, i + 1, count + i, count + i + 1);
    }
    this.geometry.setIndex(index);
    this.mesh = new THREE.Mesh(this.geometry, material);
    this.mesh.renderOrder = order;
    this.mesh.frustumCulled = false;
  }

  /** `top` and `bottom` must both be as long as the strip. */
  update(top: readonly Vec2[], bottom: readonly Vec2[]): void {
    const count = top.length;
    for (let i = 0; i < count; i += 1) {
      this.positions[i * 3] = top[i][0];
      this.positions[i * 3 + 1] = top[i][1];
      this.positions[(count + i) * 3] = bottom[i][0];
      this.positions[(count + i) * 3 + 1] = bottom[i][1];
    }
    this.geometry.attributes.position.needsUpdate = true;
  }

  /** Recolour in place: the theme can change while the page is open. */
  recolor(THREE: Three, top: string, bottom: string): void {
    const attribute = this.geometry.attributes.color;
    if (!attribute) return;
    const count = attribute.count / 2;
    const a = new THREE.Color(top);
    const b = new THREE.Color(bottom);
    for (let i = 0; i < count; i += 1) {
      attribute.array.set([a.r, a.g, a.b], i * 3);
      attribute.array.set([b.r, b.g, b.b], (count + i) * 3);
    }
    attribute.needsUpdate = true;
  }

  dispose(): void {
    this.geometry.dispose();
  }
}

/** Round sprite for the airflow, painted once into a texture. */
function flowTexture(THREE: Three): ThreeJS.CanvasTexture {
  const canvas = document.createElement("canvas");
  canvas.width = 64;
  canvas.height = 64;
  const context = canvas.getContext("2d");
  if (context) {
    const gradient = context.createRadialGradient(32, 32, 0, 32, 32, 32);
    gradient.addColorStop(0, "rgba(255,255,255,1)");
    gradient.addColorStop(0.5, "rgba(255,255,255,0.5)");
    gradient.addColorStop(1, "rgba(255,255,255,0)");
    context.fillStyle = gradient;
    context.fillRect(0, 0, 64, 64);
  }
  return new THREE.CanvasTexture(canvas);
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

  // Flat colour and no depth test: with an orthographic section, draw order is
  // the only layering that matters, and renderOrder states it outright.
  const flat = (color: string, opacity = 1): ThreeJS.MeshBasicMaterial =>
    new THREE.MeshBasicMaterial({
      color,
      transparent: opacity < 1,
      opacity,
      depthTest: false,
      depthWrite: false,
      // Outlines here are authored for reading, not for winding order: a lip
      // listed clockwise would be back-facing and silently vanish.
      side: THREE.DoubleSide,
    });

  const materials = {
    tissue: flat(palette.tissue),
    bone: flat(palette.bone),
    lip: flat(palette.lip),
    line: flat(palette.line, 0.5),
    accent: flat(palette.accent, 0.85),
    glow: flat(palette.accent, 0.16),
  };
  const tongueMaterial = new THREE.MeshBasicMaterial({
    vertexColors: true,
    depthTest: false,
    depthWrite: false,
    side: THREE.DoubleSide,
  });

  const shapeOf = (
    points: readonly Vec2[],
    holes: readonly (readonly Vec2[])[] = [],
  ): ThreeJS.ShapeGeometry => {
    const shape = new THREE.Shape(points.map(([x, y]) => new THREE.Vector2(x, y)));
    for (const hole of holes) {
      shape.holes.push(new THREE.Path(hole.map(([x, y]) => new THREE.Vector2(x, y))));
    }
    return new THREE.ShapeGeometry(shape);
  };

  const add = (
    geometry: ThreeJS.BufferGeometry,
    material: ThreeJS.Material,
    order: number,
  ): ThreeJS.Mesh => {
    const mesh = new THREE.Mesh(geometry, material);
    mesh.renderOrder = order;
    mesh.frustumCulled = false;
    scene.add(mesh);
    return mesh;
  };

  // --- anatomy that never moves -------------------------------------------
  const upperFace = add(shapeOf(UPPER_FACE, [NASAL_CAVITY]), materials.tissue, 0);
  const upperTeeth = add(shapeOf(UPPER_TEETH), materials.bone, 6);
  const eye = add(shapeOf(EYE), materials.line, 3);
  // One stroke does both jobs: it is the roof of the tract where it runs over
  // the palate, and the profile of the face everywhere else.
  const profile = closedOutline(UPPER_FACE);
  const outline = new StripMesh(THREE, profile.length, materials.line, 5);
  const outlineEdge = offsetLine(profile, 0.4);
  outline.update(outlineEdge.top, outlineEdge.bottom);
  scene.add(outline.mesh);

  // --- everything that moves ----------------------------------------------
  const jaw = new FanMesh(THREE, LOWER_FACE.length, materials.tissue, 1);
  const jawEdge = new StripMesh(THREE, LOWER_FACE.length + 1, materials.line, 3);
  const mandible = new FanMesh(THREE, MANDIBLE_SECTION.length, materials.bone, 2);
  const velum = new FanMesh(THREE, VELUM_POINTS, materials.tissue, 4);
  const velumEdge = new StripMesh(THREE, VELUM_POINTS + 1, materials.line, 4);
  const tongue = new StripMesh(THREE, TONGUE_SURFACE_POINTS, tongueMaterial, 7, [
    palette.tongue,
    palette.tongueDeep,
  ]);
  const tongueEdge = new StripMesh(THREE, TONGUE_SURFACE_POINTS, materials.line, 8);
  const lowerTeeth = new FanMesh(THREE, 5, materials.bone, 9);
  const upperLip = new FanMesh(THREE, UPPER_LIP_POINTS, materials.lip, 10);
  const lowerLip = new FanMesh(THREE, LOWER_LIP_POINTS, materials.lip, 10);
  // One strip per lip: stitched into a single one, the jump from the end of
  // the upper lip to the start of the lower drew a band across the mouth.
  const upperLipEdge = new StripMesh(THREE, UPPER_LIP_POINTS + 1, materials.line, 11);
  const lowerLipEdge = new StripMesh(THREE, LOWER_LIP_POINTS + 1, materials.line, 11);
  const moving = [
    jaw, jawEdge, mandible, velum, velumEdge, tongue, tongueEdge, lowerTeeth,
    upperLip, lowerLip, upperLipEdge, lowerLipEdge,
  ];
  for (const part of moving) scene.add(part.mesh);

  // --- what the phone is doing, made visible -------------------------------
  const glow = add(new THREE.CircleGeometry(2.8, 24), materials.glow, 11);
  const ring = add(new THREE.RingGeometry(2.4, 3, 24), materials.accent, 12);
  const burst = add(new THREE.RingGeometry(0.9, 1.3, 24), materials.accent, 13);
  const voicing = add(new THREE.CircleGeometry(1.9, 20), materials.accent, 13);
  voicing.position.set(GLOTTIS[0], GLOTTIS[1], 0);

  const texture = flowTexture(THREE);
  const flowPositions = new Float32Array(PARTICLES * 3);
  const flowGeometry = new THREE.BufferGeometry();
  flowGeometry.setAttribute("position", new THREE.BufferAttribute(flowPositions, 3));
  const flowMaterial = new THREE.PointsMaterial({
    color: palette.flow,
    size: 2.4,
    map: texture,
    transparent: true,
    opacity: 0.7,
    depthTest: false,
    depthWrite: false,
  });
  const flow = new THREE.Points(flowGeometry, flowMaterial);
  flow.renderOrder = 14;
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
  };
  setSize(300, 240);

  /** Where the air goes, and how hard, for this manner. */
  function updateFlow(frame: Frame, tract: TractShapes): void {
    const manner = frame.manner;
    const nasal = frame.pose.velum > 0.5;
    const closed = tract.constriction.gap < CLOSED;
    const noisy = manner === "fricative" || manner === "affricate" || manner === "glottal";
    flow.visible = frame.active && frame.pose.voice + (noisy ? 1 : 0) > 0 && !(closed && !nasal);
    if (!flow.visible) return;

    const exit: Vec2 = [95, LIP_LINE_Y - frame.pose.jaw * 2.2];
    const from: Vec2 = noisy ? tract.constriction.point : [GLOTTIS[0], GLOTTIS[1] + 5];
    const speed = noisy ? 0.55 : 0.3;
    const jitter = noisy ? 2.2 : 0.6;

    for (let i = 0; i < PARTICLES; i += 1) {
      const seed = seeds[i];
      const t = (frame.time * speed * seed.speed + seed.phase) % 1;
      if (nasal) {
        const point = pointOnPath(NASAL_PATH, t);
        flowPositions[i * 3] = point[0];
        flowPositions[i * 3 + 1] = point[1] + seed.lane * 1.6;
        continue;
      }
      flowPositions[i * 3] = from[0] + (exit[0] - from[0]) * t;
      flowPositions[i * 3 + 1] =
        from[1] +
        (exit[1] - from[1]) * t +
        seed.lane * (1.4 + jitter * t) +
        (noisy ? Math.sin((frame.time * 9 + seed.phase * 11) * Math.PI) * jitter * t : 0);
    }
    flowGeometry.attributes.position.needsUpdate = true;
    flowMaterial.opacity = noisy ? 0.8 : 0.4;
  }

  function render(frame: Frame): void {
    const tract = buildTract(frame.pose);

    jaw.update(tract.jawTissue);
    const jawOutline = offsetLine(closedOutline(tract.jawTissue), 0.4);
    jawEdge.update(jawOutline.top, jawOutline.bottom);
    mandible.update(tract.mandible);
    velum.update(tract.velum);
    const velumOutline = offsetLine(closedOutline(tract.velum), 0.35);
    velumEdge.update(velumOutline.top, velumOutline.bottom);
    // The underside runs tip → root and the surface root → tip: reversed, the
    // two rows line up and the strip has no twist in it.
    tongue.update(tract.tongueSurface, tract.tongueUnderside.slice().reverse());
    const edge = offsetLine(tract.tongueSurface, 0.4);
    tongueEdge.update(edge.top, edge.bottom);
    lowerTeeth.update(tract.lowerTeeth);
    upperLip.update(tract.upperLip);
    lowerLip.update(tract.lowerLip);
    // The free edges are what tell a closed mouth from a rounded one, so they
    // are stroked to stay crisp at panel size.
    const upperOutline = offsetLine(closedOutline(tract.upperLip), 0.35);
    upperLipEdge.update(upperOutline.top, upperOutline.bottom);
    const lowerOutline = offsetLine(closedOutline(tract.lowerLip), 0.35);
    lowerLipEdge.update(lowerOutline.top, lowerOutline.bottom);

    // The tightest point of the tract, shown once it is tight enough to be the
    // thing you are hearing.
    const gap = tract.constriction.gap;
    const tight = gap < 5 ? 1 - gap / 5 : 0;
    const [cx, cy] = tract.constriction.point;
    glow.visible = tight > 0.05;
    ring.visible = glow.visible;
    glow.position.set(cx, cy, 0);
    ring.position.set(cx, cy, 0);
    const scale = 0.55 + tight * 0.55;
    glow.scale.set(scale, scale, 1);
    ring.scale.set(scale, scale, 1);
    materials.glow.opacity = 0.05 + tight * 0.15;
    materials.accent.opacity = 0.25 + tight * 0.6;

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
      const grow = 1 + (since / BURST_SECONDS) * 4;
      burst.position.set(burstWhere[0], burstWhere[1], 0);
      burst.scale.set(grow, grow, 1);
    }
    previous = { gap, manner, at: [cx, cy] };

    // Voicing: the folds beat while there is voice and stop dead without it.
    const voice = frame.pose.voice;
    voicing.visible = voice > 0.25;
    if (voicing.visible) {
      const beat = frame.active ? 0.75 + 0.3 * Math.sin(frame.time * 2 * Math.PI * 7) : 1;
      voicing.scale.set(beat * voice, beat * voice, 1);
    }

    updateFlow(frame, tract);
    renderer.render(scene, camera);
  }

  return {
    render,
    setSize,
    setPalette(next: Palette) {
      materials.tissue.color.set(next.tissue);
      materials.bone.color.set(next.bone);
      materials.lip.color.set(next.lip);
      materials.line.color.set(next.line);
      materials.accent.color.set(next.accent);
      materials.glow.color.set(next.accent);
      flowMaterial.color.set(next.flow);
      tongue.recolor(THREE, next.tongue, next.tongueDeep);
    },
    dispose() {
      for (const part of [...moving, outline]) part.dispose();
      for (const mesh of [upperFace, upperTeeth, eye, glow, ring, burst, voicing]) {
        mesh.geometry.dispose();
      }
      flowGeometry.dispose();
      texture.dispose();
      for (const material of Object.values(materials)) material.dispose();
      tongueMaterial.dispose();
      flowMaterial.dispose();
      renderer.dispose();
    },
  };
}
