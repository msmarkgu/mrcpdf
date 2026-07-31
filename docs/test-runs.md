1. Generate scanned-text.pdf in ./tests folder

```
(base) bgu@z30b:~/../mrcpdf$ ./gradlew generateTestPdfs
```

2. Run MCR on scanned-text.pdf, output to ./temp

```
(base) bgu@z30b:~/../mrcpdf$ ./deps/jdk/bin/java -jar ./build/mrcpdf.jar ./tests/scanned-text.pdf -o ./t
emp/scanned-text-mrc.pdf

MrcPdf v1.0.0
  Input:  ./tests/scanned-text.pdf (1.6 MB)
  Output: ./temp/scanned-text-mrc.pdf
  DPI:    300.0
  MRC:    on
  Workers: 4 thread(s)
  Pages:  10
  Words:  2317 (source text)
  Processing 10 pages...
    [prep-4] page 4:  6.6s
    [prep-2] page 2:  7.6s
    [prep-3] page 3:  7.2s
    [prep-1] page 1:  8.0s
    [prep-7] page 7:  5.1s
    [prep-5] page 5:  5.7s
    [prep-6] page 6:  5.8s
    [prep-8] page 8:  6.1s
    [prep-9] page 9:  2.9s
    [prep-10] page 10:  3.1s
    Processing done: 10 pages in 17.4s (4 threads)
  Batch JBIG2 compression...
    JBIG2 batch done in 0.6s (sym: 2475 bytes)
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 0:27
  Output: scanned-text-mrc.pdf (244 KB)
  Words:  2317 (extracted text)

(base) bgu@z30b:~/../mrcpdf$
```
