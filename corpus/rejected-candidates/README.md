# Rejected base-document candidates

Documents that passed the HTTP check (`200`, `application/pdf`) but were
dropped from `corpus/real-bases.csv` on content grounds. "The URL serves a PDF"
does not imply "the PDF is born-digital with enough prose to label" — a scan
with a partial OCR layer serves a perfectly valid PDF and would poison the
corpus with vacuous labels.

The test is per-page text-layer density, measured on the source document:
a base page is only usable if it has enough words in the 150..3000 band, and
a base document must supply at least 5 usable pages.

| doc | why rejected |
|---|---|
| `2026-19701` (Federal Register) | 3 pages, only 2 in the 150..3000 band — cannot supply the required 5 |
| `20020043393` (NASA NTRS, 2002) | 37 pages but median 24 words/page; a scan with a partial OCR layer |
| `20190030273` (NASA NTRS) | rejected after the manifest was first written; the reason was not recorded in `notes/collect-realworld-pdfs.md`, and the PDF is no longer on disk to re-measure |

The first two were replaced with `2026-19671` (47-page FR rule) and
`20190029231` / `20180003974` (2019/2018 NTRS reports, 15 and 12 in-band
pages). See `notes/collect-realworld-pdfs.md` §"Manifest curation".

> **Reconstructed 2026-09-27.** This file and `corpus/real-bases.csv` were
> recovered from `build/real-bases/provenance.csv` after the `corpus/`
> directory was emptied. The manifest is exact — every row came from a
> recorded fetch, all with `status=cached` — but this README is new prose and
> the `20190030273` rejection reason above is unverified. The rejected PDFs
> themselves were third-party content and are not committed here.
