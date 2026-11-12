package dev.piko.shared.plex

/** 行优先的二维码矩阵；true 为深色模块。 */
data class PlexQrMatrix(
    val size: Int,
    val cells: BooleanArray,
) {
    fun dark(x: Int, y: Int): Boolean = cells[y * size + x]
}

/** 平台入口提供；桌面与 Android 共用同一份 ZXing 实现。 */
fun interface PlexQrEncoder {
    fun encode(text: String): PlexQrMatrix?
}
