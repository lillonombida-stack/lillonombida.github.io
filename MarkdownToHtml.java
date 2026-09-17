import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MarkdownToHtml - A command-line Markdown-to-HTML converter.
 *
 * Zero external dependencies: implements a small Markdown parser covering
 * the common syntax (headers, bold/italic, inline code, fenced code blocks,
 * blockquotes, lists, links, horizontal rules, paragraphs).
 *
 * Usage:
 *   javac MarkdownToHtml.java
 *
 *   # Convert a file, write output.html next to it (or specify output path)
 *   java MarkdownToHtml input.md
 *   java MarkdownToHtml input.md output.html
 *
 *   # No arguments: interactive mode - paste/type Markdown, end with a
 *   # single line containing just EOF
 *   java MarkdownToHtml
 */
public class MarkdownToHtml {

    public static void main(String[] args) throws IOException {
        String markdown;
        String outputPath;

        if (args.length >= 1) {
            Path inputPath = Path.of(args[0]);
            if (!Files.exists(inputPath)) {
                System.out.println("File not found: " + args[0]);
                return;
            }
            markdown = Files.readString(inputPath, StandardCharsets.UTF_8);
            outputPath = args.length >= 2 ? args[1] : deriveOutputPath(args[0]);
        } else {
            System.out.println("=====================================");
            System.out.println("     Java Markdown -> HTML Converter ");
            System.out.println("=====================================");
            System.out.println("Paste or type Markdown below.");
            System.out.println("On its own line, type EOF when you're done.\n");
            markdown = readFromStdin();
            System.out.print("\nOutput file name [output.html]: ");
            Scanner sc = new Scanner(System.in);
            String entered = sc.nextLine().trim();
            outputPath = entered.isEmpty() ? "output.html" : entered;
        }

        String bodyHtml = convert(markdown);
        String fullHtml = wrapDocument(bodyHtml);

        Files.writeString(Path.of(outputPath), fullHtml, StandardCharsets.UTF_8);
        System.out.println("\nDone. Wrote " + outputPath + " (" + fullHtml.length() + " characters).");
    }

