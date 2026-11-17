#!/usr/bin/env python3
"""Build adblock-rust `resources.json` from uBlock Origin's scriptlet sources.

uBlock Origin's `+js(...)` filters (the ones that strip YouTube video ads) call scriptlets
defined in `src/js/resources/*.js` of https://github.com/gorhill/uBlock. adblock-rust needs
them as a JSON array of resources. This converts them without running any JavaScript.

Usage:
    scripts/gen_adblock_resources.py <source dir> <output.json>

<source dir> must hold a flat copy of uBlock's `src/js/resources/*.js` plus
`src/js/jsonpath.js`, `src/js/arglist-parser.js` and `src/js/urlskip.js`.

It relies on uBlock Origin's source layout:
  - every scriptlet or helper is a top-level `function name(...) { ... }` or `class Name { ... }` that ends with a `}` in column 0
  - every scriptlet is registered with `registerScriptlet(fn, { ... });`
    or `builtinScriptlets.push({ ..., fn: name, ... });`
"""
import base64
import json
import re
import sys
from pathlib import Path

FUNC_RE = re.compile(r"^(?:export\s+)?((?:async\s+)?function\s+(\w+)\s*\(|class\s+(\w+)\b)")
REGISTER_RE = re.compile(r"^registerScriptlet\(\s*(\w+)\s*,\s*\{")
PUSH_RE = re.compile(r"^builtinScriptlets\.push\(\{")


def parse_file(text):
    """Returns ({fn_ident: source}, [(fn_ident_or_None, details_block)])."""
    lines = text.split("\n")
    functions, registrations = {}, []
    i = 0
    while i < len(lines):
        line = lines[i]
        m = FUNC_RE.match(line)
        if m:
            start = i
            while i < len(lines) and lines[i] != "}":
                i += 1
            body = lines[start : i + 1]
            body[0] = body[0][m.start(1) :]  # drop `export `
            functions[m.group(2) or m.group(3)] = "\n".join(body)
        else:
            m = REGISTER_RE.match(line) or PUSH_RE.match(line)
            if m:
                start = i
                while i < len(lines) and not lines[i].startswith("});"):
                    i += 1
                block = "\n".join(lines[start : i + 1])
                registrations.append((m.group(1) if m.re is REGISTER_RE else None, block))
        i += 1
    return functions, registrations


def list_items(block, key):
    m = re.search(key + r"\s*:\s*\[(.*?)\]", block, re.S)
    if not m:
        return []
    return [item.strip() for item in m.group(1).split(",") if item.strip()]


def unquote(item):
    return item[1:-1] if item[:1] in "'\"" else None


def main(src_dir, out_path):
    functions, registrations = {}, []
    for path in sorted(Path(src_dir).glob("*.js")):
        f, r = parse_file(path.read_text(encoding="utf-8"))
        functions.update(f)
        registrations.extend(r)

    entries = []
    for ident, block in registrations:
        if ident is None:
            m = re.search(r"\bfn\s*:\s*(\w+)", block)
            ident = m.group(1) if m else None
        name = re.search(r"\bname\s*:\s*['\"]([^'\"]+)['\"]", block)
        if not ident or not name or ident not in functions:
            print(f"skip: could not resolve registration for {ident!r}", file=sys.stderr)
            continue
        entries.append(
            {
                "ident": ident,
                "name": name.group(1),
                "aliases": [a for a in map(unquote, list_items(block, "aliases")) if a],
                "deps": list_items(block, "dependencies"),
                "trusted": bool(re.search(r"requiresTrust\s*:\s*true", block)),
            }
        )

    name_of = {e["ident"]: e["name"] for e in entries}
    resources, missing = [], set()
    for e in entries:
        deps = []
        for dep in e["deps"]:
            resolved = unquote(dep) or name_of.get(dep)
            if resolved:
                deps.append(resolved)
            else:
                missing.add(dep)
        is_fn = e["name"].endswith(".fn")
        resource = {
            "name": e["name"],
            "aliases": e["aliases"],
            "kind": {"mime": "fn/javascript" if is_fn else "application/javascript"},
            "content": base64.b64encode(functions[e["ident"]].encode("utf-8")).decode("ascii"),
        }
        if deps:
            resource["dependencies"] = deps
        if e["trusted"]:
            resource["permission"] = 1
        resources.append(resource)

    resources.sort(key=lambda r: r["name"])
    Path(out_path).write_text(json.dumps(resources, separators=(",", ":")), encoding="utf-8")
    scriptlets = sum(1 for r in resources if not r["name"].endswith(".fn"))
    print(f"{len(resources)} resources ({scriptlets} scriptlets, {len(resources) - scriptlets} helpers) -> {out_path}")
    if missing:
        print(f"unresolved dependencies: {sorted(missing)}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
