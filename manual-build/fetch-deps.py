#!/usr/bin/env python3
"""Recursive Maven fetcher (POM closure) through the local 3129 relay.

Writes artifacts to /tmp/oe-deps/<group path>/<artifact>/<version>/.
Roots are given as GAV strings. Version mediation: highest version wins.
"""
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

DEST = "/tmp/oe-deps"
BASES = [
    "https://dl.google.com/dl/android/maven2/",
    "https://repo.maven.apache.org/maven2/",
    "https://plugins.gradle.org/m2/",
]
_opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": "http://127.0.0.1:3129", "https": "http://127.0.0.1:3129"})
)


def get(url):
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, method="GET")
            req.add_header("User-Agent", "oe-fetch/1.0")
            with _opener.open(req, timeout=180) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            last = e
        except Exception as e:
            last = e
        import time

        time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"GET {url} failed: {last}")


def rel(g, a, v):
    return f"{g.replace('.', '/')}/{a}/{v}"


def fetch_file(g, a, v, filename):
    dest = os.path.join(DEST, rel(g, a, v), filename)
    if os.path.exists(dest):
        return dest
    for base in BASES:
        data = get(base + rel(g, a, v) + "/" + filename)
        if data is not None:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(data)
            return dest
    return None


def strip_ns(tag):
    return tag.split("}", 1)[-1]


def children(el, name):
    return [c for c in el if strip_ns(c.tag) == name]


def child(el, name):
    if el is None:
        return None
    for c in el:
        if strip_ns(c.tag) == name:
            return c
    return None


def text(el):
    return (el.text or "").strip() if el is not None else ""


class Pom:
    def __init__(self, g, a, v):
        self.g, self.a, self.v = g, a, v
        self.props = {}
        self.depmgmt = {}
        self.deps = []
        self.packaging = "jar"
        self.parent = None
        path = fetch_file(g, a, v, f"{a}-{v}.pom")
        if path is None:
            raise RuntimeError(f"POM not found: {g}:{a}:{v}")
        root = ET.parse(path).getroot()
        pg = text(child(child(root, "parent"), "groupId"))
        pa = text(child(child(root, "parent"), "artifactId"))
        pv = text(child(child(root, "parent"), "version"))
        if pg and pa and pv:
            try:
                self.parent = Pom(pg, pa, pv)
            except Exception as e:
                print(f"  (parent pom {pg}:{pa}:{pv} unavailable: {e})")
        props_el = child(root, "properties")
        if props_el is not None:
            for p in props_el:
                self.props[strip_ns(p.tag)] = text(p)
        self.props.setdefault("project.version", v)
        self.props.setdefault("pom.version", v)
        self.props.setdefault("project.groupId", g)
        dm = child(child(root, "dependencyManagement"), "dependencies")
        if dm is not None:
            for d in children(dm, "dependency"):
                key = (text(child(d, "groupId")), text(child(d, "artifactId")))
                self.depmgmt[key] = d
        deps_el = child(root, "dependencies")
        if deps_el is not None:
            for d in children(deps_el, "dependency"):
                self.deps.append(d)
        pk = text(child(root, "packaging"))
        if pk:
            self.packaging = pk

    def resolve(self, s, depth=0):
        if s is None:
            return ""
        def repl(m):
            key = m.group(1)
            if key in self.props:
                return self.props[key]
            if self.parent is not None:
                return self.parent.resolve("${" + key + "}", depth + 1)
            return m.group(0)
        out = re.sub(r"\$\{([^}]+)\}", repl, s)
        return out

    def managed_version(self, g, a):
        key = (g, a)
        pom = self
        while pom is not None:
            if key in pom.depmgmt:
                d = pom.depmgmt[key]
                return pom.resolve(text(child(d, "version")))
            pom = pom.parent
        return ""


def parse_dep(pom, d):
    g = pom.resolve(text(child(d, "groupId")))
    a = pom.resolve(text(child(d, "artifactId")))
    v = pom.resolve(text(child(d, "version")))
    scope = text(child(d, "scope")) or "compile"
    typ = text(child(d, "type")) or "jar"
    optional = text(child(d, "optional")) == "true"
    if not v:
        v = pom.managed_version(g, a)
    return g, a, v, scope, typ, optional


def ver_key(v):
    parts = re.split(r"[.\-]", v)
    key = []
    for p in parts:
        key.append((0, int(p), "") if p.isdigit() else (1, 0, p))
    return key


requested = {}  # (g,a) -> set of versions
poms = {}


def norm_version(v):
    v = v.strip()
    m = re.fullmatch(r"\[(.+)\]", v)
    if m:
        return m.group(1)
    m = re.fullmatch(r"[\[(]([^,]+),.*", v)
    if m:
        return m.group(1)
    return v


def collect(g, a, v):
    key = (g, a)
    requested.setdefault(key, set()).add(norm_version(v))


def main():
    roots = [tuple(r.split(":")) for r in sys.argv[1:]]
    for g, a, v in roots:
        collect(g, a, v)
    # iterate to fixpoint: resolve poms for the mediated (max) version of each GA
    for _ in range(30):
        changed = False
        for (g, a), versions in list(requested.items()):
            best = max(versions, key=ver_key)
            if (g, a) in poms and poms[(g, a)].v == best:
                continue
            pom = Pom(g, a, best)
            poms[(g, a)] = pom
            changed = True
            for d in pom.deps:
                dg, da, dv, scope, typ, optional = parse_dep(pom, d)
                if optional or scope in ("test", "import", "system", "provided"):
                    continue
                if typ == "pom":
                    continue
                if not dv:
                    print(f"  !! no version for {dg}:{da} (dep of {g}:{a}:{best})")
                    continue
                before = set(requested.get((dg, da), set()))
                collect(dg, da, dv)
                if requested[(dg, da)] != before:
                    changed = True
        if not changed:
            break
    print("\n=== mediated closure ===")
    for (g, a), pom in sorted(poms.items()):
        if pom.packaging == "pom":
            print(f"SKIP(pom-packaging) {g}:{a}:{pom.v}")
            continue
        ext = "aar" if pom.packaging == "aar" else "jar"
        fname = f"{a}-{pom.v}.{ext}"
        p = fetch_file(g, a, pom.v, fname)
        print(f"{'OK ' if p else 'MISS'} {g}:{a}:{pom.v} ({pom.packaging}) -> {fname}")


if __name__ == "__main__":
    main()
