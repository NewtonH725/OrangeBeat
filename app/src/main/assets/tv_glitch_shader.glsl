#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uTime;
varying vec2 vTexCoord;

float rand(vec2 co){
    return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}

// Distorsione a barilotto (CRT TV curve)
vec2 barrelDistortion(vec2 uv, float amt) {
    vec2 cc = uv - 0.5;
    float dist = dot(cc, cc);
    return uv + cc * (dist * amt);
}

void main() {
    vec2 uv = barrelDistortion(vTexCoord, 0.25);
    
    // Jitter / Tearing
    float block = floor(uv.y * 20.0);
    float jitter = rand(vec2(block, floor(uTime * 15.0))) * 2.0 - 1.0;
    
    if (rand(vec2(floor(uTime * 15.0), 0.0)) > 0.70) {
        uv.x += jitter * 0.08; 
    }
    
    // Aberrazione Cromatica (RGB Split) ridotta per maggiore leggibilità
    float splitAmount = 0.004 + (rand(vec2(floor(uTime * 10.0), 1.0)) > 0.8 ? 0.01 : 0.0);
    
    float r = texture2D(uTexSampler, vec2(uv.x + splitAmount, uv.y)).r;
    float g = texture2D(uTexSampler, uv).g;
    float b = texture2D(uTexSampler, vec2(uv.x - splitAmount, uv.y)).b;
    
    // Scanlines appena percepibili
    // Invece di sottrarre colori (che distrugge i neri), usiamo un moltiplicatore morbidissimo.
    // Il seno va da 0 a 1. Lo moltiplichiamo per 0.015 (impatto massimo 1.5%).
    float scanline = (sin(uv.y * 800.0) + 1.0) * 0.5;
    float scanMultiplier = 1.0 - (scanline * 0.015); // Abbassa la luminosità di MAX 1.5%
    
    r *= scanMultiplier;
    g *= scanMultiplier;
    b *= scanMultiplier;
    
    // Effetto Vignetta TV catodica (Morbida)
    float vignette = smoothstep(1.0, 0.5, length(vTexCoord - 0.5));
    
    gl_FragColor = vec4(r * vignette, g * vignette, b * vignette, 1.0);
}
