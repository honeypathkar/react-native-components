#!/usr/bin/env node
/**
 * Turn a Lucide icon into an Android vector drawable.
 *
 *   npx lucide-to-drawable bike --out android/app/src/main/res/drawable/ic_bike.xml
 *
 * Why this exists: a notification is drawn by SystemUI in a different process,
 * which can only be handed a resource, a bitmap or a URI — never a React
 * component. So `<Bike />` cannot be passed to a live update directly. But the
 * *geometry* behind it can: lucide-react-native stores each icon as plain SVG
 * primitives, and Android's vector drawable is the same idea in a different
 * syntax. This reads the former and writes the latter, so the icon on the
 * status-bar chip is the same one your screens draw.
 *
 * Lucide is stroke-based — no fills, 2px strokes, round caps — and vector
 * drawables support all of that, so the result is the icon rather than an
 * approximation of it.
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const require = createRequire(import.meta.url);

function usage(message) {
  if (message) console.error(`error: ${message}\n`);
  console.error(
    `usage: lucide-to-drawable <icon-name> [--out <path>] [--color <#RRGGBB>] [--stroke <width>]

  <icon-name>   kebab-case, as Lucide names it: bike, package, store, map-pin
  --out         file to write (default: stdout)
  --color       stroke colour (default: #FFFFFF)
  --stroke      stroke width in viewport units (default: Lucide's own, 2)

example:
  npx lucide-to-drawable bike --out android/app/src/main/res/drawable/ic_track_bike.xml`,
  );
  process.exit(message ? 1 : 0);
}

const args = process.argv.slice(2);
if (!args.length || args.includes('--help')) usage();

const name = args[0];
const flag = (key, fallback) => {
  const i = args.indexOf(key);
  return i === -1 ? fallback : args[i + 1];
};
const out = flag('--out');
const color = flag('--color', '#FFFFFF');
const strokeWidth = flag('--stroke', '2');

// ── Find the icon ───────────────────────────────────────────────────────────
// Resolved from the consumer's node_modules, not this package's: the icon set
// belongs to the app, and pinning a copy here would drift from the version the
// app's screens actually render.
function resolveIcon() {
  // Walked by hand rather than via require.resolve: Lucide declares an
  // `exports` map, and an exports map makes every subpath it does not list —
  // including ./package.json — unresolvable. The icon files are subpaths.
  const roots = [];
  for (let dir = process.cwd(); ; dir = dirname(dir)) {
    roots.push(join(dir, 'node_modules'));
    if (dir === dirname(dir)) break;
  }

  for (const pkg of ['lucide-react-native', 'lucide-react', 'lucide', 'lucide-static']) {
    for (const modules of roots) {
      const root = join(modules, pkg);
      if (!existsSync(root)) continue;
      for (const candidate of [
        join(root, 'dist/esm/icons', `${name}.js`),
        join(root, 'dist/cjs/icons', `${name}.js`),
        join(root, 'icons', `${name}.svg`),
      ]) {
        if (existsSync(candidate)) return candidate;
      }
    }
  }
  return null;
}

const file = resolveIcon();
if (!file) {
  usage(
    `icon "${name}" not found. Install lucide-react-native, and check the name at lucide.dev/icons`,
  );
}

// ── Read the primitives ─────────────────────────────────────────────────────
/**
 * Lucide's module is a plain array literal of `[tag, attrs]` pairs. Extracting
 * it textually rather than importing the module is deliberate: importing calls
 * `createLucideIcon`, which hands back a React component and throws the
 * geometry away — and would need a React runtime here to do it.
 */
function primitives(source) {
  const start = source.indexOf('[', source.indexOf('createLucideIcon'));
  const end = source.lastIndexOf(']);');
  if (start === -1 || end === -1) usage(`could not read icon data from ${file}`);

  const literal = source
    .slice(start, end + 1)
    // Object keys are bare identifiers in JS and must be quoted in JSON.
    .replace(/([{,]\s*)([A-Za-z_][\w-]*)\s*:/g, '$1"$2":')
    .replace(/,(\s*[}\]])/g, '$1');

  try {
    return JSON.parse(literal);
  } catch (e) {
    usage(`could not parse icon data from ${file}: ${e.message}`);
  }
}

