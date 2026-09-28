#!/usr/bin/env python3
"""
Checks layer dumps written by LayerCastComponentTest (or any dump that contains _frame.png).

For every dump directory it stacks the split-out layers (straight alpha) over game.png, the opaque game layer, and
compares the result with _frame.png, the finished game frame of the same frame. A GUI element that is missing from
every layer shows up as mismatching pixels ("missing"); mismatches under split-out content are reported separately
("order-limited": OBS stacks whole layers, so a split-out part that the game drew below some unsplit element ends up
above it). Elements cannot be duplicated: the mod routes every element to exactly one layer.

Name tags are part of the world, so the stacking check alone cannot tell whether they were really taken out of the
game layer. For a scene with a "nametags" dump and a "_hidden" dump (the same scene with the names hidden), the
game layer of the "nametags" dump must match the "_hidden" frame around the name tags ("left in game").

  check_layers.py DUMP_ROOT [--sheet OUT.png]

Needs Pillow and numpy.
"""
import argparse
import os
import pathlib
import sys

import numpy as np
from PIL import Image

# Approximate vanilla drawing order, used when several split layers overlap.
ORDER = ["nametags", "camera", "crosshair", "hotbar", "effects", "bossbar", "scoreboard", "actionbar", "title", "chat",
         "tablist", "screen", "toasts", "debug", "subtitles"]
THRESHOLD = 32  # per channel; rounding differences between one-pass and composited blending stay far below


def load(path):
    return np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32) / 255.0


def over(dst_rgb, layer):
    a = layer[..., 3:4]
    return layer[..., :3] * a + dst_rgb * (1.0 - a)


def order_key(name):
    base = name[:-4]
    if base.startswith("mod."):
        return (1, 0, base)
    return (0, ORDER.index(base) if base in ORDER else len(ORDER), base)


def check(directory, crosshair_box):
    frame = load(directory / "_frame.png")[..., :3]
    # The game layer (opaque: the picture minus everything split out) is the bottom source in OBS; the split-out
    # layers are stacked over it.
    composite = load(directory / "game.png")[..., :3]
    names = sorted([f for f in os.listdir(directory) if f.endswith(".png") and f not in ("_frame.png", "game.png")],
                   key=order_key)
    layers = {n[:-4]: load(directory / n) for n in names}
    for n in names:
        composite = over(composite, layers[n[:-4]])
    bad = np.abs(composite - frame).max(axis=2) * 255 > THRESHOLD
    h, w = bad.shape
    if crosshair_box:
        # The vanilla crosshair inverts what is below it; a separate layer cannot reproduce that.
        cx, cy, r = w // 2, h // 2, max(12, h // 24)  # grows with the GUI scale of larger windows
        bad[cy - r:cy + r, cx - r:cx + r] = False
    # A split-out layer always lies above the game layer in OBS, while in the game some unsplit element may have been
    # drawn over it: mismatches under split-out content are such ordering limits, anything else is missing content.
    covered = np.zeros((h, w), dtype=bool)
    for v in layers.values():
        covered |= v[..., 3] > 0.02
    order_limited = bad & covered
    missing = bad & ~covered
    result = {"mismatch": int(missing.sum()), "order_limited": int(order_limited.sum())}
    if missing.any():
        ys, xs = np.nonzero(missing)
        result["mismatch_box"] = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
    result["empty"] = sorted(k for k, v in layers.items() if not (v[..., 3] > 0).any())
    return result, missing


def dilate(mask, radius):
    out = mask.copy()
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            out |= np.roll(np.roll(mask, dy, axis=0), dx, axis=1)
    return out


def check_name_tags_removed(scene):
    """With the name tags split out, the game layer must look like the frame with the names hidden."""
    split, hidden, plain = scene / "nametags", scene / "_hidden", scene / "none"
    tags = load(split / "nametags.png")[..., 3] > 0
    region = dilate(tags, 2)
    game = load(split / "game.png")[..., :3]
    reference = load(hidden / "_frame.png")[..., :3]
    left = (np.abs(game - reference).max(axis=2) * 255 > THRESHOLD) & region
    # The check means nothing if the names were not visible in the first place.
    shown = (np.abs(load(plain / "_frame.png")[..., :3] - reference).max(axis=2) * 255 > THRESHOLD) & region
    return int(tags.sum()), int(shown.sum()), int(left.sum())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("root", type=pathlib.Path)
    p.add_argument("--sheet", type=pathlib.Path, help="write an image marking mismatching pixels of failed dumps")
    args = p.parse_args()
    failures = 0
    marked = []
    for frame in sorted(args.root.rglob("_frame.png")):
        directory = frame.parent
        label = str(directory.relative_to(args.root))
        split = directory.name
        result, bad = check(directory, crosshair_box=True)
        ok = result["mismatch"] == 0
        failures += 0 if ok else 1
        extra = ""
        if split not in ("none", "all") and split in result["empty"]:
            extra = "  (split layer empty: part not on screen)"
        print(f"{'ok  ' if ok else 'FAIL'} {label:32s} missing={result['mismatch']:6d} order-limited={result['order_limited']:5d}"
              f"{' box=' + str(result.get('mismatch_box')) if result['mismatch'] else ''}{extra}")
        if not ok and args.sheet:
            img = Image.open(frame).convert("RGB")
            red = Image.new("RGB", img.size, (255, 0, 0))
            mask = Image.fromarray((bad * 255).astype(np.uint8))
            img.paste(red, (0, 0), mask)
            marked.append((label, img))
    if args.sheet and marked:
        w, h = marked[0][1].size
        sheet = Image.new("RGB", (w * 2, h * ((len(marked) + 1) // 2)))
        for i, (_, img) in enumerate(marked):
            sheet.paste(img, ((i % 2) * w, (i // 2) * h))
        sheet.save(args.sheet)
    for hidden in sorted(args.root.rglob("_hidden")):
        scene = hidden.parent
        if not (scene / "nametags" / "nametags.png").exists():
            continue
        tag_pixels, shown, left = check_name_tags_removed(scene)
        ok = tag_pixels > 0 and shown > 0 and left == 0
        failures += 0 if ok else 1
        label = str(scene.relative_to(args.root)) + " (name tags out of game)"
        print(f"{'ok  ' if ok else 'FAIL'} {label:32s} tag-pixels={tag_pixels:6d} shown={shown:6d} left-in-game={left:5d}")
    print(f"{failures} failing dump(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
