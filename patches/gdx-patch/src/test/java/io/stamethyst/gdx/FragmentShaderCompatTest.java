package io.stamethyst.gdx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FragmentShaderCompatTest {
    private static final String ENABLED_PROP = "amethyst.gdx.fragment_shader_precision_compat";
    private static final String NATIVE_DIR_PROP = "amethyst.gdx.native_dir";

    @Test
    public void ensureDefaultPrecision_respectsDisabledProperty() {
        String original = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "false");
            assertEquals(original, FragmentShaderCompat.ensureDefaultPrecision(original));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void ensureDefaultPrecision_injectsPrecisionWhenEnabled() {
        String original = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.ensureDefaultPrecision(original);
            assertTrue(patched.contains("precision highp float;"));
            assertTrue(patched.contains("precision highp int;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeVertexShader_stripsDesktopVersionHeader() {
        String original = "#version 120\nattribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeVertexShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.contains("attribute vec4 a_position;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeVertexShader_keepsGlesVersionHeader() {
        String original = "#version 300 es\nin vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            assertEquals(original, FragmentShaderCompat.normalizeVertexShader(original));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeVertexShader_addsGles100VersionOnAndroidRuntime() {
        String original = "#version 120\nattribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        String previousEnabled = System.getProperty(ENABLED_PROP);
        String previousNativeDir = System.getProperty(NATIVE_DIR_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            System.setProperty(NATIVE_DIR_PROP, "/tmp/native");
            String patched = FragmentShaderCompat.normalizeVertexShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.startsWith("#version 100\n"));
            assertTrue(patched.contains("attribute vec4 a_position;"));
        } finally {
            restoreProperty(ENABLED_PROP, previousEnabled);
            restoreProperty(NATIVE_DIR_PROP, previousNativeDir);
        }
    }

    @Test
    public void normalizeFragmentShader_stripsDesktopVersionAndInjectsPrecision() {
        String original = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.contains("precision highp float;"));
            assertTrue(patched.contains("gl_FragColor"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_addsGles100VersionOnAndroidRuntime() {
        String original = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previousEnabled = System.getProperty(ENABLED_PROP);
        String previousNativeDir = System.getProperty(NATIVE_DIR_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            System.setProperty(NATIVE_DIR_PROP, "/tmp/native");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.startsWith("#version 100\n"));
            assertTrue(patched.indexOf("#version 100") < patched.indexOf("precision highp float;"));
        } finally {
            restoreProperty(ENABLED_PROP, previousEnabled);
            restoreProperty(NATIVE_DIR_PROP, previousNativeDir);
        }
    }

    @Test
    public void normalizeFragmentShader_rewritesTextureFunctionForLegacyShader() {
        String original = "uniform sampler2D u_texture;\nvoid main() {\n" +
            "    gl_FragColor = texture(u_texture, vec2(0.5));\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("texture(u_texture"));
            assertTrue(patched.contains("texture2D(u_texture"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_keepsTextureFunctionForModernGlesShader() {
        String original = "#version 300 es\nprecision mediump float;\n" +
            "uniform sampler2D u_texture;\nout vec4 fragColor;\nvoid main() {\n" +
            "    fragColor = texture(u_texture, vec2(0.5));\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("#version 300 es"));
            assertTrue(patched.contains("texture(u_texture"));
            assertFalse(patched.contains("texture2D(u_texture"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_enablesDerivativeExtensionForLegacyShader() {
        String original = "void main() {\n    float width = fwidth(1.0);\n" +
            "    gl_FragColor = vec4(width);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("#extension GL_OES_standard_derivatives : enable"));
            assertTrue(patched.indexOf("#extension GL_OES_standard_derivatives : enable") <
                patched.indexOf("precision highp float;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_removesScalarFractRedefinition() {
        String original = "float fract(float x) { return x - floor(x); }\n" +
            "void main() {\n" +
            "    float a = fract(0.5);\n" +
            "    vec3 b = fract(vec3(0.5));\n" +
            "    gl_FragColor = vec4(b * a, 1.0);\n" +
            "}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("float fract(float x)"));
            assertTrue(patched.contains("float a = fract(0.5);"));
            assertTrue(patched.contains("vec3 b = fract(vec3(0.5));"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_removesJavaFloatLiteralSuffixes() {
        String original = "void main() {\n" +
            "    int mask = 0x1F;\n" +
            "    vec4 tint = vec4(0.1F, 0.2f, 1F, 1e-3f);\n" +
            "    gl_FragColor = tint;\n" +
            "}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("int mask = 0x1F;"));
            assertTrue(patched.contains("vec4 tint = vec4(0.1, 0.2, 1.0, 1e-3);"));
            assertFalse(patched.contains("0.1F"));
            assertFalse(patched.contains("0.2f"));
            assertFalse(patched.contains("1e-3f"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_promotesVectorMultiplyIntegerLiteral() {
        String original = "uniform sampler2D u_texture;\n" +
            "varying vec2 v_texCoord;\n" +
            "void main() {\n" +
            "    vec2 texDiff = vec2(0.1, 0.2);\n" +
            "    vec4 texColor = texture2D(u_texture, " +
            "v_texCoord + texDiff / vec2(1920.0, 1080.0) * 3);\n" +
            "    gl_FragColor = texColor;\n" +
            "}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("texDiff / vec2(1920.0, 1080.0) * 3.0"));
            assertFalse(patched.contains("vec2(1920.0, 1080.0) * 3)"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_promotesIntegerLiteralBeforeVectorOperand() {
        String original = "uniform sampler2D u_texture;\n" +
            "varying vec2 v_texCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = 2 * texture2D(u_texture, v_texCoord);\n" +
            "}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("gl_FragColor = 2.0 * texture2D"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_keepsIntegerOnlyArithmetic() {
        String original = "varying vec2 v_texCoord;\n" +
            "void main() {\n" +
            "    int count = 4;\n" +
            "    int doubled = count * 2;\n" +
            "    gl_FragColor = vec4(v_texCoord, 0.0, 1.0);\n" +
            "}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("int doubled = count * 2;"));
            assertFalse(patched.contains("int doubled = count * 2.0;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_respectsDisabledProperty() {
        String original = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "false");
            assertEquals(original, FragmentShaderCompat.normalizeFragmentShader(original));
        } finally {
            restoreProperty(previous);
        }
    }

    private static void restoreProperty(String previous) {
        restoreProperty(ENABLED_PROP, previous);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
