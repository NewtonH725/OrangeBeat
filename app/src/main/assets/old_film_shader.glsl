#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uTime;
varying vec2 vTexCoord;

float rand(vec2 co){
    return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexCoord);
    
    // Film Grain (più naturale)
    float noise = (rand(vTexCoord * (uTime + 1.0)) - 0.5) * 0.15;
    color.rgb += noise;
    
    // Vignette (Molto più morbida e realistica)
    // Usiamo il centro (0.5, 0.5)
    vec2 center = vTexCoord - 0.5;
    float dist = length(center);
    // Smoothstep più ampio: inizia a scurire lentamente a 0.3, nero solo oltre 0.85
    float vignette = smoothstep(0.85, 0.35, dist);
    color.rgb *= vignette;
    
    // Scratches (Graffi verticali)
    // Moltiplichiamo per 300 per avere linee un po' spesse, non un singolo pixel invisibile
    float scratchX = floor(vTexCoord.x * 300.0);
    // Aumentata la probabilità di apparizione e l'impatto visivo
    float scratch = rand(vec2(scratchX, floor(uTime * 10.0)));
    if (scratch > 0.98) {
        color.rgb += 0.4; // Molto più visibile
    }
    
    gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