// ── SVG primitives → path data ──────────────────────────────────────────────
// A vector drawable has exactly one geometry primitive, `pathData`, so every
// circle, line and rect has to be expressed as a path.
const n = (v) => Number(v ?? 0);

/** Two half-arcs, because a single arc command cannot close a full circle. */
const circle = ({ cx, cy, r }) =>
  `M${n(cx) - n(r)},${n(cy)}a${n(r)},${n(r)} 0 1,0 ${n(r) * 2},0a${n(r)},${n(r)} 0 1,0 ${-n(r) * 2},0Z`;

const ellipse = ({ cx, cy, rx, ry }) =>
  `M${n(cx) - n(rx)},${n(cy)}a${n(rx)},${n(ry)} 0 1,0 ${n(rx) * 2},0a${n(rx)},${n(ry)} 0 1,0 ${-n(rx) * 2},0Z`;

const line = ({ x1, y1, x2, y2 }) => `M${n(x1)},${n(y1)}L${n(x2)},${n(y2)}`;

const points = (value, close) => {
  const nums = String(value).trim().split(/[\s,]+/).map(Number);
  const pairs = [];
  for (let i = 0; i + 1 < nums.length; i += 2) pairs.push(`${nums[i]},${nums[i + 1]}`);
  return `M${pairs.join('L')}${close ? 'Z' : ''}`;
};

const rect = ({ x, y, width, height, rx, ry }) => {
  const [w, h] = [n(width), n(height)];
  const radius = Math.min(n(rx ?? ry), w / 2, h / 2);
  if (!radius) return `M${n(x)},${n(y)}h${w}v${h}h${-w}Z`;
  const [left, top] = [n(x), n(y)];
  return (
    `M${left + radius},${top}` +
    `h${w - radius * 2}a${radius},${radius} 0 0,1 ${radius},${radius}` +
    `v${h - radius * 2}a${radius},${radius} 0 0,1 ${-radius},${radius}` +
    `h${-(w - radius * 2)}a${radius},${radius} 0 0,1 ${-radius},${-radius}` +
    `v${-(h - radius * 2)}a${radius},${radius} 0 0,1 ${radius},${-radius}Z`
  );
};

function toPath([tag, attrs]) {
  switch (tag) {
    case 'path': return attrs.d;
    case 'circle': return circle(attrs);
    case 'ellipse': return ellipse(attrs);
    case 'line': return line(attrs);
    case 'polyline': return points(attrs.points, false);
    case 'polygon': return points(attrs.points, true);
    case 'rect': return rect(attrs);
    default:
      console.error(`warning: skipping unsupported <${tag}>`);
      return null;
  }
}

const source = readFileSync(file, 'utf8');
const paths = primitives(source).map(toPath).filter(Boolean);
if (!paths.length) usage(`icon "${name}" produced no drawable geometry`);

// The header is a comment, so it must not contain a double hyphen anywhere:
// XML forbids "--" inside comments, and the flag this tool is invoked with
// starts with one. Spelling the command out with the flag would produce a file
// that cannot be parsed — which the resource compiler catches, but only after
// the icon has been generated and committed.
const xml = `<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated from the Lucide icon "${name}" by lucide-to-drawable.
  Edits here are lost on the next run; change the icon and regenerate instead:
  npx lucide-to-drawable ${name}
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
${paths
  .map(
    (d) => `    <path
        android:pathData="${d}"
        android:strokeColor="${color}"
        android:strokeWidth="${strokeWidth}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />`,
  )
  .join('\n')}
</vector>
`;

if (out) {
  writeFileSync(out, xml);
  console.error(`wrote ${out} (${paths.length} path${paths.length === 1 ? '' : 's'})`);
} else {
  process.stdout.write(xml);
}
