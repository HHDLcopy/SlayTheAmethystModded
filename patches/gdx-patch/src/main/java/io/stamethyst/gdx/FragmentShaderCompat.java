package io.stamethyst.gdx;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FragmentShaderCompat {
    private static final String ENABLED_PROP = "amethyst.gdx.fragment_shader_precision_compat";
    private static final String NATIVE_DIR_PROP = "amethyst.gdx.native_dir";
    private static final Pattern LEGACY_TEXTURE_FUNCTION_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])texture\\s*\\(");
    private static final Pattern MODERN_TEXTURE_CALL_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])texture\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern SAMPLER_CUBE_UNIFORM_PATTERN =
        Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?samplerCube\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern STANDARD_DERIVATIVE_PATTERN =
        Pattern.compile("\\b(?:fwidth|dFdx|dFdy)\\s*\\(");
    private static final Pattern STANDARD_DERIVATIVE_EXTENSION_PATTERN =
        Pattern.compile("(?m)^\\s*#extension\\s+GL_OES_standard_derivatives\\s*:");
    private static final Pattern SCALAR_FRACT_REDEFINITION_PATTERN =
        Pattern.compile(
            "(?m)^\\s*float\\s+fract\\s*\\(\\s*float\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\{\\s*" +
                "return\\s+\\1\\s*-\\s*floor\\s*\\(\\s*\\1\\s*\\)\\s*;\\s*\\}\\s*(?:\\r?\\n)?"
        );
    private static final Pattern JAVA_FLOAT_LITERAL_SUFFIX_PATTERN =
        Pattern.compile(
            "(?<![A-Za-z0-9_])((?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][+-]?\\d+)?)[fF](?![A-Za-z0-9_])"
        );
    private static final Pattern VECTOR_DECLARATION_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])vec[234]\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern VECTOR_CONSTRUCTOR_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])vec[234]\\s*\\(");
    private static final Pattern VECTOR_SWIZZLE_PATTERN =
        Pattern.compile("\\.[xyzwrgba]{2,4}(?![A-Za-z0-9_])");
    private static final Pattern TEXTURE_FUNCTION_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])texture(?:2D|Cube)?\\s*\\(");
    private static final Pattern IDENTIFIER_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])");
    private static final Pattern FLOAT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+float\\s*;");
    private static final Pattern INT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+int\\s*;");
    private static final Pattern LAYOUT_QUALIFIER_PATTERN =
        Pattern.compile("(?m)^\\s*layout\\s*\\([^\\r\\n]*\\)\\s*");
    private static final Pattern VERTEX_INPUT_QUALIFIER_PATTERN =
        Pattern.compile("(?m)^(\\s*)(?:(?:flat|smooth|noperspective)\\s+)?in\\b");
    private static final Pattern VERTEX_OUTPUT_QUALIFIER_PATTERN =
        Pattern.compile("(?m)^(\\s*)(?:(?:flat|smooth|noperspective)\\s+)?out\\b");
    private static final Pattern FRAGMENT_INPUT_QUALIFIER_PATTERN =
        Pattern.compile("(?m)^(\\s*)(?:(?:flat|smooth|noperspective)\\s+)?in\\b");
    private static final Pattern FRAGMENT_OUTPUT_PATTERN =
        Pattern.compile(
            "(?m)^\\s*(?:(?:flat|smooth|noperspective)\\s+)?out\\s+vec4\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*(?:\\r?\\n)?"
        );

    private FragmentShaderCompat() {
    }

    public static String normalizeVertexShader(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        String stripped = stripLeadingDesktopVersionDirective(source, "vertex");
        String versioned = ensureGles100VersionDirective(stripped, "vertex");
        String withoutJavaFloatSuffixes = removeJavaFloatLiteralSuffixes(versioned);
        String legacyCompatible = isModernGlesVersionDirective(withoutJavaFloatSuffixes)
            ? withoutJavaFloatSuffixes
            : downgradeModernVertexShaderSyntax(withoutJavaFloatSuffixes);
        return promoteVectorScalarIntegerLiterals(legacyCompatible);
    }

    public static String normalizeFragmentShader(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        String stripped = stripLeadingDesktopVersionDirective(source, "fragment");
        String versioned = ensureGles100VersionDirective(stripped, "fragment");
        String withoutRedefinedBuiltIns = removeBuiltInFunctionRedefinitions(versioned);
        String withoutJavaFloatSuffixes = removeJavaFloatLiteralSuffixes(withoutRedefinedBuiltIns);
        String withoutVectorIntegerOperands =
            promoteVectorScalarIntegerLiterals(withoutJavaFloatSuffixes);
        String legacyCompatible = isModernGlesVersionDirective(withoutVectorIntegerOperands)
            ? withoutVectorIntegerOperands
            : ensureLegacyFragmentCompatibility(withoutVectorIntegerOperands);
        return ensureDefaultPrecisionInternal(legacyCompatible);
    }

    public static String ensureDefaultPrecision(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        return ensureDefaultPrecisionInternal(source);
    }

    private static String ensureDefaultPrecisionInternal(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }

        boolean missingFloatPrecision = !FLOAT_PRECISION_PATTERN.matcher(source).find();
        boolean missingIntPrecision = !INT_PRECISION_PATTERN.matcher(source).find();
        if (!missingFloatPrecision && !missingIntPrecision) {
            return source;
        }

        String lineSeparator = detectLineSeparator(source);
        int insertIndex = findInsertIndex(source);
        StringBuilder patched = new StringBuilder(source.length() + 160);
        patched.append(source, 0, insertIndex);
        if (insertIndex > 0) {
            char previous = source.charAt(insertIndex - 1);
            if (previous != '\n' && previous != '\r') {
                patched.append(lineSeparator);
            }
        }
        appendPrecisionBlock(
            patched,
            lineSeparator,
            missingFloatPrecision,
            missingIntPrecision
        );
        patched.append(source, insertIndex, source.length());
        return patched.toString();
    }

    private static String stripLeadingDesktopVersionDirective(String source, String shaderType) {
        int versionIndex = findLeadingVersionDirectiveIndex(source);
        if (versionIndex < 0) {
            return source;
        }

        int lineEnd = skipLine(source, versionIndex);
        String directiveLine = source.substring(versionIndex, lineEnd).trim();
        if (!isDesktopVersionDirective(directiveLine)) {
            return source;
        }

        System.out.println(
            "[gdx-patch] Shader source compat stripped desktop GLSL version header from " +
                shaderType + " shader"
        );
        return source.substring(0, versionIndex) + source.substring(lineEnd);
    }

    private static String ensureLegacyFragmentCompatibility(String source) {
        String patched = downgradeModernFragmentShaderSyntax(source);
        patched = ensureStandardDerivativesExtension(patched);
        return rewriteLegacyTextureFunctions(patched);
    }

    private static String downgradeModernVertexShaderSyntax(String source) {
        String withoutLayouts = LAYOUT_QUALIFIER_PATTERN.matcher(source).replaceAll("");
        String attributes = VERTEX_INPUT_QUALIFIER_PATTERN.matcher(withoutLayouts)
            .replaceAll("$1attribute");
        return VERTEX_OUTPUT_QUALIFIER_PATTERN.matcher(attributes).replaceAll("$1varying");
    }

    private static String downgradeModernFragmentShaderSyntax(String source) {
        String withoutLayouts = LAYOUT_QUALIFIER_PATTERN.matcher(source).replaceAll("");
        Matcher outputMatcher = FRAGMENT_OUTPUT_PATTERN.matcher(withoutLayouts);
        StringBuffer withoutOutputs = new StringBuffer(withoutLayouts.length());
        String outputName = null;
        while (outputMatcher.find()) {
            if (outputName == null) {
                outputName = outputMatcher.group(1);
            }
            outputMatcher.appendReplacement(withoutOutputs, "");
        }
        outputMatcher.appendTail(withoutOutputs);

        String varyings = FRAGMENT_INPUT_QUALIFIER_PATTERN.matcher(withoutOutputs.toString())
            .replaceAll("$1varying");
        return outputName == null ? varyings : replaceIdentifierOutsideComments(
            varyings,
            outputName,
            "gl_FragColor"
        );
    }

    private static String rewriteLegacyTextureFunctions(String source) {
        Set<String> cubeSamplers = new HashSet<String>();
        Matcher samplerMatcher = SAMPLER_CUBE_UNIFORM_PATTERN.matcher(blankComments(source));
        while (samplerMatcher.find()) {
            cubeSamplers.add(samplerMatcher.group(1));
        }

        Matcher textureMatcher = MODERN_TEXTURE_CALL_PATTERN.matcher(source);
        StringBuffer out = null;
        while (textureMatcher.find()) {
            if (out == null) {
                out = new StringBuffer(source.length());
            }
            String function = cubeSamplers.contains(textureMatcher.group(1))
                ? "textureCube("
                : "texture2D(";
            textureMatcher.appendReplacement(out, function + textureMatcher.group(1));
        }
        if (out == null) {
            return LEGACY_TEXTURE_FUNCTION_PATTERN.matcher(source).replaceAll("texture2D(");
        }
        textureMatcher.appendTail(out);
        return out.toString();
    }

    private static String replaceIdentifierOutsideComments(
        String source,
        String identifier,
        String replacement
    ) {
        StringBuilder out = null;
        int lastAppend = 0;
        int index = 0;
        while (index < source.length()) {
            int commentEnd = skipComment(source, index);
            if (commentEnd != index) {
                index = commentEnd;
                continue;
            }
            if (startsWithIdentifier(source, index, identifier)) {
                if (out == null) {
                    out = new StringBuilder(source.length() + replacement.length());
                }
                out.append(source, lastAppend, index).append(replacement);
                index += identifier.length();
                lastAppend = index;
                continue;
            }
            index++;
        }
        if (out == null) {
            return source;
        }
        out.append(source, lastAppend, source.length());
        return out.toString();
    }

    private static boolean startsWithIdentifier(String source, int index, String identifier) {
        int end = index + identifier.length();
        return end <= source.length() &&
            source.regionMatches(index, identifier, 0, identifier.length()) &&
            (index == 0 || !isIdentifierPart(source.charAt(index - 1))) &&
            (end == source.length() || !isIdentifierPart(source.charAt(end)));
    }

    private static String removeBuiltInFunctionRedefinitions(String source) {
        return SCALAR_FRACT_REDEFINITION_PATTERN.matcher(source).replaceAll("");
    }

    private static String removeJavaFloatLiteralSuffixes(String source) {
        java.util.regex.Matcher matcher = JAVA_FLOAT_LITERAL_SUFFIX_PATTERN.matcher(source);
        StringBuffer out = null;
        while (matcher.find()) {
            if (out == null) {
                out = new StringBuffer(source.length());
            }
            String literal = matcher.group(1);
            if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
                literal = literal + ".0";
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(literal));
        }
        if (out == null) {
            return source;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String promoteVectorScalarIntegerLiterals(String source) {
        Set<String> vectorIdentifiers = collectVectorIdentifiers(source);
        StringBuilder out = null;
        int lastAppend = 0;
        int index = 0;
        while (index < source.length()) {
            int commentEnd = skipComment(source, index);
            if (commentEnd != index) {
                index = commentEnd;
                continue;
            }

            char current = source.charAt(index);
            if (current == '*' || current == '/') {
                IntegerLiteral leftLiteral = findIntegerLiteralBefore(source, index);
                if (leftLiteral != null &&
                    leftLiteral.end >= lastAppend &&
                    isVectorLikeRightOperand(source, index, vectorIdentifiers)
                ) {
                    if (out == null) {
                        out = new StringBuilder(source.length() + 16);
                    }
                    out.append(source, lastAppend, leftLiteral.end).append(".0");
                    lastAppend = leftLiteral.end;
                }

                IntegerLiteral rightLiteral = findIntegerLiteralAfter(source, index);
                if (rightLiteral != null &&
                    rightLiteral.end >= lastAppend &&
                    isVectorLikeLeftOperand(source, index, vectorIdentifiers)
                ) {
                    if (out == null) {
                        out = new StringBuilder(source.length() + 16);
                    }
                    out.append(source, lastAppend, rightLiteral.end).append(".0");
                    lastAppend = rightLiteral.end;
                    index = rightLiteral.end;
                    continue;
                }
            }
            index++;
        }

        if (out == null) {
            return source;
        }
        out.append(source, lastAppend, source.length());
        return out.toString();
    }

    private static Set<String> collectVectorIdentifiers(String source) {
        String analysisSource = blankComments(source);
        Set<String> identifiers = new HashSet<String>();
        Matcher matcher = VECTOR_DECLARATION_PATTERN.matcher(analysisSource);
        while (matcher.find()) {
            int afterIdentifier = skipWhitespace(analysisSource, matcher.end(1));
            if (afterIdentifier < analysisSource.length() &&
                analysisSource.charAt(afterIdentifier) == '('
            ) {
                continue;
            }
            identifiers.add(matcher.group(1));
        }
        return identifiers;
    }

    private static boolean isVectorLikeLeftOperand(
        String source,
        int operatorIndex,
        Set<String> vectorIdentifiers
    ) {
        int start = findLeftOperandStart(source, operatorIndex);
        return isVectorLikeExpression(source.substring(start, operatorIndex), vectorIdentifiers);
    }

    private static boolean isVectorLikeRightOperand(
        String source,
        int operatorIndex,
        Set<String> vectorIdentifiers
    ) {
        int end = findRightOperandEnd(source, operatorIndex);
        return isVectorLikeExpression(
            source.substring(operatorIndex + 1, end),
            vectorIdentifiers
        );
    }

    private static boolean isVectorLikeExpression(
        String expression,
        Set<String> vectorIdentifiers
    ) {
        if (VECTOR_CONSTRUCTOR_PATTERN.matcher(expression).find() ||
            VECTOR_SWIZZLE_PATTERN.matcher(expression).find() ||
            TEXTURE_FUNCTION_PATTERN.matcher(expression).find()
        ) {
            return true;
        }

        Matcher matcher = IDENTIFIER_PATTERN.matcher(expression);
        while (matcher.find()) {
            if (vectorIdentifiers.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static int findLeftOperandStart(String source, int operatorIndex) {
        int index = operatorIndex - 1;
        int depth = 0;
        while (index >= 0) {
            char current = source.charAt(index);
            if (current == ')' || current == ']') {
                depth++;
            } else if (current == '(' || current == '[') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && isAdditiveOrStatementBoundary(current)) {
                return index + 1;
            }
            index--;
        }
        return 0;
    }

    private static int findRightOperandEnd(String source, int operatorIndex) {
        int index = operatorIndex + 1;
        int depth = 0;
        boolean sawOperand = false;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '(' || current == '[') {
                depth++;
            } else if (current == ')' || current == ']') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0) {
                if (current == ';' || current == '\n' || current == '\r' ||
                    current == '=' || current == ',' || current == '?' ||
                    current == ':'
                ) {
                    break;
                }
                if (sawOperand && (current == '+' || current == '-')) {
                    break;
                }
            }

            if (!Character.isWhitespace(current)) {
                sawOperand = true;
            }
            index++;
        }
        return index;
    }

    private static boolean isAdditiveOrStatementBoundary(char current) {
        return current == '+' || current == '-' || current == '=' ||
            current == ',' || current == ';' || current == '\n' ||
            current == '\r' || current == '?' || current == ':';
    }

    private static IntegerLiteral findIntegerLiteralAfter(String source, int operatorIndex) {
        int start = skipWhitespace(source, operatorIndex + 1);
        if (start >= source.length()) {
            return null;
        }

        int digitsStart = start;
        char first = source.charAt(digitsStart);
        if ((first == '+' || first == '-') &&
            digitsStart + 1 < source.length() &&
            Character.isDigit(source.charAt(digitsStart + 1))
        ) {
            digitsStart++;
        }

        int digitsEnd = skipDigits(source, digitsStart);
        if (digitsEnd == digitsStart || !isPlainIntegerLiteral(source, digitsStart, digitsEnd)) {
            return null;
        }
        return new IntegerLiteral(digitsStart, digitsEnd);
    }

    private static IntegerLiteral findIntegerLiteralBefore(String source, int operatorIndex) {
        int digitsEnd = skipWhitespaceBackward(source, operatorIndex - 1) + 1;
        if (digitsEnd <= 0) {
            return null;
        }

        int digitsStart = digitsEnd;
        while (digitsStart > 0 && Character.isDigit(source.charAt(digitsStart - 1))) {
            digitsStart--;
        }
        if (digitsEnd == digitsStart || !isPlainIntegerLiteral(source, digitsStart, digitsEnd)) {
            return null;
        }
        return new IntegerLiteral(digitsStart, digitsEnd);
    }

    private static boolean isPlainIntegerLiteral(String source, int start, int end) {
        if (start > 0) {
            char previous = source.charAt(start - 1);
            if (previous == '.' || isIdentifierPart(previous)) {
                return false;
            }
        }
        if (end < source.length()) {
            char next = source.charAt(end);
            if (next == '.' || isIdentifierPart(next)) {
                return false;
            }
        }
        return true;
    }

    private static int skipDigits(String source, int start) {
        int index = start;
        while (index < source.length() && Character.isDigit(source.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int skipWhitespace(String source, int start) {
        int index = start;
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int skipWhitespaceBackward(String source, int start) {
        int index = start;
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
            index--;
        }
        return index;
    }

    private static boolean isIdentifierPart(char current) {
        return (current >= 'A' && current <= 'Z') ||
            (current >= 'a' && current <= 'z') ||
            (current >= '0' && current <= '9') ||
            current == '_';
    }

    private static String blankComments(String source) {
        StringBuilder out = null;
        int index = 0;
        int lastAppend = 0;
        while (index < source.length()) {
            int commentEnd = skipComment(source, index);
            if (commentEnd == index) {
                index++;
                continue;
            }

            if (out == null) {
                out = new StringBuilder(source.length());
            }
            out.append(source, lastAppend, index);
            appendBlanksPreservingLines(out, source, index, commentEnd);
            lastAppend = commentEnd;
            index = commentEnd;
        }

        if (out == null) {
            return source;
        }
        out.append(source, lastAppend, source.length());
        return out.toString();
    }

    private static int skipComment(String source, int index) {
        if (index + 1 >= source.length() || source.charAt(index) != '/') {
            return index;
        }

        char next = source.charAt(index + 1);
        if (next == '/') {
            return skipLine(source, index);
        }
        if (next == '*') {
            int end = source.indexOf("*/", index + 2);
            return end < 0 ? source.length() : end + 2;
        }
        return index;
    }

    private static void appendBlanksPreservingLines(
        StringBuilder out,
        String source,
        int start,
        int end
    ) {
        for (int index = start; index < end; index++) {
            char current = source.charAt(index);
            out.append(current == '\n' || current == '\r' ? current : ' ');
        }
    }

    private static String ensureGles100VersionDirective(String source, String shaderType) {
        if (!isGlesBackedRuntime() || findLeadingVersionDirectiveIndex(source) >= 0) {
            return source;
        }

        int insertIndex = source.charAt(0) == '\ufeff' ? 1 : 0;
        String lineSeparator = detectLineSeparator(source);
        System.out.println(
            "[gdx-patch] Shader source compat added GLES 100 version header to " +
                shaderType + " shader"
        );
        return source.substring(0, insertIndex) +
            "#version 100" + lineSeparator +
            source.substring(insertIndex);
    }

    private static String ensureStandardDerivativesExtension(String source) {
        if (!STANDARD_DERIVATIVE_PATTERN.matcher(source).find() ||
            STANDARD_DERIVATIVE_EXTENSION_PATTERN.matcher(source).find()
        ) {
            return source;
        }

        String lineSeparator = detectLineSeparator(source);
        int insertIndex = findInsertIndex(source);
        StringBuilder patched = new StringBuilder(source.length() + 64);
        patched.append(source, 0, insertIndex);
        if (insertIndex > 0) {
            char previous = source.charAt(insertIndex - 1);
            if (previous != '\n' && previous != '\r') {
                patched.append(lineSeparator);
            }
        }
        patched.append("#extension GL_OES_standard_derivatives : enable").append(lineSeparator);
        patched.append(source, insertIndex, source.length());
        return patched.toString();
    }

    private static int findLeadingVersionDirectiveIndex(String source) {
        int cursor = 0;
        if (source.charAt(0) == '\ufeff') {
            cursor = 1;
        }
        int candidate = skipTrivia(source, cursor);
        return startsWithDirective(source, candidate, "#version") ? candidate : -1;
    }

    private static boolean isDesktopVersionDirective(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 2 || !"#version".equalsIgnoreCase(tokens[0])) {
            return false;
        }
        for (String token : tokens) {
            if ("es".equalsIgnoreCase(token)) {
                return false;
            }
        }
        try {
            return Integer.parseInt(tokens[1]) >= 110;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isModernGlesVersionDirective(String source) {
        int versionIndex = findLeadingVersionDirectiveIndex(source);
        if (versionIndex < 0) {
            return false;
        }

        int lineEnd = skipLine(source, versionIndex);
        String[] tokens = source.substring(versionIndex, lineEnd).trim().split("\\s+");
        if (tokens.length < 3 || !"#version".equalsIgnoreCase(tokens[0])) {
            return false;
        }
        if (!"es".equalsIgnoreCase(tokens[2])) {
            return false;
        }
        try {
            return Integer.parseInt(tokens[1]) >= 300;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void appendPrecisionBlock(
        StringBuilder out,
        String lineSeparator,
        boolean missingFloatPrecision,
        boolean missingIntPrecision
    ) {
        out.append("#ifdef GL_ES").append(lineSeparator);
        out.append("#ifdef GL_FRAGMENT_PRECISION_HIGH").append(lineSeparator);
        if (missingFloatPrecision) {
            out.append("precision highp float;").append(lineSeparator);
        }
        if (missingIntPrecision) {
            out.append("precision highp int;").append(lineSeparator);
        }
        out.append("#else").append(lineSeparator);
        if (missingFloatPrecision) {
            out.append("precision mediump float;").append(lineSeparator);
        }
        if (missingIntPrecision) {
            out.append("precision mediump int;").append(lineSeparator);
        }
        out.append("#endif").append(lineSeparator);
        out.append("#endif").append(lineSeparator);
    }

    private static int findInsertIndex(String source) {
        int cursor = 0;
        if (source.charAt(0) == '\ufeff') {
            cursor = 1;
        }
        cursor = skipTrivia(source, cursor);
        if (startsWithDirective(source, cursor, "#version")) {
            cursor = skipLine(source, cursor);
        }
        while (true) {
            int next = skipTrivia(source, cursor);
            if (!startsWithDirective(source, next, "#extension")) {
                return next;
            }
            cursor = skipLine(source, next);
        }
    }

    private static int skipTrivia(String source, int start) {
        int index = start;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    index = skipLine(source, index);
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", index + 2);
                    if (end < 0) {
                        return source.length();
                    }
                    index = end + 2;
                    continue;
                }
            }
            break;
        }
        return index;
    }

    private static int skipLine(String source, int start) {
        int index = start;
        while (index < source.length()) {
            char current = source.charAt(index++);
            if (current == '\n') {
                return index;
            }
            if (current == '\r') {
                if (index < source.length() && source.charAt(index) == '\n') {
                    index++;
                }
                return index;
            }
        }
        return source.length();
    }

    private static boolean startsWithDirective(String source, int index, String directive) {
        if (index < 0 || index + directive.length() > source.length()) {
            return false;
        }
        if (!source.regionMatches(index, directive, 0, directive.length())) {
            return false;
        }
        int nextIndex = index + directive.length();
        return nextIndex >= source.length() || Character.isWhitespace(source.charAt(nextIndex));
    }

    private static String detectLineSeparator(String source) {
        int newlineIndex = source.indexOf('\n');
        if (newlineIndex > 0 && source.charAt(newlineIndex - 1) == '\r') {
            return "\r\n";
        }
        return "\n";
    }

    private static boolean isCompatEnabled() {
        String configured = System.getProperty(ENABLED_PROP);
        if (configured == null) {
            return true;
        }
        String normalized = configured.trim();
        if (normalized.isEmpty()) {
            return true;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return true;
    }

    private static boolean isGlesBackedRuntime() {
        if (System.getProperty(NATIVE_DIR_PROP) != null) {
            return true;
        }
        return System.getProperty("os.version", "").startsWith("Android-");
    }

    private static final class IntegerLiteral {
        final int start;
        final int end;

        IntegerLiteral(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
