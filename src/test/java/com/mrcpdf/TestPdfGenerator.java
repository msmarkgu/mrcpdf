package com.mrcpdf;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnderline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "generate-test-pdfs",
         description = "Generate test PDFs for unit tests",
         mixinStandardHelpOptions = true)
public class TestPdfGenerator implements Callable<Integer> {

    private static final int W = 612, H = 792;
    private static final int MARGIN = 50;
    private static final int LINE_HEIGHT = 22;
    private static final String FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    private static final Path OUT_DIR = Path.of("tests");

    @Option(names = "--force", description = "Regenerate existing files")
    private boolean force;

    private Font font14;
    private Font font16;
    private Font font18;

    @Override
    public Integer call() {
        try {
            Files.createDirectories(OUT_DIR);
            font14 = loadFont(14);
            font16 = loadFont(16);
            font18 = loadFont(18);

            makeBlank();
            makeSimpleText();
            makeMultiPage();
            makeTwoColumn();
            makeWithAnnotations();
            makeWithAttachments();
            makeNoisyScan();
            makeScannedText();
            makeAllInOne();

            System.out.println("\nDone. Files in " + OUT_DIR + "/");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private Font loadFont(int size) {
        File ttf = new File(FONT_PATH);
        if (ttf.exists()) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont((float) size);
            } catch (Exception e) { /* fall through */ }
        }
        return new Font("SansSerif", Font.PLAIN, size);
    }

