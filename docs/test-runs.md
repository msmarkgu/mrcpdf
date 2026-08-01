1. Generate all-features.pdf in ./tests folder

```
(base) bgu@z30b:~/../mrcpdf$ ./gradlew generateTestPdfs
```

2. Run MCR on all-features.pdf, output to ./temp

```
(base) bgu@z30b:~/../mrcpdf$ ./deps/jdk/bin/java -jar ./build/mrcpdf.jar ./tests/all-features.pdf -o ./temp/all-features.pdf

MrcPdf v1.0.0
  Input:  ./tests/all-features.pdf (1.6 MB)
  Output: ./temp/all-features.pdf
  DPI:    300.0
  MRC:    on
  Workers: 4 thread(s)
  Pages:  10
  Words:  2317 (source text)
  Processing 10 pages...
    [prep-1] page 1:  9.3s
    [prep-3] page 3:  8.7s
    [prep-4] page 4:  8.4s
    [prep-2] page 2:  9.3s
    [prep-5] page 5:  5.0s
    [prep-7] page 7:  5.1s
    [prep-6] page 6:  5.6s
    [prep-8] page 8:  5.8s
    [prep-9] page 9:  3.5s
    [prep-10] page 10:  3.2s
    Processing done: 10 pages in 19.0s (4 threads)
  Batch JBIG2 compression...
    JBIG2 batch done in 1.0s (sym: 2508 bytes)
  Assembling PDF...
  Finalizing document...
  Bookmarks:  2 (preserved)
  Links:      4 (preserved)
  Attachments: 3 (preserved)
  Total: 10 pages in 0:29
  Output: all-features.pdf (246 KB)
  Words:  2317 (extracted text)
  
(base) bgu@z30b:~/../mrcpdf$
```
