# Heading-Scoped Markdown Chunks for RAG

[![Product Page](https://img.shields.io/badge/Product%20Page-2865E0?style=for-the-badge&logo=appveyor&logoColor=white)](https://github.com/groupdocs-parser/GroupDocs.Parser-Docs)
[![Docs](https://img.shields.io/badge/Docs-2865E0?style=for-the-badge&logo=Hugo&logoColor=white)](https://docs.groupdocs.com/parser/java/)
[![Blog](https://img.shields.io/badge/Blog-2865E0?style=for-the-badge&logo=WordPress&logoColor=white)](https://blog.groupdocs.com/categories/groupdocs.parser-product-family/)
[![Free Support](https://img.shields.io/badge/Free%20Support-2865E0?style=for-the-badge&logo=Discourse&logoColor=white)](https://forum.groupdocs.com/c/parser/17)
[![Temporary License](https://img.shields.io/badge/Temporary%20License-2865E0?style=for-the-badge&logo=rocket&logoColor=white)](https://purchase.groupdocs.com/temp-license/100326)

## Overview

document-to-markdown-rag-chunks-java is a runnable Maven project that reads Word, PDF, email and ZIP files with GroupDocs.Parser for Java 26.10 and writes heading-scoped Markdown chunks, one JSON object per line, for a retrieval-augmented generation (RAG) index. Every chunk records where it came from: the source file, the parent container when it was an attachment or archive member, and the heading path above it. The whole run happens inside one JVM, so no document is uploaded to a parsing service. It targets Java developers building document parsing for RAG who want an on-premise document parser in front of whatever embedding job or vector store they already use.

## Technology Stack

- **Platform**: Java, compiled for Java 11 by the bundled `pom.xml` (the source uses Java 8 language features only)
- **Product**: GroupDocs.Parser for Java 26.10, from the `https://releases.groupdocs.com/java/repo/` Maven repository
- **Language**: Java
- **Build**: Maven with `exec-maven-plugin` 3.1.0; no other runtime dependency, not even a JSON library

## Problem Statement

A RAG pipeline is only as good as the text it embeds. Feed it the output of a plain-text extractor and a 40-page policy document arrives as one undifferentiated stream: the heading that said "Refund rules" is gone, the table that followed it is flattened into spaced words, and the retriever has no way to tell a chunk from chapter 2 apart from a chunk from the appendix. Fixed-size token windows make it worse, because they cut through the middle of a section and glue the end of one topic to the start of the next.

Business files add a second problem. An email is rarely the interesting part on its own; the contract or spreadsheet attached to it is. A ZIP upload can hold a dozen documents of different formats. If the ingestion job only reads the top-level file, the attachments never reach the index, and if it unpacks them without bookkeeping, a search hit inside `attachment.txt` cannot be traced back to the message that carried it.

Ask around for a parser and the suggestions tend to be Python libraries or metered SaaS endpoints. Neither drops cleanly into a Java service that is not allowed to send files off its own servers.

## Solution Overview

GroupDocs.Parser for Java addresses these challenges by returning document text as Markdown and by exposing email attachments and archive entries as child documents that open with the same `Parser` API. The sample adds about 60 lines of chunking logic on top. Key technical features include:

- **Markdown output**: `getFormattedText(new FormattedTextOptions(FormattedTextMode.Markdown))` keeps headings, lists and tables, which is what makes LLM-ready Markdown possible.
- **Feature probing with fallback**: `parser.getFeatures().isFormattedText()` decides between Markdown and `getText()`, so a PDF still produces chunks in plain text.
- **Heading-path chunking**: a six-level heading stack turns `#` to `######` lines into paths such as `Heading 1 > Heading 1.2 > Heading 1.2.1`.
- **Container recursion**: `getContainer()` and `ContainerItem.openParser()` read EML attachments and ZIP members up to two levels deep, with the parent id kept on every chunk.
- **JSON Lines output**: `id`, `source`, `parent`, `heading_path`, `format` and `text` per line, readable by any loader.

## Prerequisites

Before running these examples, ensure you have:

- **JDK** – version 11 or later for the `pom.xml` as shipped; lower `maven.compiler.source` and `target` to 8 if you must build on Java 8
- **Maven** – 3.x, with network access to `releases.groupdocs.com` for the first build
- **Licence** – optional; without one the library runs in evaluation mode, and a [temporary licence](https://purchase.groupdocs.com/temp-license/100326) removes the limits
- **Input files** – the four samples in `resources/`, or your own files dropped into that folder

## Getting Started

### Installation

**Using Package Manager:**

```bash
mvn compile exec:java
```

**Manual Installation:**

The only dependency is `com.groupdocs:groupdocs-parser:26.10`, declared in `pom.xml` together with the GroupDocs repository URL. Run the command from the project root: the demo reads `resources/` and writes `output/` relative to the working directory.

### Configuration

1. Open `RagChunksDemo.java` and replace `REPLACE_WITH_YOUR_LICENSE_PATH` in `applyLicense()` with the path to your `.lic` file, or leave it to run in evaluation mode.
2. Adjust `MAX_CHUNK_CHARS` (1500 by default) to the chunk size your embedding model prefers.
3. Adjust `MAX_CONTAINER_DEPTH` (2 by default) if you expect archives inside attachments inside archives.

## Repository Structure

```
document-to-markdown-rag-chunks-java/
│
├── README.md
├── pom.xml
├── resources/
│   ├── SampleWithToc.docx
│   ├── email-with-attachment.eml
│   ├── sample.pdf
│   └── sample.zip
└── src/
    └── main/
        └── java/
            └── com/
                └── groupdocs/
                    └── demo/
                        └── RagChunksDemo.java
```

### File Descriptions

- `README.md` – this guide.
- `pom.xml` – Maven build with the GroupDocs repository, the GroupDocs.Parser 26.10 dependency and the exec plugin pointed at `RagChunksDemo`.
- `resources/SampleWithToc.docx` – Word file with nested headings; it comes out as Markdown with three-level heading paths.
- `resources/email-with-attachment.eml` – MIME message carrying `attachment.txt`, used to show parent lineage.
- `resources/sample.pdf` – PDF input that falls back to plain text.
- `resources/sample.zip` – archive with a DOCX, two PDFs and two JPEG images; the images yield no text without OCR.
- `src/main/java/com/groupdocs/demo/RagChunksDemo.java` – the whole pipeline: the six documented methods, two small value classes and the JSON escaping helper.

## Code Implementation

On the four bundled inputs the run writes 22 chunks to `output/chunks.jsonl`. DOCX and EML content is recorded as `markdown`, the PDF as `plain`.

### Implementation: Opens one file and returns every chunk it yields, attachments and archive members included

`chunkDocument` is the entry point you call once per uploaded file. It opens a `Parser` in try-with-resources, uses the bare file name as the source id and passes `null` as the parent, because a top-level file has none. A format the library cannot open raises `UnsupportedDocumentFormatException`; the method reports it and returns an empty list, so one odd upload never stops a batch run.

```java
String sourceId = new File(path).getName();
try (Parser parser = new Parser(path)) {
    return chunkParser(parser, sourceId, null, 0);
} catch (UnsupportedDocumentFormatException ex) {
    System.out.println("skipped (unsupported format): " + sourceId);
    return new ArrayList<>();
}
```

#### Technical Details

The try-with-resources block closes the native file handle even when chunking throws further down. Catching only `UnsupportedDocumentFormatException` is deliberate: an `IOException` from a broken disk should still fail loudly.

**Key Components:**
- `Parser(String)`: opens the document from a path.
- `UnsupportedDocumentFormatException`: signals a format outside the supported list.

**Parameters:** `path`: file system path of one input document.

**Output:** a `List<Chunk>` for the file and everything inside it, or an empty list plus one console line for an unsupported file.

---

### Implementation: Chunks the text of an open document, then recurses into the files it contains

`chunkParser` is the recursive core. It extracts the body, cuts it into heading-scoped chunks, and then asks the same parser for contained files. Because it takes an already open `Parser`, it serves both top-level files and container items without duplicating code.

```java
ExtractedText extracted = extractMarkdown(parser);
List<Chunk> chunks =
        chunkByHeadings(extracted.text, sourceId, parentId, extracted.format);
if (depth < MAX_CONTAINER_DEPTH) {
    chunks.addAll(chunkContainerItems(parser, sourceId, depth));
}
return chunks;
```

#### Technical Details

The depth guard stops at two levels, which covers "ZIP inside an email" without letting a crafted archive recurse forever. Two is a judgement call on my side; raise the constant if your exports nest deeper. The body chunks come first in the list, followed by the chunks of each child, so the JSON Lines file reads in document order.

**Key Components:**
- `extractMarkdown`: picks Markdown or plain text.
- `chunkContainerItems`: walks attachments and archive members.

**Parameters:** `parser`, `sourceId`, `parentId`, `depth`: the open document, its id, its container's id (or `null`) and the current nesting level.

**Output:** body chunks followed by child chunks.

---

### Implementation: Extracts the document body as Markdown, falling back to plain text

This is where the format decision happens. `getFeatures().isFormattedText()` reports whether the format supports formatted text; when it does, `getFormattedText` with `FormattedTextMode.Markdown` returns headings, lists and tables as Markdown. Otherwise the method falls back to `getText()`, and when even that returns `null`, as it does for an image with no OCR, it marks the result `none` with empty text.

```java
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
```

#### Technical Details

Recording the mode in `format` matters downstream: a retriever can boost `markdown` chunks or treat `plain` chunks as unstructured. Both readers are closed by try-with-resources, and the null checks follow the documented contract that an unsupported extraction returns `null` rather than throwing.

**Key Components:**
- `FormattedTextOptions` / `FormattedTextMode.Markdown`: request Markdown output.
- `TextReader.readToEnd()`: reads the whole extracted text.

**Parameters:** `parser`: the open document.

**Output:** an `ExtractedText` with `format` set to `markdown`, `plain` or `none`.

---

### Implementation: Splits Markdown into chunks at headings and records the heading path of each chunk

`chunkByHeadings` turns one Markdown string into chunks. Each `#` to `######` heading closes the running section, updates a six-slot heading stack and clears the deeper levels, so the path always reflects the current position in the outline. Sections are passed to `addSection`, which cuts anything over `MAX_CHUNK_CHARS` at paragraph breaks and drops blank pieces.

```java
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
```

#### Technical Details

The heading line itself stays in the section text, so the embedded chunk still reads "## Pricing" at the top. I would rather spend a few tokens on that line than make the model guess what the paragraph is about. Plain text from a PDF contains no `#` lines; it ends up with an empty heading path and is split only by size.

**Key Components:**
- `HEADING` pattern: `^(#{1,6})\s+(.+?)\s*#*\s*$`, which also accepts closing hashes.
- `joinPath`: joins non-null stack entries with ` > `.

**Parameters:** `markdown`, `sourceId`, `parentId`, `format`: the text plus the lineage copied onto every chunk.

**Output:** chunks with ids such as `SampleWithToc.docx#0` and heading paths such as `Heading 1 > Heading 1.2 > Heading 1.2.1`.

### Why split on headings instead of fixed token windows?

A heading marks the point where the author changed topic, so a chunk that starts and ends on headings holds one idea. Fixed windows ignore that and routinely mix the end of one section with the start of the next, which pollutes the embedding. Heading splits also give each chunk a readable path to show in citations, and the 1500-character cap still keeps long sections within embedding limits.

---

### Implementation: Opens every attachment or archive member as a child document and chunks it

`chunkContainerItems` is what makes an email or ZIP more than one chunk of headers. `getContainer()` returns the attachments of an EML or MSG message, or the entries of a ZIP archive, and `null` for formats that are not containers. Each `ContainerItem` opens its own `Parser`, and the child id is built from the parent id plus the item path.

```java
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
```

#### Technical Details

The recursion goes back through `chunkParser`, so an attached DOCX gets the same Markdown and heading treatment as a top-level one. In the sample run the attachment chunk id is `email-with-attachment.eml/attachment.txt#0` with `parent` set to `email-with-attachment.eml`, which is enough to show the original message next to the answer.

**Key Components:**
- `Parser.getContainer()`: lists attachments or archive entries.
- `ContainerItem.openParser()`: opens one item as a full document.

**Parameters:** `parser`, `sourceId`, `depth`: the container document, its id and its nesting level.

**Output:** chunks for every readable child; unreadable members are reported and skipped.

---

### Implementation: Writes the chunks as JSON Lines, one object per line, ready for an embedding job

The last step serialises the list. JSON Lines streams well, because a loader can read and embed one line at a time without holding the whole file in memory. The writer is opened as UTF-8 explicitly, so non-ASCII text survives on a Windows server with a legacy default code page.

```java
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
```

#### Technical Details

I kept the JSON escaping hand-written on purpose: one dependency in the pom is easier to audit than two, and the `json` helper only has to handle quotes, backslashes and control characters. If your project already ships Jackson or Gson, swap it in; the field names are the contract, not the writer.

**Key Components:**
- `Files.newBufferedWriter(..., StandardCharsets.UTF_8)`: buffered UTF-8 output.
- `json(String)`: escapes a value or writes `null`.

**Parameters:** `chunks`, `outputPath`: the collected chunks and the target file, `output/chunks.jsonl` in the demo.

**Output:** one JSON object per chunk, for example `{"id":"email-with-attachment.eml/attachment.txt#0","source":"email-with-attachment.eml/attachment.txt","parent":"email-with-attachment.eml",...}`.

## Best Practices

When implementing heading-scoped Markdown chunks for RAG, consider these best practices:

- Store the lineage fields as metadata: put `source`, `parent` and `heading_path` next to the vector so answers can cite the file and section.
- Re-run per file, not per batch: `chunkDocument` is self-contained, so a queue worker can process one upload at a time and retry only the failed one.
- Tune `MAX_CHUNK_CHARS` to the embedding model: 1500 characters is a starting point; measure retrieval quality before changing it.
- Add OCR before indexing scans: images and scanned PDFs return no text here, and the `none` format makes them easy to find in the output.
- Keep the depth limit: a small `MAX_CONTAINER_DEPTH` protects the worker from archive bombs and deeply nested mail threads.

## Additional Resources

For more in-depth information about Java RAG ingestion with GroupDocs.Parser, explore these technical resources:

* **Prepare documents for RAG: Markdown chunks in Java, documentation guide** – a stage-by-stage walkthrough of this pipeline in the GroupDocs.Parser docs: [Read more](https://docs.groupdocs.com/parser/java/use-cases/prepare-documents-rag-markdown-chunks/)

* **On-premise RAG ingestion in Java, blog article** – the business case and a worked scenario for a knowledge-base team: [Read more](https://blog.groupdocs.com/parser/prepare-documents-rag-markdown-chunks-java/)

* **Extract Markdown formatted text in Java** – which formatting the Markdown mode keeps, with input and output examples: [Read more](https://docs.groupdocs.com/parser/java/markdown/)

* **Extract data from attachments and ZIP archives in Java** – the `getContainer` and `ContainerItem` reference used by the recursion: [Read more](https://docs.groupdocs.com/parser/java/extract-data-from-attachments-and-zip-archives/)

* **Convert Word to Markdown in Java** – an earlier GroupDocs blog post on DOCX to MD conversion: [Read more](https://blog.groupdocs.com/parser/convert-word-to-markdown-in-java/)

* **Extract ZIP files data in Java** – text, images and metadata from archive members: [Read more](https://blog.groupdocs.com/parser/extract-zip-files-data-in-java/)

* **GroupDocs.Parser for Java API reference** – classes and methods used above: [Read more](https://reference.groupdocs.com/parser/java/)

## Keywords

`document parsing for RAG`, `Java RAG ingestion`, `convert documents to Markdown in Java`, `chunk documents by headings`, `GroupDocs.Parser for Java`, `extract email attachments in Java`, `on-premise document parser`, `LLM-ready Markdown`, `retrieval-augmented generation`, `heading path metadata`, `JSON Lines`, `vector store ingestion`, `DOCX to Markdown`, `PDF text extraction`, `EML attachments`, `ZIP archive parsing`, `formatted text extraction`, `semantic chunking`, `Maven`

## Support

For technical support, visit:
- [Free Support Forum](https://forum.groupdocs.com/c/parser/17)
- [Product Documentation](https://docs.groupdocs.com/parser/java/)
- [Get Temporary License](https://purchase.groupdocs.com/temp-license/100326)
