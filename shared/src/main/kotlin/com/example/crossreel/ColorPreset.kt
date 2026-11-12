package com.example.crossreel

enum class ColorPreset(val title: String) {
    NONE("Nessuno"),
    TEAL_ORANGE("Teal & Orange"),
    KODAK_SUPER_8("Kodak Super 8"),
    ILFORD_PAN_F("Ilford PAN F"),
    BLEACH_BYPASS("Bleach Bypass"),
    PORTRA_400("Portra 400"),
    CYBERPUNK("Cyberpunk Neon"),
    DESATURATED_COLD("Nordic Cold"),
    ORANGE_CRUSH("Orange Crush");

    fun getMatrixArray(): FloatArray {
        // Le matrici RgbMatrix in Media3 sono 4x4 in formato column-major.
        // Array = [ R_scale_per_R, G_scale_per_R, B_scale_per_R, A_scale_per_R,
        //           R_scale_per_G, G_scale_per_G, B_scale_per_G, A_scale_per_G,
        //           R_scale_per_B, G_scale_per_B, B_scale_per_B, A_scale_per_B,
        //           R_offset,      G_offset,      B_offset,      A_offset ]
        return when (this) {
            NONE -> floatArrayOf(
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            )
            TEAL_ORANGE -> floatArrayOf(
                1.15f, -0.1f, -0.05f, 0.0f,
                0.05f, 1.05f, 0.05f, 0.0f,
                -0.15f, 0.1f, 1.25f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            )
            KODAK_SUPER_8 -> floatArrayOf(
                1.1f, 0.05f, 0.0f, 0.0f,
                0.1f, 0.9f, 0.05f, 0.0f,
                -0.1f, -0.1f, 0.8f, 0.0f,
                0.05f, 0.02f, 0.0f, 1.0f
            )
            ILFORD_PAN_F -> floatArrayOf(
                0.3f, 0.3f, 0.3f, 0.0f,
                0.59f, 0.59f, 0.59f, 0.0f,
                0.11f, 0.11f, 0.11f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            )
            BLEACH_BYPASS -> floatArrayOf(
                0.6f, 0.3f, 0.3f, 0.0f,
                0.4f, 0.7f, 0.4f, 0.0f,
                0.1f, 0.1f, 0.4f, 0.0f,
                -0.05f, -0.05f, -0.05f, 1.0f
            )
            PORTRA_400 -> floatArrayOf(
                0.9f, 0.0f, 0.0f, 0.0f,
                0.05f, 0.85f, 0.0f, 0.0f,
                0.0f, 0.05f, 0.8f, 0.0f,
                0.1f, 0.08f, 0.05f, 1.0f
            )
            CYBERPUNK -> floatArrayOf(
                0.8f, -0.2f, 0.2f, 0.0f,
                0.0f, 0.9f, 0.2f, 0.0f,
                0.4f, 0.3f, 1.2f, 0.0f,
                -0.1f, -0.1f, 0.0f, 1.0f
            )
            DESATURATED_COLD -> floatArrayOf(
                0.5f, 0.2f, 0.2f, 0.0f,
                0.3f, 0.6f, 0.3f, 0.0f,
                0.3f, 0.3f, 0.8f, 0.0f,
                -0.05f, -0.02f, 0.05f, 1.0f
            )
            ORANGE_CRUSH -> floatArrayOf(
                1.3f, 0.1f, 0.0f, 0.0f,
                0.1f, 0.9f, 0.0f, 0.0f,
                -0.2f, -0.2f, 0.6f, 0.0f,
                0.05f, 0.02f, -0.05f, 1.0f
            )
        }
    }
}
