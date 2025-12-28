package org.example

import java.io.File
import java.sql.DriverManager

fun main() {
    val rootFolderPath = "/Users/quan/Downloads/datgamedetail"
    val outputDbPath = rootFolderPath + "/master_metadata.db"

    val rootFolder = File(rootFolderPath)
    val outputFile = File(outputDbPath)

    if (!rootFolder.exists() || !rootFolder.isDirectory) {
        println("Lỗi: Thư mục gốc không tồn tại!")
        return
    }

    val dbFiles = rootFolder.walk()
        .filter { it.isFile && it.extension == "db" }
        .filter { it.absolutePath != outputFile.absolutePath }
        .toList()

    if (dbFiles.isEmpty()) {
        println("Không tìm thấy file .db nào!")
        return
    }

    println("Tìm thấy ${dbFiles.size} file database. Bắt đầu gộp...")
    mergeDatabases(dbFiles, outputDbPath)
}

fun mergeDatabases(dbFiles: List<File>, outputDbPath: String) {
    val url = "jdbc:sqlite:$outputDbPath"
    File(outputDbPath).delete()

    var totalRowsInSource = 0
    val duplicateDetails = mutableListOf<String>() // Danh sách lưu thông tin trùng

    try {
        DriverManager.getConnection(url).use { conn ->
            val stmt = conn.createStatement()

            // 1. Tạo bảng Master
            stmt.execute("""
                CREATE TABLE game_detail (
                    crc TEXT PRIMARY KEY,
                    title TEXT,
                    md5 TEXT,
                    sha1 TEXT,
                    developer TEXT,
                    publisher TEXT,
                    genre TEXT,
                    releaseYear INTEGER, 
                    releaseMonth INTEGER,
                    platform TEXT,
                    coverArtPath TEXT
                )
            """.trimIndent())

            dbFiles.forEach { file ->
                val alias = "db_${file.hashCode().filterPositive()}"
                val absolutePath = file.absolutePath.replace("\\", "/")

                try {
                    stmt.execute("ATTACH DATABASE '$absolutePath' AS $alias")

                    // Lấy dữ liệu từ file đang attach
                    val rs = stmt.executeQuery("SELECT * FROM $alias.game_detail")

                    println("Đang xử lý: ${file.name} ...")

                    while (rs.next()) {
                        totalRowsInSource++
                        val crc = rs.getString("crc")
                        val title = rs.getString("title")
                        val platform = rs.getString("platform")

                        // Kiểm tra xem CRC này đã tồn tại trong bảng chính chưa
                        val checkStmt = conn.prepareStatement("SELECT title, platform FROM game_detail WHERE crc = ?")
                        checkStmt.setString(1, crc)
                        val checkRs = checkStmt.executeQuery()

                        if (checkRs.next()) {
                            // Nếu đã tồn tại, lưu thông tin trùng để hiển thị sau
                            val existingTitle = checkRs.getString("title")
                            val existingPlatform = checkRs.getString("platform")
                            duplicateDetails.add("Trùng CRC [$crc]:\n   - Đã có: $existingTitle ($existingPlatform)\n   - Bỏ qua: $title ($platform)")
                        } else {
                            // Nếu chưa có, tiến hành chèn vào bảng Master
                            val insertStmt = conn.prepareStatement("""
                                INSERT INTO game_detail (crc, title, md5, sha1, developer, publisher, genre, releaseYear, releaseMonth, platform, coverArtPath)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                            insertStmt.setString(1, crc)
                            insertStmt.setString(2, title)
                            insertStmt.setString(3, rs.getString("md5"))
                            insertStmt.setString(4, rs.getString("sha1"))
                            insertStmt.setString(5, rs.getString("developer"))
                            insertStmt.setString(6, rs.getString("publisher"))
                            insertStmt.setString(7, rs.getString("genre"))
                            insertStmt.setInt(8, rs.getInt("releaseYear"))
                            insertStmt.setInt(9, rs.getInt("releaseMonth"))
                            insertStmt.setString(10, platform)
                            insertStmt.setString(11, rs.getString("coverArtPath"))
                            insertStmt.executeUpdate()
                        }
                        checkRs.close()
                    }
                    rs.close()
                    stmt.execute("DETACH DATABASE $alias")
                } catch (e: Exception) {
                    println("Lỗi khi xử lý file ${file.name}: ${e.message}")
                    try { stmt.execute("DETACH DATABASE $alias") } catch (ex: Exception) {}
                }
            }

            // 3. Tổng kết và hiển thị bản ghi trùng
            val rsFinal = stmt.executeQuery("SELECT COUNT(*) FROM main.game_detail")
            val finalCount = if (rsFinal.next()) rsFinal.getInt(1) else 0
            rsFinal.close()

            println("\n--- DANH SÁCH BẢN GHI TRÙNG LẶP ---")
            if (duplicateDetails.isEmpty()) {
                println("Không có bản ghi nào bị trùng.")
            } else {
                duplicateDetails.forEach { println(it) }
            }

            println("\n--- BÁO CÁO TỔNG HỢP ---")
            println("Tổng số bản ghi từ tất cả nguồn: $totalRowsInSource")
            println("Số bản ghi duy nhất (Unique CRC): $finalCount")
            println("Số lượng CRC bị trùng đã loại bỏ : ${duplicateDetails.size}")
            println("File tổng hợp: $outputDbPath")
        }
    } catch (e: Exception) {
        println("Lỗi kết nối Database: ${e.message}")
    }
}

fun Int.filterPositive(): Int = if (this < 0) this * -1 else this