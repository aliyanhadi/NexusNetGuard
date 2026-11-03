package com.nexus.netguard.data

/** PUBG Mobile regional builds. These are never blockable - they're always left untouched. */
object GamePackages {
    @JvmField
    val PACKAGES: List<String> = listOf(
        "com.tencent.ig",        // Global
        "com.pubg.krmobile",     // Korea
        "com.pubg.imobile",      // BGMI (India)
        "com.vng.pubgmobile",    // Vietnam
        "com.rekoo.pubgm",       // Taiwan
        "com.tencent.tmgp.pubgmhd", // Game for Peace / China
    )
}