    private static String readFromStdin() {
        Scanner sc = new Scanner(System.in);
        StringBuilder sb = new StringBuilder();
        while (sc.hasNextLine()) {
            String line = sc.nextLine();
            if (line.trim().equals("EOF")) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String deriveOutputPath(String inputPath) {
        int dot = inputPath.lastIndexOf('.');
        String base = dot > 0 ? inputPath.substring(0, dot) : inputPath;
        return base + ".html";
    }

    private static String wrapDocument(String body) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<title>Converted document</title>\n"
                + "</head>\n"
                + "<body>\n"
                + body
                + "</body>\n"
                + "</html>\n";
    }

    // ---------- Core conversion ----------

    private static final Pattern HEADER = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^\\s*\\d+\\.\\s+(.*)$");
    private static final Pattern UNORDERED_ITEM = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern FENCE = Pattern.compile("^```(.*)$");

    public static String convert(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder html = new StringBuilder();

        int i = 0;
        boolean inUl = false;
        boolean inOl = false;
        boolean inBlockquote = false;
        List<String> paragraphBuffer = new ArrayList<>();

        while (i < lines.length) {
            String line = lines[i];

            // Fenced code block
            Matcher fenceMatcher = FENCE.matcher(line);
            if (fenceMatcher.matches()) {
                flushParagraph(html, paragraphBuffer);
                inUl = closeIfOpen(html, inUl, "ul");
                inOl = closeIfOpen(html, inOl, "ol");
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");

                String lang = fenceMatcher.group(1).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !FENCE.matcher(lines[i]).matches()) {
                    code.append(escapeHtml(lines[i])).append("\n");
                    i++;
                }
                String classAttr = lang.isEmpty() ? "" : " class=\"language-" + escapeHtml(lang) + "\"";
                html.append("<pre><code").append(classAttr).append(">")
                        .append(code)
                        .append("</code></pre>\n");
                i++; // skip closing fence
                continue;
            }

            // Horizontal rule
            if (HR.matcher(line).matches() && !line.isBlank()) {
                flushParagraph(html, paragraphBuffer);
                inUl = closeIfOpen(html, inUl, "ul");
                inOl = closeIfOpen(html, inOl, "ol");
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");
                html.append("<hr>\n");
                i++;
                continue;
            }

            // Header
            Matcher headerMatcher = HEADER.matcher(line);
            if (headerMatcher.matches()) {
                flushParagraph(html, paragraphBuffer);
                inUl = closeIfOpen(html, inUl, "ul");
                inOl = closeIfOpen(html, inOl, "ol");
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");
                int level = headerMatcher.group(1).length();
                html.append("<h").append(level).append(">")
                        .append(inline(headerMatcher.group(2)))
                        .append("</h").append(level).append(">\n");
                i++;
                continue;
            }

            // Blockquote
            Matcher bqMatcher = BLOCKQUOTE.matcher(line);
            if (bqMatcher.matches()) {
                flushParagraph(html, paragraphBuffer);
                inUl = closeIfOpen(html, inUl, "ul");
                inOl = closeIfOpen(html, inOl, "ol");
                if (!inBlockquote) {
                    html.append("<blockquote>\n");
                    inBlockquote = true;
                }
                html.append("<p>").append(inline(bqMatcher.group(1))).append("</p>\n");
                i++;
                continue;
            } else if (inBlockquote && line.isBlank()) {
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");
            }

            // Unordered list
            Matcher ulMatcher = UNORDERED_ITEM.matcher(line);
            if (ulMatcher.matches()) {
                flushParagraph(html, paragraphBuffer);
                inOl = closeIfOpen(html, inOl, "ol");
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");
                if (!inUl) {
                    html.append("<ul>\n");
                    inUl = true;
                }
                html.append("<li>").append(inline(ulMatcher.group(1))).append("</li>\n");
                i++;
                continue;
            }

            // Ordered list
            Matcher olMatcher = ORDERED_ITEM.matcher(line);
            if (olMatcher.matches()) {
                flushParagraph(html, paragraphBuffer);
                inUl = closeIfOpen(html, inUl, "ul");
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");
                if (!inOl) {
                    html.append("<ol>\n");
                    inOl = true;
                }
                html.append("<li>").append(inline(olMatcher.group(1))).append("</li>\n");
                i++;
                continue;
            }

            // Blank line: close open blocks, flush paragraph
            if (line.isBlank()) {
                flushParagraph(html, paragraphBuffer);
                inUl = closeIfOpen(html, inUl, "ul");
                inOl = closeIfOpen(html, inOl, "ol");
                inBlockquote = closeIfOpen(html, inBlockquote, "blockquote");
                i++;
                continue;
            }

            // Otherwise: part of a paragraph
            inUl = closeIfOpen(html, inUl, "ul");
            inOl = closeIfOpen(html, inOl, "ol");
            paragraphBuffer.add(line);
            i++;
        }

        flushParagraph(html, paragraphBuffer);
        closeIfOpen(html, inUl, "ul");
        closeIfOpen(html, inOl, "ol");
        closeIfOpen(html, inBlockquote, "blockquote");

        return html.toString();
    }

    private static boolean closeIfOpen(StringBuilder html, boolean open, String tag) {
        if (open) {
            html.append("</").append(tag).append(">\n");
        }
        return false;
    }

    private static void flushParagraph(StringBuilder html, List<String> buffer) {
        if (!buffer.isEmpty()) {
            html.append("<p>").append(inline(String.join(" ", buffer))).append("</p>\n");
            buffer.clear();
        }
    }

    // ---------- Inline formatting ----------

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!_)_([^_]+)_(?!_)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)");

    private static String inline(String text) {
        String escaped = escapeHtml(text);

        // Inline code first, so markers inside it aren't touched by bold/italic/links
        escaped = replaceAll(escaped, INLINE_CODE, m -> "<code>" + m.group(1) + "</code>");

        escaped = replaceAll(escaped, LINK, m -> {
            String linkText = m.group(1);
            String href = m.group(2);
            String title = m.group(3);
            String titleAttr = (title != null && !title.isEmpty()) ? " title=\"" + title + "\"" : "";
            return "<a href=\"" + href + "\"" + titleAttr + ">" + linkText + "</a>";
        });

        escaped = replaceAll(escaped, BOLD, m -> {
            String content = m.group(1) != null ? m.group(1) : m.group(2);
            return "<strong>" + content + "</strong>";
        });

        escaped = replaceAll(escaped, ITALIC, m -> {
            String content = m.group(1) != null ? m.group(1) : m.group(2);
            return "<em>" + content + "</em>";
        });

        return escaped;
    }

    @FunctionalInterface
    private interface MatchHandler {
        String handle(Matcher m);
    }

    private static String replaceAll(String input, Pattern pattern, MatchHandler handler) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(handler.handle(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
