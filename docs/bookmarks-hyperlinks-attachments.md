# Preserve Attachments + Print Metadata Counts + Tests

**Date:** 2026-07-26
**Status:** Implementation in progress

---

## Goal

1. Preserve embedded files (attachments) from input PDF in output PDF
2. Print counts of bookmarks, hyperlinks, and attachments to console output
3. Add necessary tests

---

## Current State

| Feature | Status |
|---------|--------|
| Bookmarks/Outlines | ✅ Preserved (`MetadataPreserver.copyOutline()`, lines 88-97) |
| Hyperlinks/Annotations | ✅ Preserved (`MetadataPreserver.copyAnnotations()`, lines 99-118) |
| Attachments/Embedded Files | ❌ **Lost** — no code exists |

`MetadataPreserver.preserve()` currently returns `void`. Both `preserve()` and `finishAssembly()` return void.

---

## Plan

### Task 1: Preserve attachments in output

**File:** `MetadataPreserver.java`

1. Add `copyEmbeddedFiles(PDDocument source, PDDocument output)`:
   - Get `PDDocumentNameDictionary` from source catalog
   - Get `PDEmbeddedFiles` from it; return early if null
   - Deep-copy the `/EmbeddedFiles` COS dictionary via existing `deepCopyCOSDictionary()`
   - Set the copied tree on the output document's `Names` dictionary
   - Follows the exact same COS-level deep-copy pattern as `copyOutline()` and `copyAnnotations()`

2. Call `copyEmbeddedFiles(source, output)` from `preserve()`

3. Change `preserve()` return type from `void` to:
   ```java
   public record PreserveResult(int outlines, int annotations, int embeddedFiles) {}
   ```
   Each copy method returns its count. `preserve()` aggregates and returns.

### Task 2: Print counts in console output

**File:** `MrcPdf.java`

After the `preserver.preserve(...)` call, use the returned `PreserveResult` to print:

```
  Bookmarks:  3 (preserved)
  Links:      12 (preserved)
  Attachments: 2 (preserved)
```

Only print lines for counts > 0.

### Task 3: Tests

**File:** `TestPdfGenerator.java`

1. Add `makeWithEmbeddedFiles()` — creates a PDF with:
   - 2 text pages
   - 2 embedded files (a small text file and a small image) using `PDEmbeddedFile` + `PDComplexFileSpecification`
   - Output: `tests/with-attachments.pdf`

**File:** `MetadataPreserverTest.java`

2. Add `preserve_copiesEmbeddedFiles()`:
   - Load `tests/with-attachments.pdf`
   - Run pipeline → assemble → preserve
   - Assert output has same number of embedded files
   - Assert embedded file names match

3. Add `preserve_returnsCounts()`:
   - Load a PDF with bookmarks + annotations + embedded files
   - Assert `PreserveResult` has correct counts

**File:** `PipelineIntegrationTest.java`

4. Add `fullPipeline_withAttachments_outputPreservesEmbeddedFiles()` — end-to-end test

---

## Changes by file

| File | Changes |
|------|---------|
| `MetadataPreserver.java` | Add `PreserveResult` record, `copyEmbeddedFiles()` method, change `preserve()` return type, count in each copy method |
| `MrcPdf.java` | Update `preserve()` call, print counts from `PreserveResult` |
| `TestPdfGenerator.java` | Add `makeWithEmbeddedFiles()` method |
| `MetadataPreserverTest.java` | Add `preserve_copiesEmbeddedFiles()` + `preserve_returnsCounts()` tests |
| `PipelineIntegrationTest.java` | Add attachment integration test |

## Order of implementation

1. `MetadataPreserver.java` — add `PreserveResult` record, refactor copy methods to return counts, add `copyEmbeddedFiles()`
2. `MrcPdf.java` — update `preserve()` call, print counts
3. `TestPdfGenerator.java` — add embedded files PDF
4. `MetadataPreserverTest.java` — add tests
5. `PipelineIntegrationTest.java` — add integration test
6. `./gradlew test` — verify all pass
