> Bản lưu báo cáo audit. Tên artifact trong báo cáo chỉ các bằng chứng local được bảo quản tại `.superpowers/evidence/run_c4af214b4a8d/`; không phải đường dẫn tương đối từ thư mục tài liệu này.

# Independent model provenance audit

**Audit completeness: PASS. Authorship certainty: limited to the records described below.**

Run `run_c4af214b4a8d`; audit dispatch `ctx_81727b756bf9`; 2026-11-02. Scope is C0, VF0 and A1–A4/B0, through accepted B0 `84a4682a93fae5f41320e549e252bfd3a872d57e`, with checkout HEAD `d64b4d23bd16c7b32d650af11eb683180cfc7ccb`. PASS means the requested evidence was checked and its gaps disclosed; it does **not** certify the model used for every source-editing turn.

All artifact paths below are relative to this ignored plan directory. `provenance-audit-evidence.json` records receipt hashes, exact dispatch/source metadata, the original-receipt comparison, and fresh Git comparison results. No source, tracked docs, HEAD, index, build output, configuration or existing report was modified; no tests, helpers or model changes were used. The unfinished `final-review.md` was not read.

## Commit-to-attempt evidence

The corresponding worker report names each base/head pair; Git independently confirms the direct-parent chain shown below. Commit author identities and Codex coauthor trailers do not establish a particular model.

| Attempt / original receipt | Dispatch | Resulting commit | Recorded effective launch | Worker report |
| --- | --- | --- | --- | --- |
| C0 / `C0-launch.json` | `ctx_5f0594be0617` | `7d0e680549a9e18bca6f4c0116b3ad65cfec78b5` | Claude `sonnet`, xhigh | `C0-report.md` |
| VF0 / `VF0-launch.json` | `ctx_87aad4a08c82` | `2eb1880b22f4d5088209b9276be16fd93b8e29dc` | All launch fields null; existing process reused | `VF0-report.md` |
| A1 / `A1-launch.json` | `ctx_0f0f04c38c62` | `d84c28fbf16c6e52b561e20c31d13b6e7a29e38c` | Codex `gpt-6.1-sol`, xhigh | `task-1-report.md` |
| A2 / `A2-launch.json` | `ctx_4ebcbd302dd4` | `fe3c11fb3ca1ce2809915a2a8d3ed2a3ce110dac` | Codex `gpt-6.1-sol`, xhigh | `task-2-report.md` |
| A3 / `A3-launch.json` | `ctx_a4aa669aa850` | `93dc123f3ca388ff60c665721cd156fa109c14a3` | Codex `gpt-6.1-sol`, high | `task-3-report.md` |
| A4 / `A4-launch.json` | `ctx_d126472c16f3` | `0b0ecebad55dd8c57c54e26d8778453aeb57c240` | Codex `gpt-6.1-sol`, high | `task-4-report.md` |
| B0 / `B0-launch.json` | `ctx_77b669a459c8` | `84a4682a93fae5f41320e549e252bfd3a872d57e` | Codex `gpt-6.1-sol`, xhigh | `task-5-report.md` |

C0's parent is accepted G0 `3f3775ece2ce5acb1b15ab910736e38b08f7fd6f`; each later row is a direct child of the previous row. The sole child through current HEAD is docs commit `d64b4d2`, whose three paths are under `docs/superpowers/`. C0 produced production code and a test; VF0 changed only `src/test/java/com/ltm/rd/ui/SnapshotPumpTest.java`. This is attribution mapping, not a functional assessment.

`model-audit.json` was checked against **all 17 original launch receipts**: dispatch IDs and effective launch objects match in every entry. Its null VF0 fields are faithfully summarized, not proof of an unspecified-model launch.

## VF0 continuity and source evidence

