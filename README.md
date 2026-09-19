# ParserUpwork

Pipeline for selecting Upwork jobs: search URLs → page dumps → parser with a hard auto-filter → manual review of survivors.

## How it works

1. **`extantion/`** — browser extension (Chrome MV3, "Upwork HTML Dumper").
   Opens search result pages from the URL list in `urls.json` and saves HTML dumps to a folder.
2. **`upwork-parse-diff/`** — offline parser (Java 21, jsoup; no network, no database).
   Reads a folder of dumps and outputs **only new** jobs (dedup by uid via a global `seen.bin`):
   - `new.md` — jobs for manual review;
   - `rejected.md` — jobs rejected by the hard auto-filter (with a `reason` column).
3. **Manual review** of `new.md` against the rules in `rules/` — what the script cannot check.

The packaged parser lives in `upwork-parse-dist/` (fat jar + bat wrapper). Build/update: `bash upwork-parse-dist/build.sh`. Verify: `upwork-parse-diff --selftest` → `STRUCTURE OK`.

## rules/ — single source of truth

| File | Contents |
| --- | --- |
| `список_ниш.md` | 14 niches (blocks A: Java/Spring, B: Android/Kotlin, C: scraping/automation) and search queries `q` (strict/broad) |
| `синтаксис_запросов.md` | How `q` works on Upwork: AND/OR/NOT, quotes, parentheses, URL encoding |
| `правила_отбора.md` | All selection rules: "Filter" level (encoded in URLs/script) and "Manual review" level |

### Rule separation

**Auto-filter** (URLs in `urls.json` + parser) — rejects "obviously NO" jobs.
**Manual review** — everything else from `rules/правила_отбора.md` (what the script cannot check).

Concrete values and rules live only in `rules/правила_отбора.md`, along with the application level of each ("Filter" / "Manual review").

## Structure

```
├── extantion/          # Chrome extension: dumps Upwork search results per urls.json
├── rules/              # niches, query syntax, selection rules
├── upwork-parse-diff/  # parser sources (Maven, Java 21)
└── upwork-parse-dist/  # packaged parser distribution (jar + bat + build.sh)
```

## Usage

```bash
# 1. Update search URLs when rules/ change
#    (manually or via a script from список_ниш.md + правила_отбора.md)

# 2. Use the extension to save fresh search dumps to a folder, e.g.:
#    C:\...\Страницы\2026-09-12_10-00\

# 3. Run the parser
upwork-parse-diff "C:\...\Страницы\2026-09-12_10-00"

# 4. Review new.md; rejected jobs are in rejected.md
```

`seen.bin` is global (`%USERPROFILE%\.upwork-parse-diff\seen.bin`), so repeat jobs across runs are not shown again; TTL is `--ttl-days 90`.
