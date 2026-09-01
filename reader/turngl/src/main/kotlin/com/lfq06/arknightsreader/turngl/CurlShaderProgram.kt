package com.lfq06.arknightsreader.turngl

/**
 * Embedded GLSL ES sources for the page-curl pipeline.
 *
 * COORDINATE CONTRACT (must match CurlMesh / CurlSolver):
 * - Vertex `position.xy` holds material-space coordinates produced by
 *   CurlMesh.build: x in [originX, originX + pageW], y in [-pageH/2, +pageH/2]
 *   with +y = page BOTTOM in mesh space (canonical top was negated).
 * - `uAxisPoint` / `uAxisNormal` are the crease axis already converted into
 *   the same material space (see CurlMesh.canonicalToMeshPoint /
 *   canonicalToMeshVector).
 *
 * DEFORMATION CONTRACT (mirrors CurlSolver.deformPoint exactly; the JVM
 * mirror is CurlShaderMath.deform, and CurlShaderMathTest enforces numeric
 * parity with the solver):
 * - Let d = dot(p - uAxisPoint, n) be the signed distance to the axis and
 *   s = dot(p - uAxisPoint, t) the tangential coordinate, t = (-n.y, n.x).
 * - d <= 0 (flat front):   lat = d,                z = 0,        normal = +z.
 * - 0 < d < PI*r (cylindrical wrap, arc-length preserving):
 *     ang = d / r; lat = r*sin(ang); z = r*(1 - cos(ang));
 *     normal = (-sin(ang)*n.x, -sin(ang)*n.y, cos(ang)).
 * - d >= PI*r (flat back): lat = -(d - PI*r),      z = 2*r,      normal = -z.
 * - Radius below 1e-4 degenerates the cylinder band to a sharp crease.
 *
 * The output is outP = uAxisPoint + t*s + n*lat, lifted to z, then pushed
 * along the deformed normal by uOffset (half paper thickness) so the front
 * and back sheets never share the same depth.
 *
 * Clean-room rewrite: shader ideas follow the site's flip3d.js reference;
 * no code or comments were copied.
 */
object CurlShaderProgram {
    /** Attribute binding slot for `position` (vec3 material coords). */
    const val ATTR_POSITION = 0

    /** Attribute binding slot for `uv` (vec2, u along +x of material space). */
    const val ATTR_UV = 1

    const val VERTEX_SHADER: String = """
precision highp float;

uniform vec2 uAxisPoint;
uniform vec2 uAxisNormal;
uniform float uRadius;
uniform float uOffset;
uniform mat4 uMvp;

attribute vec3 position;
attribute vec2 uv;

varying vec2 vUv;
varying vec3 vNrm;
varying float vCrease;

const float PI = 3.14159265359;

void main() {
    vUv = uv;
    vec2 p = position.xy;
    vec2 n = normalize(uAxisNormal);
    vec2 t = vec2(-n.y, n.x);
    float r = max(uRadius, 0.0);
    vec2 rel = p - uAxisPoint;
    float d = dot(rel, n);
    float s = dot(rel, t);

    float lat;
    float z;
    vec3 nrm;
    if (d <= 0.0) {
        // Flat front sheet.
        lat = d;
        z = 0.0;
        nrm = vec3(0.0, 0.0, 1.0);
    } else if (r >= 1e-4 && d < PI * r) {
        // Cylindrical wrap: arc length preserved (lat = chord of the roll).
        float ang = d / r;
        lat = r * sin(ang);
        z = r * (1.0 - cos(ang));
        nrm = vec3(-sin(ang) * n.x, -sin(ang) * n.y, cos(ang));
    } else {
        // Flat back sheet, mirrored through the completed fold.
        lat = -(d - PI * r);
        z = 2.0 * r;
        nrm = vec3(0.0, 0.0, -1.0);
    }

    vec2 outP = uAxisPoint + t * s + n * lat;
    vec3 pos = vec3(outP.x, outP.y, z);
    // Half paper thickness: separate front/back along the deformed normal.
    pos += nrm * uOffset;

    vNrm = nrm;
    // Crease shadow: darkest on the axis, fading across the cylinder band.
    vCrease = (d > 0.0) ? (1.0 - smoothstep(0.0, r * 1.6 + 1e-4, d)) : 0.0;

    gl_Position = uMvp * vec4(pos, 1.0);
}
"""

    /**
     * The MVP matrix is supplied as 16 floats (column-major) via
     * [CurlGLRenderer]; declared here so the source contract test can verify
     * the uniform exists.
     */
    const val MVP_UNIFORM = "uMvp"

    const val FRAGMENT_SHADER: String = """
precision highp float;

uniform sampler2D uFront;
uniform sampler2D uBack;
uniform vec3 uLight;
uniform float uIsBack;
uniform float uCreaseGain;

varying vec2 vUv;
varying vec3 vNrm;
varying float vCrease;

void main() {
    vec3 N = normalize(vNrm);
    vec4 tex;
    if (uIsBack > 0.5) {
        // Back sheet mirrors horizontally (viewing the verso of the page).
        tex = texture2D(uBack, vec2(1.0 - vUv.x, vUv.y));
        N = -N;
    } else {
        tex = texture2D(uFront, vUv);
    }
    float diff = max(0.0, dot(N, normalize(uLight)));
    // Fold shadow: deepest at the crease, modulated per material.
    float shade = 1.0 - uIsBack * 0.06 - uCreaseGain * vCrease * 0.5;
    vec3 col = tex.rgb * shade * (0.88 + diff * 0.22);
    gl_FragColor = vec4(col, tex.a);
}
"""

    /** Extra fragment uniform: strength of the crease shading (0 disables). */
    const val U_CREASE_GAIN = 0.75f
}
