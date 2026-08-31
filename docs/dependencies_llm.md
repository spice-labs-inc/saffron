# Dependencies — LLM Summary

## Policy (hard facts)

- "Latest" = newest STABLE from the full Maven Central version list
  (pre-release suffixes filtered); metadata <release>/<latest> fields
  LIE (slf4j → 2.1.0-alpha1).
- Java 21 fixed; every bumped jar verified (class major ≤ 53).
- JUnit pinned 5.14.4 (6.1.3 blocked by jazzer 0.30.0/archunit 1.5.0
  JUnit-5 API).
- No lockfile (user decision — rolls into Goat Rodeo/spice CLI/other).
- Baharat divergence approved (not architectural).
- Maven runner unpinned (3.8.7) → spotbugs pinned 4.9.8.5 (4.10.4.0
  needs Maven ≥ 3.8.9).

## Bumps applied

annotations 24.1.0→26.1.0; xz 1.9→1.12; zstd-jni 1.5.5-6→1.5.7-15;
commons-compress 1.26.0→1.28.0 (CVE remediation); lz4-java
org.lz4:1.8.0→at.yawk.lz4:1.11.2 (relocated; 1.8.1 CVE-flagged by
osv-scanner); slf4j 2.0.9→2.0.18; junit 5.10.2→5.14.4; gson 2.10.1→2.14.0;
assertj 3.25.3→3.27.7; awaitility 4.2.0→4.3.0; jazzer 0.22.1→0.30.0;
archunit 1.2.1→1.5.0; compiler 3.12.1→3.15.0; surefire/failsafe
3.2.5→3.5.6; jar 3.3.0→3.5.1; source 3.3.0→3.4.0; javadoc 3.6.3→3.12.0;
jacoco 0.8.11→0.8.15; spotbugs 4.8.3.1→4.10.3.0; exec 3.1.0→3.6.3; gpg
3.2.7→3.2.8; central-publishing 0.8.0→0.11.0.

No update: packageurl-java 1.5.0, lzo-core 1.0.6 (accept-risk), jmh 1.37.

## Verification results

- Integrity: sha1 of all 26 final dependency+plugin jars OK vs Central
  (integrity-final.txt); platform skew clean (5.14.4/1.14.4 single
  versions). PGP: partial (publisher keys unreachable) — documented
  remediation; javadoc-baseline and initial-osv-scan gaps recorded.
- CVE gate: osv-scanner 2.5.1 "No issues found" (after lz4 1.11.1 fix).
- Tests: baseline 1810 (1754+56) 0/0/0; per-step suites green; final
  1810 0/0/0; jacoco 71.5% before AND after.
- R8.7 adaptation: kernel-decompressor test helpers stop double-finishing
  compression streams (commons-compress 1.28/xz 1.12 null state after
  finish()).
- Spotbugs 4.10.3.0: 186 bugs, all pre-existing (baseline XML 193;
  difference is 4.10.x detector changes).
- surefire 3.5.6: both executions (default parallel + hardening fork)
  verified running.

## Artifacts

docs/audit-phase8/{baseline/,step-01-runtime-deps/,step-02-test-framework/,
step-03-plugins/,tree-before.txt,tree-after.txt,osv-scan.txt,pom-final.xml}
