"""Validate and package the versioned Gradle release output using only the stdlib."""

import argparse
import hashlib
from pathlib import Path
import re
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    version = args.version
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
        parser.error("Version must be a release SemVer value")
    configured = re.search(r"^version=(.+)$", (root / "gradle.properties").read_text(), re.M)
    if configured is None or configured.group(1).strip() != version:
        parser.error("Version does not match gradle.properties")
    if args.tag is not None and args.tag != "v" + version:
        parser.error("Tag does not match the release version")
    release = root / "build/release"
    expected = [f"minecraft-harness-forge-1.7.10-{version}{suffix}.jar" for suffix in ("", "-dev", "-sources")]
    for module in ("agent-api", "core", "chat-ui", "agent-codex"):
        expected.extend(f"{module}-{version}{suffix}.jar" for suffix in ("", "-sources"))
    expected.extend(("LICENSE", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md", "README.md", "RELEASE_NOTES.md"))
    missing = [name for name in expected if not (release / name).is_file()]
    if missing:
        parser.error("Missing release artifacts: " + ", ".join(missing))
    archive_name = f"minecraft-harness-{version}.zip"
    unexpected = {path.name for path in release.iterdir()} - set(expected) - {archive_name, "SHA256SUMS"}
    if unexpected:
        parser.error("Unexpected release artifacts: " + ", ".join(sorted(unexpected)))
    for name in expected:
        if name.endswith(".jar"):
            with zipfile.ZipFile(release / name) as jar:
                broken = jar.testzip()
                if broken:
                    parser.error(f"Corrupt JAR member: {name}/{broken}")
    with zipfile.ZipFile(release / archive_name, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(expected):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, (release / name).read_bytes())
    sums = []
    for name in sorted(expected + [archive_name]):
        digest = hashlib.sha256((release / name).read_bytes()).hexdigest()
        sums.append(f"{digest}  {name}\n")
    (release / "SHA256SUMS").write_text("".join(sums), encoding="utf-8", newline="\n")
    print(f"Prepared {len(expected)} artifacts, archive and SHA256SUMS in {release}")


if __name__ == "__main__":
    main()