    private BufferedImage newPage() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    private Graphics2D createGraphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        return g;
    }

    private void saveImagePdf(List<BufferedImage> pages, String name) throws IOException {
        Path path = OUT_DIR.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage img : pages) {
                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                }
            }
            doc.save(path.toFile());
        }
        System.out.println("  " + name);
    }

    // ── 1. blank.pdf ────────────────────────────────────────────────────

    private void makeBlank() throws IOException {
        saveImagePdf(List.of(newPage()), "blank.pdf");
    }

    // ── 2. simple-text.pdf ──────────────────────────────────────────────

    private void makeSimpleText() throws IOException {
        BufferedImage img = newPage();
        Graphics2D g = createGraphics(img);
        g.setFont(font14);
        String[] lines = {
            "The quick brown fox jumps over the lazy dog.",
            "Pack my box with five dozen liquor jugs.",
            "Sphinx of black quartz, judge my vow.",
            "",
            "This is a test document for OCR validation.",
            "It contains English text rendered as an image,",
            "simulating a scanned page that is not searchable.",
            "",
            "The pipeline should extract this text via Tesseract,",
            "then embed it as invisible text over the image.",
            "The output PDF should be searchable while retaining",
            "the original visual appearance.",
        };
        int y = MARGIN;
        for (String line : lines) {
            if (!line.isEmpty()) g.drawString(line, MARGIN, y);
            y += LINE_HEIGHT;
        }
        g.dispose();
        saveImagePdf(List.of(img), "simple-text.pdf");
    }

    // ── 3. multi-page.pdf ───────────────────────────────────────────────

    private void makeMultiPage() throws IOException {
        String[][] pageData = {
            {
                "Page 1: Introduction",
                "This is the first page of a multi-page document.",
                "It contains introductory text about the topic.",
                "Each page has different content to test that",
                "the OCR pipeline processes all pages correctly.",
            },
            {
                "Page 2: Methodology",
                "The approach uses a 6-step pipeline:",
                "1. Page extraction via PDFBox at 300 DPI",
                "2. Image segmentation via pure-Java binarization",
                "3. JBIG2 compression of text mask",
                "4. OCR via Tesseract CLI subprocess",
                "5. PDF re-assembly with searchable text",
                "6. Metadata preservation (bookmarks, annotations)",
            },
            {
                "Page 3: Results",
                "The output is a searchable, highly-compressed PDF",
                "that maintains the original visual appearance",
                "while adding a hidden text layer for searching.",
                "All metadata from the source PDF is preserved.",
            },
        };

        List<BufferedImage> pages = new ArrayList<>();
        for (String[] data : pageData) {
            BufferedImage img = newPage();
            Graphics2D g = createGraphics(img);
            g.setFont(font18);
            g.drawString(data[0], MARGIN, MARGIN);
            g.setFont(font14);
            int y = MARGIN + 40;
            for (int i = 1; i < data.length; i++) {
                g.drawString(data[i], MARGIN, y);
                y += LINE_HEIGHT;
            }
            g.dispose();
            pages.add(img);
        }
        saveImagePdf(pages, "multi-page.pdf");
    }

    // ── 4. two-column.pdf ───────────────────────────────────────────────

    private void makeTwoColumn() throws IOException {
        BufferedImage img = newPage();
        int colW = (W - 3 * MARGIN) / 2;
        int x1 = MARGIN;
        int x2 = MARGIN * 2 + colW;

        String body = "Lorem ipsum dolor sit amet, consectetur adipiscing "
            + "elit. Sed do eiusmod tempor incididunt ut labore et "
            + "dolore magna aliqua. Ut enim ad minim veniam, quis "
            + "nostrud exercitation ullamco laboris nisi ut aliquip "
            + "ex ea commodo consequat. Duis aute irure dolor in "
            + "reprehenderit in voluptate velit esse cillum dolore "
            + "eu fugiat nulla pariatur. Excepteur sint occaecat "
            + "cupidatat non proident, sunt in culpa qui officia "
            + "deserunt mollit anim id est laborum.";

        String[] words = body.split(" ");
        List<String> wrapped = new ArrayList<>();
        for (int i = 0; i < words.length; i += 8) {
            int end = Math.min(i + 8, words.length);
            wrapped.add(String.join(" ", Arrays.copyOfRange(words, i, end)));
        }

        Graphics2D g = createGraphics(img);
        g.setFont(font16);
        g.drawString("Left Column", x1, MARGIN);
        g.drawString("Right Column", x2, MARGIN);
        g.setFont(font14);
        int y = MARGIN + 30;
        for (String line : wrapped) {
            g.drawString(line, x1, y);
            g.drawString(line, x2, y);
            y += 18;
        }
        g.dispose();
        saveImagePdf(List.of(img), "two-column.pdf");
    }

    // ── 5. with-annotations.pdf ─────────────────────────────────────────

    private void makeWithAnnotations() throws IOException {
        String[] titles = {
            "Chapter 1: Getting Started",
            "Chapter 2: Configuration",
            "Chapter 3: Usage"
        };

        List<BufferedImage> imgs = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            BufferedImage img = newPage();
            Graphics2D g = createGraphics(img);
            g.setFont(font18);
            g.drawString(titles[i], MARGIN, MARGIN);
            g.setFont(font14);
            g.drawString("This is page " + (i + 1) + " of the annotated document.",
                    MARGIN, MARGIN + 40);
            g.drawString("This PDF includes bookmarks and annotations.",
                    MARGIN, MARGIN + 70);
            g.dispose();
            imgs.add(img);
        }

        Path path = OUT_DIR.resolve("with-annotations.pdf");
        try (PDDocument doc = new PDDocument()) {
            // Set document info
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Annotated Test Document");
            info.setAuthor("MrcPdf Test Suite");
            info.setSubject("Test PDF with annotations and bookmarks");
            info.setKeywords("test, annotations, bookmarks, pdf");

            List<PDPage> pages = new ArrayList<>();
            for (BufferedImage img : imgs) {
                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                pages.add(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                }
            }

            // Add text annotation (sticky note) on page 1
            PDAnnotationText textAnnot = new PDAnnotationText();
            textAnnot.setRectangle(new PDRectangle(100, H - 100, 200, 50));
            textAnnot.setContents("This is a sticky note annotation.");
            textAnnot.setOpen(false);
            pages.get(0).getAnnotations().add(textAnnot);

            // Add underline annotation on page 1
            PDAnnotationUnderline underline = new PDAnnotationUnderline();
            float[] quads = {
                MARGIN, H - MARGIN,
                MARGIN + 200, H - MARGIN,
                MARGIN, H - MARGIN - 20,
                MARGIN + 200, H - MARGIN - 20
            };
            underline.setRectangle(new PDRectangle(MARGIN, H - MARGIN - 20, 200, 20));
            underline.setQuadPoints(quads);
            underline.setContents("Underlined text");
            pages.get(0).getAnnotations().add(underline);

            // Add bookmarks (outline)
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);

            for (int i = 0; i < titles.length; i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(titles[i]);
                PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
                dest.setPage(pages.get(i));
                item.setDestination(dest);
                outline.addLast(item);
            }

            doc.save(path.toFile());
        }
        System.out.println("  with-annotations.pdf");
    }

    // ── 6. with-attachments.pdf ────────────────────────────────────────

    private void makeWithAttachments() throws IOException {
        Path path = OUT_DIR.resolve("with-attachments.pdf");
        try (PDDocument doc = new PDDocument()) {
            // Set document info
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Attachment Test Document");
            info.setAuthor("MrcPdf Test Suite");
            info.setSubject("Test PDF with embedded file attachments");

            // Add 2 pages with images
            List<PDPage> pages = new ArrayList<>();
            String[] titles = {"Page 1: With Attachments", "Page 2: Second Page"};
            for (int i = 0; i < 2; i++) {
                BufferedImage img = newPage();
                Graphics2D g = createGraphics(img);
                g.setFont(font18);
                g.drawString(titles[i], MARGIN, MARGIN);
                g.setFont(font14);
                g.drawString("This PDF contains embedded file attachments.", MARGIN, MARGIN + 40);
                g.dispose();

                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                pages.add(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                }
            }

            // Add bookmark
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            for (int i = 0; i < 2; i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(titles[i]);
                PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
                dest.setPage(pages.get(i));
                item.setDestination(dest);
                outline.addLast(item);
            }

            // Add 2 embedded files
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            doc.getDocumentCatalog().setNames(names);

            PDEmbeddedFilesNameTreeNode embeddedFilesNode = new PDEmbeddedFilesNameTreeNode();
            names.setEmbeddedFiles(embeddedFilesNode);

            // Embedded file 1: text file
            PDEmbeddedFile ef1 = new PDEmbeddedFile(doc);
            ef1.setSubtype("text/plain");
            PDComplexFileSpecification spec1 = new PDComplexFileSpecification();
            spec1.setFile("readme.txt");
            spec1.setEmbeddedFile(ef1);
            try (var os = ef1.createOutputStream()) {
                os.write("This is an embedded text file for testing attachment preservation.".getBytes(StandardCharsets.UTF_8));
            }

            // Embedded file 2: small data file
            PDEmbeddedFile ef2 = new PDEmbeddedFile(doc);
            ef2.setSubtype("application/octet-stream");
            PDComplexFileSpecification spec2 = new PDComplexFileSpecification();
            spec2.setFile("data.bin");
            spec2.setEmbeddedFile(ef2);
            try (var os = ef2.createOutputStream()) {
                os.write("binary test data here".getBytes(StandardCharsets.UTF_8));
            }

            java.util.Map<String, PDComplexFileSpecification> fileMap = new java.util.LinkedHashMap<>();
            fileMap.put("readme.txt", spec1);
            fileMap.put("data.bin", spec2);
            embeddedFilesNode.setNames(fileMap);

            doc.save(path.toFile());
        }
        System.out.println("  with-attachments.pdf");
    }

    // ── 7. noisy-scan.pdf ───────────────────────────────────────────────

    private void makeNoisyScan() throws IOException {
        BufferedImage img = newPage();
        Graphics2D g = createGraphics(img);
        g.setFont(font14);

        String[] lines = {
            "This page simulates a noisy scanned document.",
            "It includes speckles, uneven lighting, and",
            "slight rotation to test image preprocessing.",
        };
        int y = MARGIN;
        for (String line : lines) {
            g.drawString(line, MARGIN, y);
            y += LINE_HEIGHT;
        }
        g.dispose();

        // Add noise and gradient directly on the raster
        Random rnd = new Random(42);
        int[] pixels = new int[W * H];
        img.getRGB(0, 0, W, H, pixels, 0, W);

        // Add speckle noise
        for (int i = 0; i < 3000; i++) {
            int x = rnd.nextInt(W);
            int y2 = rnd.nextInt(H);
            int v = rnd.nextInt(61);
            pixels[y2 * W + x] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }

        // Add gradient (darkening toward bottom)
        for (int y2 = 0; y2 < H; y2++) {
            int shade = (int) (30 * ((double) y2 / H));
            for (int x = 0; x < W; x++) {
                int rgb = pixels[y2 * W + x];
                int r = Math.max(0, ((rgb >> 16) & 0xFF) - shade);
                int g2 = Math.max(0, ((rgb >> 8) & 0xFF) - shade);
                int b = Math.max(0, (rgb & 0xFF) - shade);
                pixels[y2 * W + x] = (0xFF << 24) | (r << 16) | (g2 << 8) | b;
            }
        }

        img.setRGB(0, 0, W, H, pixels, 0, W);
        saveImagePdf(List.of(img), "noisy-scan.pdf");
    }

    // ── 8. scanned-text.pdf ─────────────────────────────────────────────
    // Simulates a scanned PDF that already carries a searchable text layer
    // (the shape of TrulyFreeOCR's "--no-mrc" output): each page is a
    // grayscale page image with invisible text overlaid on top.  The MRC
    // pipeline must compress the background while preserving the text layer.

    // Each page is a heading followed by paragraphs that wrap to fill the
    // usable page area; shortfall is topped up from SCANNED_FILLER so every
    // page looks like a densely printed scanned document.

    private static final int MAX_LINES = (H - 2 * MARGIN) / LINE_HEIGHT;

    private static final String[][] SCANNED_PAGE_CONTENT = {
        {
            "Page 1: Introduction",
            "This is a simulated scanned page with a searchable text layer. The page background is a grayscale image, like a real scan, and the searchable text is overlaid invisibly on top of the image.",
            "The MRC pipeline must preserve this text layer while compressing the scanned background into a JPEG layer plus a JBIG2 mask, so that the output PDF stays fully searchable.",
            "The pages that follow mimic the shape of a typical digitized archive document: dense body text, section headings, paragraph spacing, and a page footer.",
        },
        {
            "Page 2: Document structure",
            "A typical scanned document mixes headings, body text, and page footers. The invisible text layer captures all of them so that search and selection behave like a born-digital PDF.",
            "Headings are usually a bit larger and darker than body text, and the foreground mask keeps them pixel-sharp in the output. Footers often carry page numbers and document titles.",
            "Every word on the page, from the first heading to the last footer, must survive the round trip through the compressor without any loss.",
        },
        {
            "Page 3: Compression layers",
            "Mixed Raster Content splits each page into three layers. The background is a downsampled, smoothed JPEG image that carries the smooth gradients and the paper texture.",
            "The foreground is a binary mask of the ink pixels, encoded with JBIG2 or CCITT G4 and drawn over the background. The third layer is the searchable text, rendered invisibly.",
            "The three layers combine into one visually faithful page, which is how the format achieves large compression ratios without visible artifacts.",
        },
        {
            "Page 4: The foreground mask",
            "The mask stores only the dark pixels of the glyphs, so it reproduces the original text resolution exactly. JBIG2 groups identical symbol shapes into a shared dictionary.",
            "That symbol dictionary is what makes multi-page scans compress so well: symbols seen once are stored once and referenced again wherever they recur across the document.",
            "Repeated letters, common words, and recurring page furniture across many pages therefore cost almost nothing extra in the final file size.",
        },
        {
            "Page 5: The background image",
            "After the ink is removed, the remaining page is mostly smooth paper, faint images, and shaded regions. Downscaling it by a factor of three removes high frequency detail the eye cannot notice.",
            "JPEG at chroma subsampling then handles the color efficiently, and mild Gaussian smoothing removes any halos left around the text pixels before the image is encoded.",
            "Small files and sharp text are not in conflict: the mask keeps the glyphs crisp while the background carries only the paper that surrounds them.",
        },
        {
            "Page 6: Searchable text layer",
            "The text layer repositions every word from the source PDF at its original coordinates, rendered with a single font in RenderingMode.NEITHER so it never shows on screen.",
            "Search, copy, and text selection all read this layer even though nothing is painted on the page. CJK documents automatically fall back to a bundled TrueType font when non-Latin characters are detected.",
            "Search works exactly as it did in the source document, which keeps the archive useful for retrieval and reference long after the images are compressed.",
        },
        {
            "Page 7: Metadata preservation",
            "Document information, bookmarks, annotations, and embedded file attachments are deep-copied into the output document. Outline entries that point at pages keep their destinations so navigation still jumps to the right place.",
            "The page size and orientation come from the source crop box, keeping every page exactly the same size as before. Nothing important is lost during recompression.",
            "Metadata is carried through the entire pipeline, so the compressed archive remains a drop-in replacement for the original scan.",
        },
        {
            "Page 8: Multi-page behavior",
            "Long documents are processed page by page, so memory use stays bounded even for large scans. Rendering runs on one thread while segmentation and text extraction fan out across a small worker pool.",
            "Results are reassembled in order, and the JBIG2 symbol dictionary is built from every page at once, which is what makes the shared symbols so effective.",
            "A hundred page scan finishes in minutes, not hours, and each page occupies a predictable slice of the total processing time.",
        },
        {
            "Page 9: When the source has no text",
            "If the source PDF is a raw scan with no text layer at all, the page is still MRC compressed but no invisible text is added, because this tool does not run OCR.",
            "For scans without text, run an OCR step first and then feed the searchable result into this compressor. The compressed output then keeps both the sharp mask and the words that were recognized earlier.",
            "Compression and searchability are separate concerns, and each is handled by the tool that is best suited to it.",
        },
        {
            "Page 10: Summary",
            "This fixture exercises the full pipeline on ten pages of grayscale scan content with a searchable text layer, filling each page edge to edge like a real digitized document.",
            "Every word on these pages must be preserved in the output, and the total file size should shrink noticeably thanks to MRC layering, JBIG2 symbol sharing, and JPEG background.",
            "If all ten pages round trip correctly, the compressor is behaving like a production tool on a representative document.",
        },
    };

    private static final String[] SCANNED_FILLER = {
        "The archive contains hundreds of such pages, each digitized from the same paper stock with the same scanner settings.",
        "Consistent capture settings make the compression ratio more predictable across the whole document set.",
        "Batching and parallel processing keep the throughput high even for very large digitization projects.",
        "Rendered at the original resolution, the compressed pages are visually indistinguishable from the source scans.",
        "The file size difference between the original and the compressed archive is most visible on scans with dense text.",
        "Most users never notice the compression, because the text remains sharp and the layout remains unchanged.",
        "Quality checks sample a few pages from each batch to confirm that nothing was lost during recompression.",
        "Long term storage benefits from smaller files, which lower both disk usage and network transfer costs.",
        "The workflow is fully scriptable, so new scans can be added to the archive with a single command.",
        "Documentation and audit trails record the exact settings used for every batch of scans.",
    };

    private List<List<String>> buildScannedPages() {
        BufferedImage meas = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = meas.createGraphics();
        g.setFont(font14);
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < SCANNED_PAGE_CONTENT.length; i++) {
            String[] content = SCANNED_PAGE_CONTENT[i];
            List<String> lines = new ArrayList<>();
            lines.add(content[0]);
            for (String para : Arrays.copyOfRange(content, 1, content.length)) {
                lines.add("");
                wrapInto(para, W - 2 * MARGIN, g, lines);
            }
            int fi = 0;
            while (fi < SCANNED_FILLER.length) {
                List<String> filler = new ArrayList<>();
                wrapInto(SCANNED_FILLER[fi], W - 2 * MARGIN, g, filler);
                if (lines.size() + filler.size() + 1 > MAX_LINES) break;
                lines.addAll(filler);
                fi++;
            }
            lines.add("Page " + (i + 1) + " of " + SCANNED_PAGE_CONTENT.length);
            pages.add(lines);
        }
        g.dispose();
        return pages;
    }

    private static void wrapInto(String text, int maxWidth, Graphics2D g, List<String> out) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = sb.length() == 0 ? word : sb + " " + word;
            if (g.getFontMetrics().stringWidth(test) <= maxWidth || sb.length() == 0) {
                sb.setLength(0);
                sb.append(test);
            } else {
                out.add(sb.toString());
                sb.setLength(0);
                sb.append(word);
            }
        }
        if (sb.length() > 0) out.add(sb.toString());
    }

    private void makeScannedText() throws IOException {
        List<List<String>> pages = buildScannedPages();

        Path path = OUT_DIR.resolve("scanned-text.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (List<String> lines : pages) {
                // 1. Grayscale "scan" background
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, W, H);
                g.setColor(Color.BLACK);
                g.setFont(font14);
                int y = MARGIN;
                for (String line : lines) {
                    if (!line.isEmpty()) g.drawString(line, MARGIN, y);
                    y += LINE_HEIGHT;
                }
                g.dispose();

                // 2. Page with the scan image + invisible searchable text layer
                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.setRenderingMode(RenderingMode.NEITHER);
                    y = MARGIN;
                    for (String line : lines) {
                        if (!line.isEmpty()) {
                            cs.setTextMatrix(Matrix.getTranslateInstance(MARGIN, H - y));
                            cs.showText(line);
                        }
                        y += LINE_HEIGHT;
                    }
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
        System.out.println("  scanned-text.pdf");
    }

    // ── 9. all-features.pdf ─────────────────────────────────────────────
    // One fixture that combines every preserved feature at once: dense
    // scanned pages with an invisible searchable text layer, a nested
    // bookmarks outline, sticky-note and underline annotations on several
    // pages, and embedded file attachments.

    private void makeAllInOne() throws IOException {
        List<List<String>> pages = buildScannedPages();

        Path path = OUT_DIR.resolve("all-features.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("All Features Test Document");
            info.setAuthor("MrcPdf Test Suite");
            info.setSubject("Test PDF with text, bookmarks, annotations and attachments");
            info.setKeywords("test, text, bookmarks, annotations, attachments, pdf");

            List<PDPage> pdPages = new ArrayList<>();
            for (List<String> lines : pages) {
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, W, H);
                g.setColor(Color.BLACK);
                g.setFont(font14);
                int y = MARGIN;
                for (String line : lines) {
                    if (!line.isEmpty()) g.drawString(line, MARGIN, y);
                    y += LINE_HEIGHT;
                }
                g.dispose();

                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                pdPages.add(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.setRenderingMode(RenderingMode.NEITHER);
                    y = MARGIN;
                    for (String line : lines) {
                        if (!line.isEmpty()) {
                            cs.setTextMatrix(Matrix.getTranslateInstance(MARGIN, H - y));
                            cs.showText(line);
                        }
                        y += LINE_HEIGHT;
                    }
                    cs.endText();
                }
            }

            // Nested bookmarks outline: 2 parts, each with 5 chapter children
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            String[] partNames = {"Part I: Foundations", "Part II: In Practice"};
            for (int p = 0; p < partNames.length; p++) {
                PDOutlineItem part = new PDOutlineItem();
                part.setTitle(partNames[p]);
                outline.addLast(part);
                for (int c = 0; c < 5; c++) {
                    int pageIndex = p * 5 + c;
                    PDOutlineItem chapter = new PDOutlineItem();
                    chapter.setTitle("Chapter " + (pageIndex + 1));
                    PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
                    dest.setPage(pdPages.get(pageIndex));
                    chapter.setDestination(dest);
                    part.addLast(chapter);
                }
            }

            // Annotations: sticky notes and an underline spread across pages
            PDAnnotationText note1 = new PDAnnotationText();
            note1.setRectangle(new PDRectangle(100, H - 100, 200, 50));
            note1.setContents("Sticky note on page 1.");
            note1.setOpen(false);
            pdPages.get(0).getAnnotations().add(note1);

            PDAnnotationUnderline underline = new PDAnnotationUnderline();
            float[] quads = {
                MARGIN, H - MARGIN,
                MARGIN + 200, H - MARGIN,
                MARGIN, H - MARGIN - 20,
                MARGIN + 200, H - MARGIN - 20
            };
            underline.setRectangle(new PDRectangle(MARGIN, H - MARGIN - 20, 200, 20));
            underline.setQuadPoints(quads);
            underline.setContents("Underlined text on page 1.");
            pdPages.get(0).getAnnotations().add(underline);

            for (int idx : new int[] {4, 7}) {
                PDAnnotationText note = new PDAnnotationText();
                note.setRectangle(new PDRectangle(100, H - 100, 200, 50));
                note.setContents("Sticky note on page " + (idx + 1) + ".");
                note.setOpen(false);
                pdPages.get(idx).getAnnotations().add(note);
            }

            // Embedded file attachments
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            doc.getDocumentCatalog().setNames(names);
            PDEmbeddedFilesNameTreeNode embeddedFilesNode = new PDEmbeddedFilesNameTreeNode();
            names.setEmbeddedFiles(embeddedFilesNode);

            java.util.Map<String, PDComplexFileSpecification> fileMap = new java.util.LinkedHashMap<>();
            String[][] attachments = {
                {"readme.txt", "text/plain", "This is an embedded text file for testing attachment preservation."},
                {"data.bin", "application/octet-stream", "binary test data here"},
                {"notes.txt", "text/plain", "Additional attachment: page notes for the all-features fixture."},
            };
            for (String[] att : attachments) {
                PDEmbeddedFile ef = new PDEmbeddedFile(doc);
                ef.setSubtype(att[1]);
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile(att[0]);
                spec.setEmbeddedFile(ef);
                try (var os = ef.createOutputStream()) {
                    os.write(att[2].getBytes(StandardCharsets.UTF_8));
                }
                fileMap.put(att[0], spec);
            }
            embeddedFilesNode.setNames(fileMap);

            doc.save(path.toFile());
        }
        System.out.println("  all-features.pdf");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TestPdfGenerator()).execute(args);
        System.exit(exitCode);
    }
}
