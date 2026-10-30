package me.eternal.purrfect.common.ui.theme

import org.intellij.lang.annotations.Language

/**
 * Premium AGSL Shaders for the "Aether" skin.
 * Ported from Kyant0's AndroidLiquidGlass backdrop engine.
 */
object AetherShaders {

    @Language("AGSL")
    private const val SDF_LIB = """
        float radiusAt(float2 coord, float4 radii) {
            if (coord.x >= 0.0) {
                if (coord.y <= 0.0) return radii.y;
                else return radii.z;
            } else {
                if (coord.y <= 0.0) return radii.x;
                else return radii.w;
            }
        }

        float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            float outside = length(max(cornerCoord, 0.0)) - radius;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }

        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                return sign(coord) * normalize(max(cornerCoord, 0.0));
            } else {
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }
        }
    """

    @Language("AGSL")
    const val DISPERSION = """
        uniform shader content;
        uniform float2 size;
        uniform float4 cornerRadii;
        uniform float refractionHeight;
        uniform float refractionAmount;
        uniform float chromaticAberration;

        $SDF_LIB

        float circleMap(float x) {
            return 1.0 - sqrt(1.0 - x * x);
        }

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 centeredCoord = coord - halfSize;
            float radius = radiusAt(centeredCoord, cornerRadii);
            
            float sd = sdRoundedRect(centeredCoord, halfSize, radius);
            if (-sd >= refractionHeight) {
                return content.eval(coord);
            }
            sd = min(sd, 0.0);
            
            float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + 0.1 * normalize(centeredCoord));
            
            float2 refractedCoord = coord + d * grad;
            float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
            float2 dispersedCoord = d * grad * dispersionIntensity;
            
            half4 color = half4(0.0);
            color.r = content.eval(refractedCoord + dispersedCoord).r;
            color.g = content.eval(refractedCoord).g;
            color.b = content.eval(refractedCoord - dispersedCoord).b;
            color.a = content.eval(refractedCoord).a;
            
            return color;
        }
    """

    @Language("AGSL")
    const val HIGHLIGHT = """
        uniform float2 size;
        uniform float4 cornerRadii;
        layout(color) uniform half4 highlightColor;
        uniform float angle;
        uniform float falloff;

        $SDF_LIB

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 centeredCoord = coord - halfSize;
            float radius = radiusAt(centeredCoord, cornerRadii);
            
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
            float2 normal = float2(cos(angle), sin(angle));
            float d = dot(grad, normal);
            float intensity = pow(abs(max(d, 0.0)), falloff);
            return highlightColor * intensity;
        }
    """

    @Language("AGSL")
    const val SLAB_SCATTER = """
        uniform float2 size;
        uniform float4 cornerRadii;
        uniform float time;
        uniform float intensity;
        layout(color) uniform half4 colorTop;
        layout(color) uniform half4 colorBottom;

        $SDF_LIB

        float hash(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
        }

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 centeredCoord = coord - halfSize;
            float radius = radiusAt(centeredCoord, cornerRadii);
            float sd = sdRoundedRect(centeredCoord, halfSize, radius);
            
            if (sd > 0.0) return half4(0.0);

            float2 uv = coord / size;
            float scatter = hash(uv + time * 0.05) * 0.04 * intensity;
            
            half4 finalColor = mix(colorTop, colorBottom, uv.y);
            finalColor.rgb += scatter;
            
            return finalColor;
        }
    """
}
