package io.github.spair.byond.dme;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.stream.Collectors;

final class PreParser {

    PreParser() {
    }

    private static final char NEW_LINE = '\n';
    private static final char SLASH = '/';
    private static final char BACKSLASH = '\\';
    private static final char SPACE = ' ';
    private static final char STAR = '*';
    private static final char LEFT_BRACKET = '[';
    private static final char RIGHT_BRACKET = ']';
    private static final char LEFT_PARENTHESIS = '(';
    private static final char RIGHT_PARENTHESIS = ')';
    private static final char LEFT_FIGURE_BRACKET = '{';
    private static final char RIGHT_FIGURE_BRACKET = '}';
    private static final char QUOTE = '"';
    private static final char TAB = '\t';

    private static final String NEW_LINE_ESCAPE = "\\n";
    private static final String QUOTE_ESCAPE = "\"";
    private static final String QUOTE_ESCAPE_EXTRA = "\\\"";

    // Strips comments and splits file into separate lines.
    List<FileLine> parse(final File file) {
        StringBuilder text = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.lines().forEach(line -> {
                text.append(line);

                if (line.endsWith("\\")) {
                    text.setLength(text.length() - 1);
                } else {
                    text.append(NEW_LINE);
                }
            });

            text.append(NEW_LINE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return doParse(text.toString(), file.getName());
    }

    @SuppressWarnings("checkstyle:AvoidInlineConditionals")
    private List<FileLine> doParse(final String text, final String fileName) {
        Deque<Syntax> syntaxStack = new ArrayDeque<>();
        List<FileLine> fileLines = new ArrayList<>();
        FileLine.Builder builder = FileLine.builder();

        boolean hasNonWhitespace = false;
        int indentLevel = 0;
        int lineNum = 0;
        int colNum = 0;

        for (int charIndex = 0; charIndex < text.length() - 1; charIndex++) {
            colNum++;

            final char currentChar = text.charAt(charIndex);
            final char nextChar = text.charAt(charIndex + 1);

            final Syntax currentSyntax = syntaxStack.size() > 0 ? syntaxStack.getLast() : null;

            final boolean inString = isString(currentSyntax) || isMultiString(currentSyntax);
            final boolean inComment = isComment(currentSyntax) || isMultiComment(currentSyntax);

            if (currentChar == BACKSLASH && nextChar != NEW_LINE && inString) {
                builder.append(currentChar).append(nextChar);
                charIndex++;
                continue;
            } else if (currentChar == NEW_LINE || (currentChar == BACKSLASH && nextChar == NEW_LINE)) {
                hasNonWhitespace = false;
                lineNum++;
                colNum = 0;
                indentLevel = 0;

                if (inComment && isComment(currentSyntax)) {
                    syntaxStack.pollLast();
                }

                if (!inComment && currentChar == BACKSLASH) {
                    builder.append(SPACE);
                    charIndex++;
                } else if (syntaxStack.isEmpty()) {
                    FileLine line = builder.build();
                    fileLines.add(line);
                    builder = FileLine.builder();
                } else if (isMultiString(currentSyntax)) {
                    builder.append(NEW_LINE_ESCAPE);
                } else if (!inString) {
                    builder.append(SPACE);
                }

                continue;
            } else if (inComment) {
                if (currentChar == SLASH && nextChar == STAR) {
                    syntaxStack.addLast(Syntax.MULTI_COMMENT);
                    charIndex++;
                } else if (currentChar == STAR && nextChar == SLASH && isMultiComment(currentSyntax)) {
                    syntaxStack.pollLast();
                    charIndex++;
                }
                continue;
            } else if (!inString && currentChar == SLASH && nextChar == SLASH) {
                syntaxStack.addLast(Syntax.COMMENT);
                charIndex++;
                continue;
            } else if (!inString && currentChar == SLASH && nextChar == STAR) {
                syntaxStack.addLast(Syntax.MULTI_COMMENT);
                charIndex++;
                continue;
            } else if (currentChar == LEFT_BRACKET) {
                syntaxStack.addLast(Syntax.BRACKETS);
            } else if (isBrackets(currentSyntax) && currentChar == RIGHT_BRACKET) {
                syntaxStack.pollLast();
            } else if (!inString && currentChar == QUOTE) {
                syntaxStack.addLast(Syntax.STRING);
            } else if (isString(currentSyntax) && currentChar == QUOTE) {
                syntaxStack.pollLast();
            } else if (!inString && currentChar == LEFT_PARENTHESIS) {
                syntaxStack.addLast(Syntax.PARENTHESES);
            } else if (isParentheses(currentSyntax) && currentChar == RIGHT_PARENTHESIS) {
                syntaxStack.pollLast();
            } else if (currentChar == LEFT_FIGURE_BRACKET && nextChar == QUOTE && !inString) {
                syntaxStack.addLast(Syntax.MULTI_STRING);
                charIndex++;
                builder.append(QUOTE_ESCAPE);
                continue;
            } else if (currentChar == QUOTE && nextChar == RIGHT_FIGURE_BRACKET && isMultiString(currentSyntax)) {
                syntaxStack.pollLast();
                charIndex++;
                builder.append(QUOTE_ESCAPE);
                continue;
            } else if (currentChar == QUOTE && isMultiString(currentSyntax)) {
                builder.append(QUOTE_ESCAPE_EXTRA);
                continue;
            }
            if ((currentChar != SPACE && currentChar != TAB) || hasNonWhitespace) {
                builder.append(currentChar);
            }
            if (currentChar != SPACE && currentChar != TAB && !hasNonWhitespace) {
                hasNonWhitespace = true;

                if (builder.hasNoIndent()) {
                    builder.setIndentLevel(indentLevel);
                }
            } else if (!hasNonWhitespace) {
                indentLevel++;
            }
        }

        fileLines.add(builder.build());

        if (syntaxStack.size() > 0) {
            throw new RuntimeException("Syntax stack not empty in file " + fileName + "! Stack: "
                    + syntaxStack + ".  Last line: " + lineNum + ", last col: " + colNum);
        }

        return fileLines.stream().filter(line -> !line.getText().isEmpty()).collect(Collectors.toList());
    }

    private boolean isString(final Syntax syntax) {
        return syntax == Syntax.STRING;
    }

    private boolean isMultiString(final Syntax syntax) {
        return syntax == Syntax.MULTI_STRING;
    }

    private boolean isComment(final Syntax syntax) {
        return syntax == Syntax.COMMENT;
    }

    private boolean isMultiComment(final Syntax syntax) {
        return syntax == Syntax.MULTI_COMMENT;
    }

    private boolean isBrackets(final Syntax syntax) {
        return syntax == Syntax.BRACKETS;
    }

    private boolean isParentheses(final Syntax syntax) {
        return syntax == Syntax.PARENTHESES;
    }
}
