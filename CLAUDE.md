# Project: Trend Explorer (Java)

Read Trend_Explorer_Build_Plan_Java_version1.md fully before any task.

Rules: run the Phase 0a environment preflight before requesting any install,
and only ask for what is missing; work one phase at a time and stop for
review; never commit secrets (.env and application-local.yml are gitignored);
never invent API fields or Maven coordinates — follow §5/§6 of the plan
exactly; Cloud Run region is us-central1; all analysis runs inside the HTTP
request on virtual threads (no background jobs, no WebFlux); no paid
services; prefer records, avoid Lombok, constructor injection only;
./mvnw verify must pass before a phase is done.

Starter code: trend-core-starter/ contains verified implementations of
Windows, Keywords, Scoring (log-odds), and DeltaMath plus a plain-Java test
harness (34 passing assertions). In Phase 1, move the classes under
src/main/java unchanged (packages already match). In Phases 2 and 4, port
every CoreTests assertion into JUnit 5 tests before extending behavior, then
delete CoreTests. Do not modify these classes and their tests in the same
commit.

Teaching: after completing each phase, write TUTORIALS/phase-N.md — a
beginner-friendly Markdown tutorial of what was just built, in the style of
Tutorial_1_Core_Math_version1.md: the concepts introduced (Spring and Java
features used and why — DI, beans, profiles, virtual threads, SseEmitter,
Flyway, Testcontainers, WireMock as they appear), a guided walkthrough of the
new code, the phase's key design decision explained from first principles,
worked examples with concrete numbers where math is involved, and 3-4
exercises (at least one "predict before you run" and one "write the failing
test first"). Use LaTeX ($...$ / $$...$$) for any mathematics. Keep NOTES.md
as a short factual changelog; the tutorial is where the teaching lives.
Tutorial numbering: core math is Tutorial 1; Phase 1's tutorial is
TUTORIALS/phase-1.md, and so on.
