package org.example
import java.io.File
import java.sql.DriverManager
import java.net.URLEncoder

// Cấu hình đường dẫn (Hãy sửa lại cho đúng với máy Mac của bạn)
const val packagePath = "/Users/quan/Downloads/datgamedetail/gbc"

const val dbpath = packagePath + "/gbc_master_metadata.db"


data class GameData(
    val crc: String,
    var title: String = "",
    var md5: String = "",
    var sha1: String = "",
    var developer: String = "",
    var publisher: String = "",
    var genre: String = "",
    var year: Int = 0,
    var releaseMonth: Int = 0,
    var platform: String = "",
    var coverArtPath: String = ""
)

/**
 * Hàm nhận diện Repo Libretro dựa trên tên hệ máy bạn cung cấp.
 * Giúp tạo link ảnh chính xác: https://raw.githubusercontent.com/libretro-thumbnails/[Platform]/...
 */
fun getLibretroPlatform(): String {
    val name = packagePath.lowercase()
    return when {
        // ST: Sufami Turbo
        name.contains("st") -> "Nintendo_-_Sufami_Turbo"
        // GBA: Game Boy Advance
        name.contains("gba") -> "Nintendo_-_Game_Boy_Advance"
        // GBC: Game Boy Color
        name.contains("gbc") -> "Nintendo_-_Game_Boy_Color"
        // GB: Game Boy
        name.contains("gb") -> "Nintendo_-_Game_Boy"
        // SNES: Super Nintendo Entertainment System
        name.contains("snes") -> "Nintendo_-_Super_Nintendo_Entertainment_System"
        // NES: Nintendo Entertainment System
        name.contains("nes") -> "Nintendo_-_Nintendo_Entertainment_System"
        else -> "Nintendo_-_Game_Boy_Advance" // Mặc định
    }
}

/**
 * Hàm lấy mã rút gọn để lưu vào cột 'platform' trong Database
 */
fun getDisplayPlatformCode(libretroName: String): String {
    return when (libretroName) {
        "Nintendo_-_Sufami_Turbo" -> "ST"
        "Nintendo_-_Game_Boy_Advance" -> "GBA"
        "Nintendo_-_Game_Boy_Color" -> "GBC"
        "Nintendo_-_Game_Boy" -> "GB"
        "Nintendo_-_Super_Nintendo_Entertainment_System" -> "SNES"
        "Nintendo_-_Nintendo_Entertainment_System" -> "NES"
        else -> "UNKNOWN"
    }
}

