package com.filtercam.gl

object Shaders {
    const val VERTEX = """
in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
out vec2 vScreen;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vScreen = aPos * 0.5 + 0.5;
    vUv = (uTexMatrix * vec4(vScreen, 0.0, 1.0)).xy;
}
"""

    const val FRAGMENT = """
precision highp float;
precision highp int;
in vec2 vUv;
in vec2 vScreen;
out vec4 fragColor;
#ifdef USE_OES
uniform samplerExternalOES uTex;
#else
uniform sampler2D uTex;
#endif
uniform mat2 uUvLin;
uniform float uTime, uAspect;
uniform float uExposure, uTemperature, uTint, uContrast, uBlackLift;
uniform vec3 uShadowColor, uHighlightColor, uHalationColor;
uniform float uShadowAmount, uHighlightAmount, uSaturation, uGreenDeepen, uCyanShift,
              uSkinProtect, uHalation, uHalationThreshold, uGrain, uVignette, uStrength;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(floor(p)));
    uint h = q.x * 1597334677u ^ q.y * 3812015801u;
    h = (h ^ (h >> 16u)) * 2246822519u;
    h = (h ^ (h >> 13u)) * 3266489917u;
    h = h ^ (h >> 16u);
    return float(h) * (1.0 / 4294967295.0);
}

void main() {
    vec3 orig = texture(uTex, vUv).rgb;
    vec3 c = orig;

    // 1. exposure + white balance
    c *= exp2(uExposure);
    c.r *= 1.0 + 0.10 * uTemperature;
    c.b *= 1.0 - 0.10 * uTemperature;
    c.g *= 1.0 - 0.06 * uTint;
    c = clamp(c, 0.0, 1.0);

    // 2. hue masks (skin incl. reds/lips, foliage greens)
    vec3 hsv = rgb2hsv(c);
    float h2 = hsv.x > 0.5 ? hsv.x - 1.0 : hsv.x;
    float skin = smoothstep(-0.06, -0.02, h2) * (1.0 - smoothstep(0.08, 0.14, h2))
               * smoothstep(0.10, 0.25, hsv.y) * smoothstep(0.15, 0.35, hsv.z);
    float green = smoothstep(0.17, 0.25, hsv.x) * (1.0 - smoothstep(0.42, 0.50, hsv.x))
                * smoothstep(0.10, 0.30, hsv.y);
    vec3 preTone = c;

    // 3. deepen greens toward teal
    vec3 g = hsv;
    g.z *= 1.0 - 0.35 * uGreenDeepen * green;
    g.x += 0.03 * uGreenDeepen * green;
    float blueMask = smoothstep(0.50, 0.56, hsv.x) * (1.0 - smoothstep(0.70, 0.78, hsv.x)) * smoothstep(0.10, 0.30, hsv.y);
    g.x -= 0.08 * uCyanShift * blueMask;
    g.y *= 1.0 + 0.15 * uGreenDeepen * green;
    c = hsv2rgb(vec3(fract(g.x), clamp(g.y, 0.0, 1.0), clamp(g.z, 0.0, 1.0)));

    // 4. contrast, lifted tinted blacks, split toning
    c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
    vec3 liftCol = uShadowColor / max(max(uShadowColor.r, uShadowColor.g), max(uShadowColor.b, 1.0e-3));
    c = c * (1.0 - uBlackLift) + uBlackLift * liftCol;
    float l = dot(c, LUMA);
    float sw = 1.0 - smoothstep(0.0, 0.55, l);
    float hw = smoothstep(0.45, 1.0, l);
    c += sw * uShadowAmount * (uShadowColor - dot(uShadowColor, LUMA));
    c += hw * uHighlightAmount * (uHighlightColor - dot(uHighlightColor, LUMA));

    // 5. protect skin and lips: pull the toned result most of the way back to the pre-tone color
    c = mix(c, preTone + (c - preTone) * 0.35, skin * uSkinProtect);

    // 6. saturation
    l = dot(c, LUMA);
    c = mix(vec3(l), c, uSaturation);

    // 7. halation: glow from bright areas, ring sampled in screen space
    vec3 glow = vec3(0.0);
    float jitter = hash(floor(vScreen * 977.0)) * 6.2831853;
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float a = jitter + fi * 2.3999632;
        float r = sqrt((fi + 0.5) / 16.0) * 0.03;
        vec2 off = vec2(cos(a) / uAspect, sin(a)) * r;
        glow += max(texture(uTex, vUv + uUvLin * off).rgb - vec3(uHalationThreshold), 0.0);
    }
    c += (glow / 16.0) * uHalation * 3.0 * uHalationColor;

    // 8. grain (midtone weighted), vignette
    float n = hash(floor(vScreen * vec2(uAspect, 1.0) * 1440.0) + floor(uTime * 24.0)) - 0.5;
    c += n * uGrain * 0.14 * (1.0 - abs(l * 2.0 - 1.0) * 0.5);
    float d = length((vScreen - 0.5) * vec2(uAspect, 1.0));
    c *= 1.0 - uVignette * smoothstep(0.25, 0.85, d);

    fragColor = vec4(mix(orig, clamp(c, 0.0, 1.0), uStrength), 1.0);
}
"""

    fun vertexSource() = "#version 300 es\n$VERTEX"
    fun fragmentSource(oes: Boolean) = buildString {
        append("#version 300 es\n")
        if (oes) append("#define USE_OES\n#extension GL_OES_EGL_image_external_essl3 : require\n")
        append(FRAGMENT)
    }
}
