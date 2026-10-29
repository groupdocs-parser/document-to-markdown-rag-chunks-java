package com.groupdocs.demo;

// Topic: Turn DOCX, PDF, email and ZIP files into heading-scoped Markdown chunks for RAG.
// Uses GroupDocs.Parser for Java: Markdown output, a plain-text fallback, and container
// items (email attachments, archive members) parsed as child documents. No cloud calls.

import com.groupdocs.parser.Parser;
import com.groupdocs.parser.data.ContainerItem;
import com.groupdocs.parser.data.TextReader;
import com.groupdocs.parser.exceptions.UnsupportedDocumentFormatException;
import com.groupdocs.parser.licensing.License;
import com.groupdocs.parser.options.FormattedTextMode;
import com.groupdocs.parser.options.FormattedTextOptions;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RagChunksDemo {

    private static final String DOCS = "resources";
    private static final String RESULT = "output";
    private static final int MAX_CHUNK_CHARS = 1500;
    private static final int MAX_CONTAINER_DEPTH = 2;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** One retrievable piece of text plus the lineage a RAG index needs to cite it. */
    static final class Chunk {
        final String id;
        final String source;
        final String parent;
        final String headingPath;
        final String format;
        final String text;

        Chunk(String id, String source, String parent, String headingPath,
              String format, String text) {
            this.id = id;
            this.source = source;
            this.parent = parent;
            this.headingPath = headingPath;
            this.format = format;
            this.text = text;
        }
    }

    /** Text pulled from one document and the mode it came out in. */
    static final class ExtractedText {
        final String format;
        final String text;

        ExtractedText(String format, String text) {
            this.format = format;
            this.text = text;
        }
    }

    public static void main(String[] args) throws Exception {
        new File(DOCS).mkdirs();
        new File(RESULT).mkdirs();
        applyLicense();

        File[] files = new File(DOCS).listFiles(File::isFile);
        if (files == null || files.length == 0) {
            System.err.println("No input documents in " + new File(DOCS).getAbsolutePath());
            System.exit(1);
        }
        Arrays.sort(files);

        List<Chunk> chunks = new ArrayList<>();
        for (File file : files) {
            chunks.addAll(chunkDocument(file.getPath()));
        }

        Map<String, Integer> perSource = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            perSource.merge(chunk.source + " [" + chunk.format + "]", 1, Integer::sum);
        }
        perSource.forEach((source, count) ->
                System.out.println(count + " chunks <- " + source));

        String output = Paths.get(RESULT, "chunks.jsonl").toString();
        writeJsonl(chunks, output);
        System.out.println("Total chunks: " + chunks.size());
        System.out.println("Written: " + new File(output).getAbsolutePath());

        System.exit(chunks.isEmpty() ? 2 : 0);
    }

    private static void applyLicense() {
        // Point this at your .lic file to remove evaluation limits.
        // Get a free temporary licence: https://purchase.groupdocs.com/temporary-license
        String licensePath = "REPLACE_WITH_YOUR_LICENSE_PATH";
        if (new File(licensePath).exists()) {
            new License().setLicense(licensePath);
            System.out.println("[license] applied");
        } else {
            System.out.println("[license] no licence set - running in evaluation mode");
        }
    }

    /**
     * Opens one file and returns every chunk it yields, attachments and archive members
     * included.
     * @remarks Creates a Parser for the path and hands it to chunkParser with the file name
     *          as the source id. A format GroupDocs.Parser cannot open raises
     *          UnsupportedDocumentFormatException; the file is reported and skipped so one
     *          odd upload never stops a batch ingestion run.
     */
    static List<Chunk> chunkDocument(String path) {
        String sourceId = new File(path).getName();
        try (Parser parser = new Parser(path)) {
            return chunkParser(parser, sourceId, null, 0);
        } catch (UnsupportedDocumentFormatException ex) {
            System.out.println("skipped (unsupported format): " + sourceId);
            return new ArrayList<>();
        }
    }

    /**
     * Chunks the text of an open document, then recurses into the files it contains.
     * @remarks Calls extractMarkdown for the document body and chunkByHeadings to cut it
     *          into sections. Email attachments and ZIP members are read by
     *          chunkContainerItems and keep the parent id, so a hit inside an attachment
     *          can still be traced back to the message it came from. Recursion stops at
     *          MAX_CONTAINER_DEPTH levels.
     */
    static List<Chunk> chunkParser(Parser parser, String sourceId, String parentId,
                                   int depth) {
        ExtractedText extracted = extractMarkdown(parser);
        List<Chunk> chunks =
                chunkByHeadings(extracted.text, sourceId, parentId, extracted.format);
        if (depth < MAX_CONTAINER_DEPTH) {
            chunks.addAll(chunkContainerItems(parser, sourceId, depth));
        }
        return chunks;
    }

    /**
     * Extracts the document body as Markdown, falling back to plain text.
     * @remarks Checks Features.isFormattedText and calls getFormattedText with
     *          FormattedTextOptions(FormattedTextMode.Markdown), which keeps headings,
     *          lists and tables as Markdown - the structure the chunker splits on. When a
     *          format has no formatted-text support (or the reader is null) it falls back
     *          to getText. Images without OCR return no text at all and are marked "none".
     */
    static ExtractedText extractMarkdown(Parser parser) {
        if (parser.getFeatures().isFormattedText()) {
            FormattedTextOptions options =
                    new FormattedTextOptions(FormattedTextMode.Markdown);
            try (TextReader reader = parser.getFormattedText(options)) {
                if (reader != null) {
                    return new ExtractedText("markdown", reader.readToEnd());
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        try (TextReader reader = parser.getText()) {
            if (reader != null) {
                return new ExtractedText("plain", reader.readToEnd());
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return new ExtractedText("none", "");
    }

    /**
     * Splits Markdown into chunks at headings and records the heading path of each chunk.
     * @remarks Walks the text line by line. Every "#" to "######" heading closes the
     *          current chunk and updates a heading stack, so each chunk carries a path such
     *          as "Chapter 2 > Pricing" that a retriever can show or filter on. Sections
     *          longer than MAX_CHUNK_CHARS are cut at paragraph breaks; blank sections are
     *          dropped.
     */
    static List<Chunk> chunkByHeadings(String markdown, String sourceId, String parentId,
                                       String format) {
        List<Chunk> chunks = new ArrayList<>();
        String[] stack = new String[6];
        StringBuilder section = new StringBuilder();
        String path = "";

        for (String line : markdown.split("\\r?\\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                addSection(chunks, section.toString(), sourceId, parentId, path, format);
                section.setLength(0);
                int level = heading.group(1).length();
                stack[level - 1] = heading.group(2).trim();
                for (int i = level; i < stack.length; i++) {
                    stack[i] = null;
                }
                path = joinPath(stack);
            }
            section.append(line).append('\n');
        }
        addSection(chunks, section.toString(), sourceId, parentId, path, format);
        return chunks;
    }

    /**
     * Opens every attachment or archive member as a child document and chunks it.
     * @remarks getContainer returns the attachments of an EML or MSG message and the
     *          entries of a ZIP archive, or null when the format is not a container. Each
     *          ContainerItem is opened with openParser and processed by chunkParser with
     *          the parent's id, so the chunk id reads "message.eml/report.pdf#3". Members
     *          Parser cannot read are skipped and reported.
     */
    static List<Chunk> chunkContainerItems(Parser parser, String sourceId, int depth) {
        List<Chunk> chunks = new ArrayList<>();
        Iterable<ContainerItem> items = parser.getContainer();
        if (items == null) {
            return chunks;
        }
        for (ContainerItem item : items) {
            String childId = sourceId + "/" + item.getFilePath();
            try (Parser child = item.openParser()) {
                chunks.addAll(chunkParser(child, childId, sourceId, depth + 1));
            } catch (UnsupportedDocumentFormatException ex) {
                System.out.println("skipped (unsupported format): " + childId);
            }
        }
        return chunks;
    }

    /**
     * Writes the chunks as JSON Lines, one object per line, ready for an embedding job.
     * @remarks Each line holds id, source, parent, heading_path, format and text. JSON
     *          Lines streams well into vector-store loaders and keeps the sample free of a
     *          JSON library; strings are escaped by hand and the file is written as UTF-8.
     */
    static void writeJsonl(List<Chunk> chunks, String outputPath) throws IOException {
        try (Writer out = Files.newBufferedWriter(Paths.get(outputPath),
                StandardCharsets.UTF_8)) {
            for (Chunk c : chunks) {
                out.write("{\"id\":" + json(c.id)
                        + ",\"source\":" + json(c.source)
                        + ",\"parent\":" + json(c.parent)
                        + ",\"heading_path\":" + json(c.headingPath)
                        + ",\"format\":" + json(c.format)
                        + ",\"text\":" + json(c.text) + "}\n");
            }
        }
    }

    private static void addSection(List<Chunk> chunks, String section, String sourceId,
                                   String parentId, String path, String format) {
        StringBuilder piece = new StringBuilder();
        for (String paragraph : section.split("\\n\\s*\\n")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (piece.length() > 0 && piece.length() + p.length() > MAX_CHUNK_CHARS) {
                addChunk(chunks, piece.toString(), sourceId, parentId, path, format);
                piece.setLength(0);
            }
            if (piece.length() > 0) {
                piece.append("\n\n");
            }
            piece.append(p);
        }
        addChunk(chunks, piece.toString(), sourceId, parentId, path, format);
    }

    private static void addChunk(List<Chunk> chunks, String text, String sourceId,
                                 String parentId, String path, String format) {
        String body = text.trim();
        if (body.isEmpty() || HEADING.matcher(body).matches()) {
            return;
        }
        String id = sourceId + "#" + chunks.size();
        chunks.add(new Chunk(id, sourceId, parentId, path, format, body));
    }

    private static String joinPath(String[] stack) {
        StringBuilder sb = new StringBuilder();
        for (String title : stack) {
            if (title != null) {
                sb.append(sb.length() == 0 ? "" : " > ").append(title);
            }
        }
        return sb.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
