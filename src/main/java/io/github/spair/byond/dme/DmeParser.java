package io.github.spair.byond.dme;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DmeParser {

    private static final String DME_SUFFIX = ".dme";
    private static final String BYOND_DEF_FILE = "stddef.dm";

    private final Pattern directivesPattern = Pattern.compile("#(ifdef|ifndef|undef|if)[\\s]+(.+)");
    private final Pattern includePattern = Pattern.compile("#include\\s+\"(.*(?:\\.dm|\\.dme|\\.dmm))\"");
    private final Pattern definePattern = Pattern.compile("^#define\\s+(\\w+)(?:\\([^)]*\\))?(?:\\s+(.+))?");
    private final Pattern varDefinitionPattern = Pattern.compile(
            "^[/\\w]+(?:var(?:/[\\w/]+)?)?/(\\w+)\\s*=\\s*(.+)|^[/\\w]+(?:var(?:/[\\w/]+)?)/(\\w+)");

    private final PreParser preParser = new PreParser();

    private List<String> pathTree = new ArrayList<>();
    private Dme dme = DmeInitializer.initialize(new Dme());

    public static Dme parse(final File dmeFile) {
        if (dmeFile.isFile() && dmeFile.getName().endsWith(DME_SUFFIX)) {
            DmeParser parser = new DmeParser();

            parser.doParse(ResourceUtil.loadFile(BYOND_DEF_FILE));
            parser.doParse(dmeFile);
            parser.postParse();

            return parser.dme;
        } else {
            throw new IllegalArgumentException("Parser only accept '.dme' files");
        }
    }

    private void postParse() {
        new PostParser(dme).parse();
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private void doParse(final File file) {
        Map<String, String> macroses = dme.getMacroses();

        Deque<Boolean> preProcessStack = new ArrayDeque<>();
        int preProcessBlocked = 0;

        for (FileLine line : preParser.parse(file)) {
            final String lineText = line.getText();

            if (line.hasNoIndent()) {
                continue;
            }

            if (lineText.startsWith(Directives.HASH)) {
                if (lineText.contains(Directives.Hashed.ENDIF) && !preProcessStack.removeLast()) {
                    preProcessBlocked--;
                }

                Matcher matcher = directivesPattern.matcher(lineText);

                if (matcher.find()) {
                    final String macrosValue = matcher.group(2);

                    switch (matcher.group(1)) {
                        case Directives.UNDEF:
                            macroses.remove(macrosValue);
                            break;
                        case Directives.IFDEF:
                            boolean isDefined = macroses.containsKey(macrosValue);
                            preProcessStack.addLast(isDefined);

                            if (!isDefined) {
                                preProcessBlocked++;
                            }

                            break;
                        case Directives.IFNDEF:
                            boolean isNotDefined = !macroses.containsKey(macrosValue);
                            preProcessStack.addLast(isNotDefined);

                            if (!isNotDefined) {
                                preProcessBlocked++;
                            }

                            break;
                        case Directives.IF:
                            preProcessStack.addLast(true);
                            break;
                    }
                }
                if (preProcessBlocked > 0) {
                    continue;
                }

                addNewMacrosValueIfExist(lineText);
                parseIncludedFileIfExist(lineText, file);

                continue;
            }

            final String fullPath = formFullPath(line);
            final String type = formTypeName(fullPath);

            DmeItem dmeItem = dme.getItemOrCreate(type);
            Matcher varMatcher = varDefinitionPattern.matcher(fullPath);

            if (varMatcher.find()) {
                final String value = varMatcher.group(2);

                if (Objects.nonNull(value)) {
                    final String varName = varMatcher.group(1);
                    dmeItem.setVar(varName, WordDefineChecker.check(value, macroses));
                } else {
                    dmeItem.setEmptyVar(varMatcher.group(3));
                }
            }
        }
    }

    private void addNewMacrosValueIfExist(final String lineText) {
        Matcher matcher = definePattern.matcher(lineText);

        if (matcher.find() && Objects.nonNull(matcher.group(2))) {
            String macrosValue = matcher.group(2).replace("$", "\\$");
            dme.addMacros(matcher.group(1), WordDefineChecker.check(macrosValue, dme.getMacroses()));
        }
    }

    private void parseIncludedFileIfExist(final String lineText, final File currentFile) {
        Matcher matcher = includePattern.matcher(lineText);

        if (matcher.find()) {
            String filePath = matcher.group(1);

            if (filePath.endsWith(".dmm")) {
                dme.addMapFile(filePath);
            } else {
                dme.addIncludedFile(filePath);
                doParse(new File(currentFile.getParent().concat("/").concat(filePath)));
            }
        }
    }

    private void checkPathTreeSize(final int expectedSize) {
        if (pathTree.size() < expectedSize) {
            pathTree.addAll(Collections.nCopies(expectedSize - pathTree.size(), ""));
        }
    }

    private String formFullPath(final FileLine line) {
        checkPathTreeSize(line.getIndentLevel() + 1);

        pathTree.set(line.getIndentLevel(), line.getText());
        StringBuilder fullPath = new StringBuilder();

        for (int i = 0; i < line.getIndentLevel() + 1; i++) {
            String item = pathTree.get(i);

            if (Objects.nonNull(item) && !item.isEmpty()) {
                if (item.startsWith("/")) {
                    fullPath = new StringBuilder(item);
                } else {
                    fullPath.append("/").append(item);
                }
            }
        }

        return fullPath.toString();
    }

    @SuppressWarnings("checkstyle:AvoidInlineConditionals")
    private String formTypeName(final String fullPath) {
        StringBuilder typeName = new StringBuilder();

        for (String pathPart : fullPath.split("/")) {
            if (pathPart.isEmpty()) {
                continue;
            } else if (notPartOfTypeName(pathPart)) {
                break;
            }

            typeName.append("/").append(pathPart);
        }

        return typeName.length() > 0 ? typeName.toString() : ByondTypes.GLOBAL;
    }

    private boolean notPartOfTypeName(final String item) {
        return item.contains("=") || item.contains("(")
                || "var".equals(item) || "proc".equals(item) || "global".equals(item)
                || "static".equals(item) || "tmp".equals(item) || "verb".equals(item);
    }

    private DmeParser() {
    }
}