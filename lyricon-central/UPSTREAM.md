# Lyricon central source module

This directory contains the central bridge sources from Lyricon, pinned to commit `2bc34008b8a22c7261d33acceed7d708e23ef6d6` (2026-04-07, merge pull request #62). The repository is https://github.com/tomakino/lyricon and the original module path is `lyric/bridge/central`.

The Kotlin sources, Android manifest, consumer/proguard rules, and root Apache-2.0 license were copied directly from that Git commit. `source-hashes.json` records the original bytes of these 20 files, including 16 Kotlin files. All 20 file hashes matched during release preparation. Copyright and license headers remain intact; no source algorithms or package names were changed. This establishes the identity of the copied source snapshot, not its behavioral equivalence to a previously cached binary.

This commit's root build configuration sets provider, subscriber, and lyric model SDK versions to `0.1.70`. Its central module is an internal Android library without Maven publishing coordinates. The newer local checkout was deliberately not used: it contains a different central implementation and SDK configuration.

The ZHIColor build file adapts project integration: it uses the project's Android Gradle plugin and compile SDK 37, retains minimum SDK 27 and Java 17, and replaces sibling project dependencies with published provider/subscriber `0.1.70` API dependencies. The original central build declares only the sibling projects and Core KTX directly; its source also uses coroutines and serialization through transitive dependencies. ZHIColor declares these source dependencies explicitly, retaining the application's previously resolved coroutines `1.9.0` and serialization `1.10.0`. The pinned upstream version catalog sets Core KTX to `1.17.0` and serialization to `1.10.0`; it does not declare a coroutines version. No source requirement for a coroutines upgrade was found. This module declares no new serializable data classes and does not require a serialization compiler plugin.

This source module replaces the unresolved `io.github.proify.lyricon:central:0.1.70` binary coordinate. On 2026-10-17, Maven Central returned 404 for that coordinate's artifact and Maven metadata while the subscriber `0.1.70` POM returned 200. The preexisting cached central AAR had SHA-256 `cf758f86d0c6799103cceae53b2591599d9235e4d7b5dc5fe11e0c0b01a9d4f2`. Its exact original build provenance was not established.

A static comparison during release preparation found the same 31 class names in the cached AAR and the source module's release classes JAR, including nested/generated classes. Class headers, field/method signatures, access flags, constant values, and inheritance/interface declarations matched except for three Kotlin internal methods in `ScreenStateMonitor`: their module-name suffix changed from `$central` to `$lyricon_central`. The `BridgeCentral` entry points and the `receiver` field used by ZHIColor matched. Only 3 of the 31 class files were byte-identical in that comparison. The cached AAR's manifest contained only its package and minimum SDK 27, and its consumer rules were empty, consistent with the source module.

These checks support source identity and broad ABI compatibility; they do not prove equivalent method bodies, Binder timing, registration/recovery, serialization, shared-memory handling, process-death cleanup, or screen-state behavior. Those paths require separate runtime validation with provider/subscriber `0.1.70` and the embedded-central lifecycle used by ZHIColor. A successful build or matching class inventory alone is not behavioral acceptance.

To verify copied source bytes from the repository root:

```python
from pathlib import Path
import hashlib
import json
module = Path("lyricon-central")
for entry in json.loads((module / "source-hashes.json").read_text())["files"]:
    assert hashlib.sha256((module / entry["path"]).read_bytes()).hexdigest() == entry["sha256"], entry["path"]
print("PASS: copied Lyricon central files match the pinned source manifest")
```

The original license is available in `LICENSE`. Changes to ZHIColor's build integration are described above; the copied files remain unchanged.
