# Build dependencies

`python3 scripts/build_android.py` downloads these immutable Maven Central artifacts
and verifies every file against `SHA256SUMS` before compiling:

- `org.bouncycastle:bcprov-jdk15to18:1.83`
- `io.nayuki:qrcodegen:1.8.0`

JAR files are cached locally and ignored by Git. R8 retains only reachable code.
See the root `THIRD_PARTY_NOTICES.md` and `licenses/` for redistribution notices.
