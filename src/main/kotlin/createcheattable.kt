package org.example

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CheatItem(val desc: String, val code: String, val enable: Boolean)

fun main() {
    val dbPath = "C:/Users/Admin/Downloads/gamedetail/master_metadata.db"

    val chtFolderPath = "C:/Users/Admin/Downloads/gamedetail/libretro-database-master/libretro-database-master/cht/Nintendo - Game Boy Advance"

    val folder = File(chtFolderPath)
    if (!folder.exists() || !folder.isDirectory) {
        println("Thư mục không tồn tại!")
        return
    }

    val platformName = folder.name
    val dbPlatformValue = when {

        // ST: Sufami Turbo
        platformName.contains("Nintendo - Sufami Turbo",  ) -> "ST"
        // GBA: Game Boy Advance
        platformName.contains("Nintendo - Game Boy Advance") -> "GBA"
        // GBC: Game Boy Color
        platformName.contains("Nintendo - Game Boy Color") -> "GBC"
        // GB: Game Boy
        platformName.contains("GB") -> "Nintendo - Game Boy"
        // SNES: Super Nintendo Entertainment System
        platformName.contains("Nintendo - Super Nintendo Entertainment System") -> "SNES"
        // NES: Nintendo Entertainment System
        platformName.contains("Nintendo - Nintendo Entertainment System") -> "NES"

        platformName.contains("Nintendo - Family Computer Disk System") -> "FCDS"
        platformName.contains("Nintendo - Satellaview") -> "SATELLAVIEW"

        else -> platformName
    }

    val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    conn.autoCommit = false

    try {
        initCheatTableOnly(conn)

        val chtFiles = folder.listFiles { _, name -> name.endsWith(".cht") } ?: emptyArray()
        println("Đang xử lý hệ máy: $dbPlatformValue | Tổng số file .cht: ${chtFiles.size}")

        var successCount = 0
        var failCount = 0

        chtFiles.forEach { file ->
            val groupName = file.nameWithoutExtension
            val cheats = parseChtFile(file)

            if (cheats.isNotEmpty()) {
                val jsonCheats = Json.encodeToString(cheats)
                insertCheatData(conn, groupName, dbPlatformValue, cheats.size, jsonCheats)
                successCount++
            } else {
                // TÍNH NĂNG MỚI: In thông tin file không hợp lệ
                failCount++
                println("--------------------------------------------------")
                println("[LỖI/TRỐNG] File thứ $failCount: ${file.name}")
                println("Đường dẫn: ${file.absolutePath}")
                println("Nội dung bên trong:")
                try {
                    val content = file.readText()
                    if (content.isBlank()) {
                        println("<File hoàn toàn trống>")
                    } else {
                        println(content)
                    }
                } catch (e: Exception) {
                    println("<Không thể đọc nội dung: ${e.message}>")
                }
                println("--------------------------------------------------")
            }
        }

        conn.commit()
        println("\n--- TỔNG KẾT ---")
        println("Thành công: $successCount")
        println("Không hợp lệ (đã in log): $failCount")
        println("Tổng cộng: ${successCount + failCount}")

    } catch (e: Exception) {
        conn.rollback()
        e.printStackTrace()
    } finally {
        conn.close()
    }
}

// --- CÁC HÀM GIỮ NGUYÊN NHƯ CŨ ---

fun initCheatTableOnly(conn: Connection) {
    val stmt = conn.createStatement()
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

fun insertCheatData(conn: Connection, groupName: String, platform: String, count: Int, json: String) {
    val sql = "INSERT INTO cheat_game (group_name, platform, countCheat, cheatCodeJson) VALUES (?, ?, ?, ?)"
    conn.prepareStatement(sql).use { pstmt ->
        pstmt.setString(1, groupName)
        pstmt.setString(2, platform)
        pstmt.setInt(3, count)
        pstmt.setString(4, json)
        pstmt.executeUpdate()
    }
}

fun parseChtFile(file: File): List<CheatItem> {
    val result = mutableListOf<CheatItem>()
    try {
        val lines = file.readLines()
        val count = lines.find { it.startsWith("cheats =") }
            ?.substringAfter("=")?.trim()?.toIntOrNull() ?: 0

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
    } catch (e: Exception) { }
    return result
}