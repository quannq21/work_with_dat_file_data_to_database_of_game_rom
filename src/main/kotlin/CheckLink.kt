package org.example

import okhttp3.OkHttpClient
import okhttp3.Request
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

// Đường dẫn tới file DB bạn vừa tạo
const val DB_FILE_PATH = "/Users/quan/IdeaProjects/WorkDatToEntityGameDetailGBA/WorkDatToEntityGameDetailGBA/gba_master_metadata.db"

fun main() {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val urlList = mutableListOf<String>()

    // 1. Lấy tất cả link từ Database
    try {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$DB_FILE_PATH").use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT coverArtPath FROM game_detail")
            while (rs.next()) {
                val path = rs.getString("coverArtPath")
                if (!path.isNullOrBlank()) {
                    urlList.add(path)
                }
            }
        }
    } catch (e: Exception) {
        println("Lỗi đọc Database: ${e.message}")
        return
    }

    println("Tìm thấy ${urlList.size} link cần kiểm tra. Bắt đầu quét...")

    var successCount = 0
    var errorCount = 0
    val total = urlList.size

    // 2. Kiểm tra từng link
    urlList.forEachIndexed { index, url ->
        try {
            // Sử dụng phương thức HEAD thay vì GET để chỉ kiểm tra sự tồn tại (nhanh hơn)
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    successCount++
                } else {
                    errorCount++
                    // println("Lỗi 404: $url") // Bỏ comment nếu muốn xem link nào bị lỗi
                }
            }
        } catch (e: Exception) {
            errorCount++
        }

        // Hiển thị tiến độ sau mỗi 100 link
        if ((index + 1) % 100 == 0 || index + 1 == total) {
            println("Tiến độ: ${index + 1}/$total | Thành công: $successCount | Lỗi (404/Timeout): $errorCount")
        }
    }

    println("\n=== KẾT QUẢ CUỐI CÙNG ===")
    println("Tổng số link đã kiểm tra: $total")
    println("Số ảnh tồn tại (200 OK): $successCount")
    println("Số ảnh bị lỗi (404/Không tìm thấy): $errorCount")
    println("Tỷ lệ thành công: ${String.format("%.2f", (successCount.toDouble() / total) * 100)}%")
}