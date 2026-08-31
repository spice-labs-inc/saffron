# Dependencies — Version Matrix and Policy

Audit date: 2026-08-26/27 (phase 8 of the cleanup plan). All versions are
the newest STABLE releases on Maven Central as of the audit date
(pre-releases excluded by parsing the full version list, never the
metadata `<release>`/`<latest>` fields, which lie — e.g. slf4j points at
2.1.0-alpha1). Raw audit artifacts: `docs/audit-phase8/`.

## Policy

- Java 21 toolchain fixed (`maven.compiler.source/target=21`); every
  bumped jar checked (class-file major ≤ 65 — all observed are ≤ 53).
- Same-major upgrades by default; the one blocked major (JUnit 6.1.3) is
  documented below.
- No lockfile (user decision): Saffron rolls into Goat Rodeo → spice CLI →
  a proprietary project; version drift is resolved at those integration
  points. Recorded as an explicit reproducibility deviation.
- Baharat alignment: divergence approved (version choices are not
  architectural decisions); the stale "aligned with Baharat" comment is
  removed from the pom.
- Maven runner is UNPINNED (3.8.7 locally, no mvnw) — spotbugs
  4.10.4.0 requires Maven ≥ 3.8.9, so spotbugs is pinned at 4.9.8.5
  (newest compatible). Pinning Maven in CI is a separate proposal.

## Version matrix

| Artifact | Before | After | Notes |
|---|---|---|---|
| org.jetbrains:annotations | 24.1.0 | 26.1.0 | |
| com.github.package-url:packageurl-java | 1.5.0 | 1.5.0 | already latest |
| org.tukaani:xz | 1.9 | 1.12 | pure-Java; unaffected by CVE-2024-3094 (C-side build injection) — rationale documented |
| com.github.luben:zstd-jni | 1.5.5-6 | 1.5.7-15 | native prebuilts from the publisher (trust model documented) |
| org.apache.commons:commons-compress | 1.26.0 | 1.28.0 | CVE remediation (CVE-2024-25710/CVE-2024-26308 fixed in 1.26.1; 2025 zstd-bomb family in 1.28.x) |
| at.yawk.lz4:lz4-java | org.lz4 1.8.0 | 1.11.2 | org.lz4 relocated to at.yawk.lz4; 1.8.1 has known CVEs (GHSA-cmp6-m4wj-q63q, GHSA-xx22-p4ch-683r) — osv-scanner caught this; fixed at 1.11.1 |
| org.anarres.lzo:lzo-core | 1.0.6 | 1.0.6 | latest; unmaintained; no known CVE — accept-risk decision |
| org.slf4j:slf4j-api / slf4j-simple | 2.0.9 | 2.0.18 | (2.1.0-alpha1 excluded: pre-release) |
| org.junit.jupiter:junit-jupiter | 5.10.2 | 5.14.4 | newest 5.x; 6.1.3 (major) blocked — jazzer-junit 0.30.0 and archunit-junit5 1.5.0 target the JUnit 5 API. Re-check dated in this file. |
| com.google.code.gson:gson | 2.10.1 | 2.14.0 | test |
| org.assertj:assertj-core | 3.25.3 | 3.27.7 | (4.0.0-M1 excluded: milestone) |
| org.awaitility:awaitility | 4.2.0 | 4.3.0 | test |
| com.code-intelligence:jazzer-junit | 0.22.1 | 0.30.0 | junit-platform exclusions still valid |
| com.tngtech.archunit:archunit-junit5 | 1.2.1 | 1.5.0 | |
| org.openjdk.jmh:jmh-core | 1.37 | 1.37 | already latest |
| maven-compiler-plugin | 3.12.1 | 3.15.0 | |
| maven-surefire/failsafe-plugin | 3.2.5 | 3.5.6 | dual-execution (default + hardening fork) verified working; no forkNodeImplementation fallback needed |
| maven-jar-plugin | 3.3.0 | 3.5.1 | |
| maven-source-plugin | 3.3.0 | 3.4.0 | |
| maven-javadoc-plugin | 3.6.3 | 3.12.0 | javadoc:jar green, no doclint warnings |
| jacoco-maven-plugin | 0.8.11 | 0.8.15 | coverage 71.5% before AND after (no drift) |
| spotbugs-maven-plugin | 4.8.3.1 | 4.10.3.0 | newest stable that supports Maven 3.8.7 (4.10.4.0 requires ≥3.8.9); reports 186 bugs — the same pre-existing set (baseline XML: 193 instances; the difference is detector changes in 4.10.x, not new code findings) |
| exec-maven-plugin | 3.1.0 | 3.6.3 | corpus download exercised by every build |
| maven-gpg-plugin | 3.2.7 | 3.2.8 | `mvn -P maven-central validate` green |
| central-publishing-maven-plugin | 0.8.0 | 0.11.0 | same profile gate |

