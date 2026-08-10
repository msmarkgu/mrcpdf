package com.mrcpdf.pipeline;

import java.io.File;
import java.io.IOException;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public final class FontResolver {

  private FontResolver() {}

  public static PDFont resolveFont(PDDocument output, File fontFile, PDFont defaultFont) {
    if (fontFile != null && fontFile.exists()) {
      PDFont loaded = loadFont(output, fontFile);
      if (loaded != null) return loaded;
    }
    return defaultFont;
  }

  public static PDFont loadFont(PDDocument output, File file) {
    try {
      String name = file.getName().toLowerCase();
      if (name.endsWith(".ttc")) {
        try (TrueTypeCollection ttc = new TrueTypeCollection(file)) {
          final TrueTypeFont[] firstFont = new TrueTypeFont[1];
          ttc.processAllFonts(
              font -> {
                if (firstFont[0] == null) firstFont[0] = font;
              });
          if (firstFont[0] != null) {
            return PDType0Font.load(output, firstFont[0], true);
          }
        }
      } else {
        return PDType0Font.load(output, file);
      }
    } catch (IOException e) {
      System.out.printf(
          "  Warning: could not load font '%s' — may be OTF/CFF (unsupported). Using built-in Helvetica instead.%n",
          file.getName());
    }
    return null;
  }
}