`V0-launch.json` records Codex `gpt-6.1-sol`, high, dispatch `ctx_3a86275e8cec`, terminal `term_bba83548-b27a-45fa-a9e8-84cb7744ba59`, process `73011079-41d5-4462-bdce-f41d278d0909`. `VF0-launch.json` explicitly reuses the same terminal and process. Fresh `orca orchestration worker-show --dispatch ctx_87aad4a08c82 --json` confirms terminal resource origin V0 and owner VF0. `VF0-tail.json` also records provider Codex and projected model `gpt-6.1-sol`, with a source-exact transcript containing the commit command and result. Together these support continuity from the Sol-launched V0 process, rather than an independently recorded VF0 model selection.

Stored `A1-read.json`, `A2-read.json`, `A3-read.json`, `A4-read.json`, `B0-read.json` and `VF0-tail.json` all say `source=transcript`, `sourceExact=true`, provider Codex and projected model `gpt-6.1-sol`. `C0-transcript-tail.json` instead identifies Claude and has a null projected model; its original launch receipt supplies Sonnet. Their opaque source identities are preserved in the evidence JSON; they are not provider session IDs or transcript filesystem paths.

Fresh `worker-read --dispatch <exact ID> --source transcript --limit 200 --json` reads were attempted for all seven execution dispatches. VF0 and A1–A4/B0 still returned exact-source records, but bounded/clipped content (`contentComplete=false`); five are released-worker archives and A1 is a nonarchived exact read. C0 returned `worker_identity_changed`, so this audit uses its original exact-source saved receipt rather than another process or guessed transcript. The returned source identities and warnings are in the evidence JSON.

## Model changes, Luna suggestion, and limits

No completed in-session model change is evidenced in the inspected execution receipts, stored read records or available fresh read pages. That is a bounded observation, not proof that no change ever occurred. Normalized messages exposed no per-message model field in the fresh pages; projected provider/model is worker status metadata, not an attestation binding a model to each edit. Archives omit older messages and clip tool payloads. A launch model alone cannot prove every later turn used that model. Exact model-at-edit history is therefore **unavailable from the inspected records**; no universal Sol-only or never-Luna authorship claim is justified.

The specific Luna evidence is `final-review-terminal.json`: exact terminal source for review dispatch `ctx_12649120c11d`, source identity `TJEdd5SEvu9T5keZyihYPiGQOk7CoXfj`, showing a usage-limit message and a menu offering “Switch to gpt-6-luna,” with “Keep current model” alternatives and an `enter select` footer. Its projection still records `gpt-6.1-sol`; `final-review-launch.json` also records Sol. The highlighted first choice is an offered action, not a receipt that Enter was pressed, a switch succeeded, or Luna edited source. This review dispatch is distinct from the seven source-producing attempts. No specifically attributable Luna-produced diff was established.

Consequently, **no separate code review of a specifically identified Luna diff can be scoped from this evidence**. That does not waive the independent functional review of the whole execution diff. If stronger authorship proof becomes necessary, it requires exact provider turn/model metadata tied to actual edit events; this audit did not guess session IDs, search unrelated sessions, or infer missing facts.

## Fresh source equality: PASS

These read-only commands each exited **0** with no diff:

```text
git diff --quiet 84a4682 HEAD -- src pom.xml
git diff --quiet --cached 84a4682 -- src pom.xml
git diff --quiet 84a4682 -- src pom.xml
```

`git ls-files --others --exclude-standard -- src pom.xml` returned no entries. Initial full status was clean; the final recheck shows only two modified plan docs and the new `docs/superpowers/reviews/2026-11-02-host-viewer-a1-b0-final-review.md`. Coordinator message `msg_4a78255e9dbb` explicitly attributes those concurrent docs updates to the coordinator; none is a source/pom change. The three source comparisons above were repeated after that message and again exited 0. Both B0 and HEAD have `src` tree ID `ea87300a0aec50d4cfd6532bed5aab0bb7052d19` and `pom.xml` blob ID `6c2e7af400d7ae73341b2aa48f29b0e7e549285b`. Thus committed content, index and Git-normalized working-tree content in the requested source/build scope equal accepted B0; the docs commit adds no later source diff to attribute. This is Git content equality, not a claim about raw LF/CRLF byte identity or untracked ignored build files.