## Integrity verification (R8.3)

- SHA-1 of every direct dependency AND every build-plugin jar (26
  artifacts, FINAL versions) computed from the local repo and
  cross-checked directly against repo1.maven.org — ALL OK, recorded in
  `docs/audit-phase8/integrity-final.txt`. (An earlier partial record in
  step-03/integrity.txt covered only a subset and predated the final lz4
  bump; it is superseded.)
- Class-file major versions ≤ 53 (Java 21-compatible).
- dependency:tree before/after committed; JUnit platform skew check:
  single version per artifact (jupiter 5.14.4, platform 1.14.4; jazzer's
  5.9.0 jupiter-api loses to the direct dependency by nearest-wins).
- PGP `.asc` verification: PARTIAL — the publisher key material was
  unreachable from this network (Apache KEYS endpoints 404, keyservers
  blocked). Remediation: re-run signature verification in an environment
  with keyserver access; checksum integrity (the immutability guarantee)
  passed for every jar.
- Known trail limitations (recorded per the adversarial audit): the
  javadoc 3.6.3 baseline was never captured ("0 new doclint warnings"
  rests on the 3.12.0 run succeeding cleanly); the initial osv-scan that
  flagged lz4 1.8.1 was not preserved as an artifact (the final
  "No issues found" scan is committed); the per-step logs under
  docs/audit-phase8 are summaries, not full surefire XML.

## Security gates (R8.4)

- osv-scanner 2.5.1 over the resolved dependencies: **No issues found**
  (after the lz4 CVE fix it caught). The scan is the gate: the initial
  scan flagged at.yawk.lz4:lz4-java 1.8.1 (High + Medium CVEs) and drove
  the 1.11.1 bump.
- zstd-jni native trust model: publisher-prebuilt native binaries that
  run on hostile input; documented as an accepted trust decision.

## Regression gates (R8.5/R8.6)

- Baseline (pre-bump): 1810 tests (1754 default + 56 hardening), 0/0/0,
  jacoco 71.5%, spotbugs 4.8.3.1 = 198 findings (build-failing,
  pre-existing), Maven 3.8.7.
- Per-step full suites: runtime deps → green; test-framework group →
  green (fuzz tests executed under the new JUnit platform); compiler →
  green; surefire 3.5.6 → green with BOTH surefire executions running.
- Code adaptation (R8.7): the kernel-decompressor test helpers
  double-finished compression streams (`finish()` then try-with-resources
  `close()`) — commons-compress 1.28/xz 1.12 null their state after
  finish; helpers now close-only (fixture-generation code, red→green
  with the library bump).
- Golden decompression byte-compare: the corpus SHA-256 suites run the
  new codecs over real files — green.
- jmh smoke run: `ReadBenchmark` executes under the new deps.
- Final: 1810 tests, 0 failures, 0 errors, 0 skipped; coverage 71.5%
  (no drift from jacoco 0.8.15).
- Post-audit remediation (2026-08-27): lz4 bumped 1.11.1 → 1.11.2,
  spotbugs 4.9.8.5 → 4.10.3.0, the stale Baharat comment removed, and
  the final integrity/tree/osv artifacts regenerated
  (`integrity-final.txt`, `tree-after.txt`, `metadata-dump.txt`).

## Dated re-checks

- JUnit 6 adoption: re-check jazzer-junit/archunit-junit5 support for
  JUnit 6 before any future bump (checked 2026-08-26: not supported).
- lzo-core: re-check for a maintained successor on any CVE advisory
  (none as of 2026-08-26).
