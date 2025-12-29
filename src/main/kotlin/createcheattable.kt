package org.example

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CheatItem(
    val desc: String,
    val code: String,
    val enable: Boolean
)

fun main() {
    // 1. Cấu hình đường dẫn
    val dbPath = "C:/Users/Admin/Downloads/gamedetail/master_metadata.db"
    val chtFolderPath = "C:/Users/Admin/Downloads/gamedetail/libretro-database-master/libretro-database-master/cht/Nintendo - Nintendo Entertainment System"

    val folder = File(chtFolderPath)
    val folderName = folder.name

    // 2. Xác định Platform dựa trên tên thư mục
    val dbPlatformValue = when {
        folderName.contains("Entertainment System", true) -> "NES"
        folderName.contains("Super Nintendo", true) -> "SNES"
        folderName.contains("Advance", true) -> "GBA"
        folderName.contains("Color", true) -> "GBC"
        else -> folderName // Mặc định lấy tên thư mục nếu không khớp
    }

    val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    conn.autoCommit = false

    try {
        // Nâng cấp Schema với các ràng buộc NOT NULL
        upgradeDatabaseSchema(conn)

        val chtFiles = folder.listFiles { _, name -> name.endsWith(".cht") } ?: emptyArray()
        println("Platform: $dbPlatformValue | Tìm thấy ${chtFiles.size} file .cht")

        chtFiles.forEach { file ->
            val originalFileName = file.nameWithoutExtension
            val cleanedGroupName = sanitizeGameTitle(originalFileName)

            // Bước 2: Tạo Group kèm Platform
            insertGroupIfNotExist(conn, cleanedGroupName, dbPlatformValue)

            // Bước 3: Mapping chỉ trong cùng một Platform
            mapGamesToGroup(conn, cleanedGroupName, dbPlatformValue)

            // Bước 4: Parse và lưu cheat vào bảng cheat_game
            val cheats = parseChtFile(file)
            if (cheats.isNotEmpty()) {
                val jsonCheats = Json.encodeToString(cheats)
                insertCheatData(conn, cleanedGroupName, dbPlatformValue, cheats.size, jsonCheats)
                println("Đã xử lý: [$dbPlatformValue] $cleanedGroupName")
            }
        }

        conn.commit()
        println("--- HOÀN THÀNH HỆ MÁY $dbPlatformValue ---")
    } catch (e: Exception) {
        conn.rollback()
        println("Lỗi nghiêm trọng: ${e.message}")
        e.printStackTrace()
    } finally {
        conn.close()
    }
}

fun upgradeDatabaseSchema(conn: Connection) {
    val stmt = conn.createStatement()

    // Tạo bảng game_groups với NOT NULL và Composite Key
    stmt.execute("""
        CREATE TABLE IF NOT EXISTS game_groups (
            group_name TEXT NOT NULL, 
            platform TEXT NOT NULL,
            PRIMARY KEY(group_name, platform)
        )
    """.trimIndent())

    // Thêm cột group_name vào game_detail nếu chưa có (SQLite mặc định hỗ trợ NULL khi ALTER,
    // nhưng các bản ghi mới sẽ được xử lý qua code)
    try {
        stmt.execute("ALTER TABLE game_detail ADD COLUMN group_name TEXT")
    } catch (e: Exception) { /* Đã tồn tại */ }

    // Tạo bảng cheat_game với các cột NOT NULL
    stmt.execute("""
        CREATE TABLE IF NOT EXISTS cheat_game (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
            group_name TEXT NOT NULL, 
            platform TEXT NOT NULL,
            countCheat INTEGER NOT NULL, 
            cheatCodeJson TEXT NOT NULL
        )
    """.trimIndent())
    stmt.close()
}

fun sanitizeGameTitle(title: String): String {
    return title
        .replace(Regex("\\(.*?\\)"), "")
        .replace(Regex("\\[.*?\\]"), "")
        .trim()
}

fun insertGroupIfNotExist(conn: Connection, groupName: String, platform: String) {
    // Sử dụng INSERT OR IGNORE để tránh lỗi trùng khóa chính (Name + Platform)
    val sql = "INSERT OR IGNORE INTO game_groups (group_name, platform) VALUES (?, ?)"
    conn.prepareStatement(sql).use { pstmt ->
        pstmt.setString(1, groupName)
        pstmt.setString(2, platform)
        pstmt.executeUpdate()
    }
}

fun mapGamesToGroup(conn: Connection, groupName: String, platform: String) {
    // UPDATE có điều kiện Platform chặt chẽ
    val sql = "UPDATE game_detail SET group_name = ? WHERE title LIKE ? AND platform = ?"
    conn.prepareStatement(sql).use { pstmt ->
        pstmt.setString(1, groupName)
        pstmt.setString(2, "$groupName%")
        pstmt.setString(3, platform)
        pstmt.executeUpdate()
    }
}

fun insertCheatData(conn: Connection, groupName: String, platform: String, count: Int, json: String) {
    // Kiểm tra trùng dựa trên cả group_name và platform
    val checkSql = "SELECT id FROM cheat_game WHERE group_name = ? AND platform = ? LIMIT 1"
    val exists = conn.prepareStatement(checkSql).use { pstmtCheck ->
        pstmtCheck.setString(1, groupName)
        pstmtCheck.setString(2, platform)
        pstmtCheck.executeQuery().next()
    }

    if (!exists) {
        val sql = "INSERT INTO cheat_game (group_name, platform, countCheat, cheatCodeJson) VALUES (?, ?, ?, ?)"
        conn.prepareStatement(sql).use { pstmt ->
            pstmt.setString(1, groupName)
            pstmt.setString(2, platform)
            pstmt.setInt(3, count)
            pstmt.setString(4, json)
            pstmt.executeUpdate()
        }
    }
}

fun parseChtFile(file: File): List<CheatItem> {
    val result = mutableListOf<CheatItem>()
    try {
        val lines = file.readLines()
        val countLine = lines.find { it.startsWith("cheats =") } ?: return emptyList()
        val count = countLine.substringAfter("=").trim().toIntOrNull() ?: 0

        for (i in 0 until count) {
            val desc = lines.find { it.startsWith("cheat${i}_desc =") }
                ?.substringAfter("=")?.trim()?.removeSurrounding("\"") ?: ""
            val code = lines.find { it.startsWith("cheat${i}_code =") }
                ?.substringAfter("=")?.trim()?.removeSurrounding("\"") ?: ""
            val enable = lines.find { it.startsWith("cheat${i}_enable =") }
                ?.substringAfter("=")?.trim()?.toBoolean() ?: false

            if (code.isNotEmpty()) {
                result.add(CheatItem(desc, code, enable))
            }
        }
    } catch (e: Exception) {
        println("Lỗi file ${file.name}: ${e.message}")
    }
    return result
}