fun main() {
    val folder = File(packagePath)
    if (!folder.exists()) {
        println("Lỗi: Không tìm thấy thư mục tại $packagePath")
        return
    }

    val masterMap = mutableMapOf<String, GameData>()

    // Regex bóc tách khối game() và các trường dữ liệu
    val gameBlockRegex = Regex("""game\s*\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
    val crcRegex = Regex("""crc\s+([A-F0-9]{8})""", RegexOption.IGNORE_CASE)
    val nameRegex = Regex("""name\s+"(.*?)"""")
    val commentRegex = Regex("""comment\s+"(.*?)"""")
    val md5Regex = Regex("""md5\s+([A-F0-9]{32})""", RegexOption.IGNORE_CASE)
    val sha1Regex = Regex("""sha1\s+([A-F0-9]{40})""", RegexOption.IGNORE_CASE)
    val devRegex = Regex("""developer\s+"(.*?)"""")
    val pubRegex = Regex("""publisher\s+"(.*?)"""")
    val genreRegex = Regex("""genre\s+"(.*?)"""")
    val yearRegex = Regex("""releaseyear\s+"(\d+)"""")
    val monthRegex = Regex("""releasemonth\s+"(\d+)"""")

    // Quét toàn bộ file .dat trong thư mục packagePath
    val datFiles = folder.walk().filter { it.extension == "dat" }.toList()
    println("Tìm thấy ${datFiles.size} file .dat")

    datFiles.forEach { file ->
        // 1. Nhận diện hệ máy từ tên file
        val libretroName = getLibretroPlatform()
        val platformCode = getDisplayPlatformCode(libretroName)

        println("Đang đọc file: ${file.name} (Hệ máy: $platformCode)")

        val content = file.readText()
        gameBlockRegex.findAll(content).forEach { block ->
            val data = block.groupValues[1]
            val crc = crcRegex.find(data)?.groupValues?.get(1)?.uppercase() ?: return@forEach

            // 2. Lấy hoặc tạo mới Object GameData dựa trên CRC
            val game = masterMap.getOrPut(crc) { GameData(crc) }
            game.platform = platformCode

            // 3. Cập nhật thông tin (Chỉ cập nhật nếu chưa có dữ liệu)
            nameRegex.find(data)?.let { if (game.title.isEmpty()) game.title = it.groupValues[1] }
            commentRegex.find(data)?.let { if (game.title.isEmpty()) game.title = it.groupValues[1] }
            md5Regex.find(data)?.let { if (game.md5.isEmpty()) game.md5 = it.groupValues[1] }
            sha1Regex.find(data)?.let { if (game.sha1.isEmpty()) game.sha1 = it.groupValues[1] }
            devRegex.find(data)?.let { if (game.developer.isEmpty()) game.developer = it.groupValues[1] }
            pubRegex.find(data)?.let { if (game.publisher.isEmpty()) game.publisher = it.groupValues[1] }
            genreRegex.find(data)?.let { if (game.genre.isEmpty()) game.genre = it.groupValues[1] }
            yearRegex.find(data)?.let { if (game.year == 0) game.year = it.groupValues[1].toInt() }
            monthRegex.find(data)?.let { if (game.releaseMonth == 0) game.releaseMonth = it.groupValues[1].toInt() }

            // 4. Tạo link ảnh bìa động dựa trên hệ máy vừa nhận diện
            if (game.title.isNotEmpty()) {
                val cleanTitle = game.title.replace("&", "_")
                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                    .replace("+", "%20")
                    .replace("%28", "(")
                    .replace("%29", ")")

                game.coverArtPath = "https://raw.githubusercontent.com/libretro-thumbnails/$libretroName/master/Named_Boxarts/$encodedTitle.png"
            }
        }
    }

    if (masterMap.isNotEmpty()) {
        saveToSQLite(masterMap.values.toList())
    } else {
        println("Không tìm thấy dữ liệu game nào trong các file .dat!")
    }
}

fun saveToSQLite(games: List<GameData>) {
    val url = "jdbc:sqlite:$dbpath"
    try {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().execute("DROP TABLE IF EXISTS game_detail")
            conn.createStatement().execute("""
                CREATE TABLE game_detail (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    crc TEXT,
                    md5 TEXT,
                    sha1 TEXT,
                    title TEXT,
                    platform TEXT,
                    genre TEXT,
                    publisher TEXT,
                    developer TEXT,
                    releaseYear INTEGER,
                    releaseMonth INTEGER,
                    description TEXT,
                    rating REAL,
                    coverArtPath TEXT
                )
            """)

            val sql = "INSERT INTO game_detail (crc, md5, sha1, title, platform, genre, publisher, developer, releaseYear, releaseMonth, description, rating, coverArtPath) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            val ps = conn.prepareStatement(sql)

            conn.autoCommit = false
            games.forEach { g ->
                ps.setString(1, g.crc)
                ps.setString(2, g.md5)
                ps.setString(3, g.sha1)
                ps.setString(4, g.title)
                ps.setString(5, g.platform)
                ps.setString(6, g.genre)
                ps.setString(7, g.publisher)
                ps.setString(8, g.developer)
                ps.setInt(9, g.year)
                ps.setInt(10, g.releaseMonth)
                ps.setString(11, "")
                ps.setDouble(12, 0.0)
                ps.setString(13, g.coverArtPath)
                ps.addBatch()
            }
            ps.executeBatch()
            conn.commit()
            println("THÀNH CÔNG! Đã gộp ${games.size} game vào Database tại: $dbpath")
        }
    } catch (e: Exception) {
        println("LỖI SQL: ${e.message}")
        e.printStackTrace()
    }
}