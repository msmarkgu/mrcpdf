package com.mrcpdf.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks temporary files created during a pipeline run and provides
 * a single cleanup() call to delete them all.
 *
 * Callers are responsible for invoking {@link #cleanup()} (e.g. from a
 * finally block); files are not registered with deleteOnExit() so the
 * class stays safe for long-running processes.
 */
public class TempFileManager {

    private final List<File> tempFiles = new ArrayList<>();

    /**
     * Creates a temp file and registers it for cleanup.
     *
     * @param prefix  File name prefix (e.g. "mrcpdf-").
     * @param suffix  File name suffix (e.g. ".png").
     * @return The created temporary File.
     */
    public File createTempFile(String prefix, String suffix) throws IOException {
        Files.createDirectories(Path.of("temp"));
        File f = Files.createTempFile(Path.of("temp"), prefix, suffix).toFile();
        tempFiles.add(f);
        return f;
    }

    /**
     * Deletes all tracked temp files and clears the internal list.
     */
    public void cleanup() {
        for (File f : tempFiles) {
            if (f.exists()) f.delete();
        }
        tempFiles.clear();
    }
}
