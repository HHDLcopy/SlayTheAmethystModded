package io.stamethyst.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Utility for writing files atomically with fsync to ensure durability.
 * Write pattern: temp file → fsync → atomic rename → fsync parent directory.
 */
final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    /**
     * Atomically replaces targetFile with content from writer.
     * If targetFile exists, it will be deleted only after the new content is durable.
     *
     * @param targetFile final destination
     * @param writer callback that writes content to the provided FileOutputStream
     * @throws IOException if write or fsync fails
     */
    static void write(File targetFile, ContentWriter writer) throws IOException {
        File tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }

        FileOutputStream output = null;
        boolean success = false;
        try {
            output = new FileOutputStream(tempFile, false);
            writer.write(output);
            output.getFD().sync();
            output.close();
            output = null;

            if (targetFile.isFile() && !targetFile.delete()) {
                throw new IOException("Failed to delete old file: " + targetFile.getAbsolutePath());
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("Failed to rename temp file into place: " + targetFile.getAbsolutePath());
            }

            fsyncDirectory(parent);
            success = true;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            if (!success && tempFile.isFile()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Fsyncs a directory to make directory entry changes durable.
     * Best-effort on platforms where directory fsync is not supported.
     */
    private static void fsyncDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ);
            channel.force(true);
        } catch (Throwable ignored) {
            // Directory fsync is not portable; silently ignore failures.
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    interface ContentWriter {
        void write(FileOutputStream output) throws IOException;
    }
}
