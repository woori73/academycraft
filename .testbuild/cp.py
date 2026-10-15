import os
repo = "/home/hatch/.m2/repository"
best = {}
for root, _, files in os.walk(repo):
    for f in files:
        if not f.endswith(".jar") or "sources" in f or "javadoc" in f:
            continue
        p = os.path.join(root, f)
        version = os.path.basename(root)            # .../artifactId/version/
        artifact = os.path.basename(os.path.dirname(root))
        if artifact not in best or version > best[artifact][0]:
            best[artifact] = (version, p)
cp = ":".join(sorted(v[1] for v in best.values()))
print(cp)
